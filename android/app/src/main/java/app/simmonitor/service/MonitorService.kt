package app.simmonitor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.telephony.SubscriptionManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.simmonitor.MainActivity
import app.simmonitor.R
import app.simmonitor.net.DesktopConnection
import app.simmonitor.probe.Sims
import app.simmonitor.settings.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import org.json.JSONArray
import org.json.JSONObject

/**
 * Foreground service: holds the desktop connection while Wi-Fi is up, runs instructions,
 * forwards carrier SMS. Started from the app, at boot and after app updates when enabled.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var connection: DesktopConnection
    private lateinit var runner: InstructionRunner
    private var wifi: Network? = null
    private var lastSims: String? = null
    private val outbox = ArrayDeque<String>() // carrier SMS waiting for a connection

    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (wifi == network) return
            wifi = network
            connection.bind(network, Prefs.pairing.value)
        }

        override fun onLost(network: Network) {
            if (wifi != network) return
            wifi = null
            connection.bind(null, Prefs.pairing.value)
        }
    }

    @Suppress("DEPRECATION") // the Executor overload needs API 30
    private val simListener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() {
            scope.launch { sendSimsIfChanged() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.load(this)
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(MonitorState.state.value),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        runner = InstructionRunner(this)
        connection = DesktopConnection(scope, ::onOpen, ::onText)
        MonitorState.set(MonitorState.Kind.WAITING_WIFI)

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // a LAN without internet is fine
            .build()
        cm.registerNetworkCallback(request, networkCallback)
        runCatching { getSystemService(SubscriptionManager::class.java).addOnSubscriptionsChangedListener(simListener) }

        scope.launch {
            MonitorState.state.collect { state ->
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
            }
        }
        scope.launch {
            SmsBus.flow.collect { sms ->
                if (!runner.offer(sms)) forwardSms(sms)
            }
        }
        scope.launch {
            // Re-pairing while running: reconnect with the new details.
            Prefs.pairing.drop(1).collect { connection.bind(wifi, it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { cm.unregisterNetworkCallback(networkCallback) }
        runCatching { getSystemService(SubscriptionManager::class.java).removeOnSubscriptionsChangedListener(simListener) }
        connection.stop()
        scope.cancel()
        MonitorState.set(MonitorState.Kind.STOPPED)
        super.onDestroy()
    }

    // protocol -------------------------------------------------------------------------------

    private fun onOpen(ws: WebSocket) {
        val sims = simsJson()
        lastSims = sims.toString()
        ws.send(
            JSONObject()
                .put("type", "hello")
                .put("phone_id", Prefs.phoneId(this))
                .put("name", Prefs.phoneName(this))
                .put("app_version", appVersion())
                .put("sims", sims)
                .toString()
        )
        synchronized(outbox) {
            while (outbox.isNotEmpty()) ws.send(outbox.removeFirst())
        }
    }

    private fun onText(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (msg.optString("type")) {
            "config" -> msg.optJSONArray("allowed_senders")?.let { arr ->
                Prefs.setAllowedSenders(this, (0 until arr.length()).map { arr.getString(it) }.toSet())
            }
            "run" -> scope.launch {
                val subId = msg.getInt("sub_id")
                val ins = msg.getJSONObject("instruction")
                MonitorState.setBusy(ins.optString("code").ifEmpty { "SMS" })
                val result = try {
                    runner.run(subId, ins)
                } finally {
                    MonitorState.setBusy(null)
                }
                result.put("type", "result").put("request_id", msg.getString("request_id"))
                connection.send(result.toString())
            }
        }
    }

    private fun forwardSms(sms: SmsBus.Sms) {
        val json = JSONObject()
            .put("type", "sms").put("sub_id", sms.subId).put("sender", sms.sender)
            .put("body", sms.body).put("ts", sms.ts.toString())
            .toString()
        if (!connection.send(json)) synchronized(outbox) {
            if (outbox.size >= 50) outbox.removeFirst()
            outbox.addLast(json)
        }
    }

    private fun sendSimsIfChanged() {
        val sims = simsJson()
        if (sims.toString() == lastSims) return
        if (connection.send(JSONObject().put("type", "sims").put("sims", sims).toString())) {
            lastSims = sims.toString()
        }
    }

    private fun simsJson() = JSONArray(Sims.list(this).map {
        JSONObject()
            .put("sub_id", it.subId).put("slot", it.slot).put("carrier_name", it.carrierName)
            .put("mcc", it.mcc ?: JSONObject.NULL).put("mnc", it.mnc ?: JSONObject.NULL)
            .put("number", it.number ?: JSONObject.NULL)
    })

    private fun appVersion(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

    // notification ---------------------------------------------------------------------------

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Monitor status", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows whether the phone is connected to the desktop"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun notification(state: MonitorState.State): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(state.title)
            .setContentText(state.detail)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL = "monitor"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "app.simmonitor.STOP"

        fun start(context: Context) {
            Prefs.setEnabled(context, true)
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        }

        fun stop(context: Context) {
            Prefs.setEnabled(context, false)
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
