package app.simmonitor.probe

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

sealed interface RunResult {
    data class Reply(val text: String) : RunResult
    data class Failed(val reason: String) : RunResult
}

object UssdRunner {
    /** Single-shot USSD on a specific SIM. Menus can't be answered. */
    @RequiresPermission(Manifest.permission.CALL_PHONE)
    suspend fun run(context: Context, subId: Int, code: String, timeoutMs: Long = 45_000): RunResult {
        val tm = context.getSystemService(TelephonyManager::class.java).createForSubscriptionId(subId)
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val cb = object : TelephonyManager.UssdResponseCallback() {
                    override fun onReceiveUssdResponse(t: TelephonyManager, req: String, resp: CharSequence) {
                        if (cont.isActive) cont.resume(RunResult.Reply(resp.toString()))
                    }

                    override fun onReceiveUssdResponseFailed(t: TelephonyManager, req: String, code: Int) {
                        if (cont.isActive) cont.resume(RunResult.Failed(failureName(code)))
                    }
                }
                try {
                    tm.sendUssdRequest(code, cb, Handler(Looper.getMainLooper()))
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(RunResult.Failed("exception: ${e.message}"))
                }
            }
        } ?: RunResult.Failed("timeout after ${timeoutMs / 1000}s")
    }

    private fun failureName(code: Int) = when (code) {
        TelephonyManager.USSD_RETURN_FAILURE -> "USSD_RETURN_FAILURE ($code)"
        TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL -> "USSD_ERROR_SERVICE_UNAVAIL ($code)"
        else -> "code $code"
    }
}

object SmsRunner {
    private const val ACTION_SENT = "app.simmonitor.SMS_SENT"

    /** Sends one SMS from a specific SIM and waits for the radio's send result (not the reply). */
    @RequiresPermission(Manifest.permission.SEND_SMS)
    suspend fun send(context: Context, subId: Int, to: String, body: String): RunResult {
        val app = context.applicationContext
        val sms = smsManager(app, subId)
        return withTimeoutOrNull(60_000) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        app.unregisterReceiver(this)
                        val r = if (resultCode == Activity.RESULT_OK) RunResult.Reply("sent")
                        else RunResult.Failed("send failed: ${smsErrorName(resultCode)}")
                        if (cont.isActive) cont.resume(r)
                    }
                }
                ContextCompat.registerReceiver(
                    app, receiver, IntentFilter(ACTION_SENT), ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                cont.invokeOnCancellation { runCatching { app.unregisterReceiver(receiver) } }
                val sent = PendingIntent.getBroadcast(
                    app, 0, Intent(ACTION_SENT).setPackage(app.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                try {
                    sms.sendTextMessage(to, null, body, sent, null)
                } catch (e: Exception) {
                    runCatching { app.unregisterReceiver(receiver) }
                    if (cont.isActive) cont.resume(RunResult.Failed("exception: ${e.message}"))
                }
            }
        } ?: RunResult.Failed("no send result after 60s")
    }

    /** SmsManager result codes (values are stable ints; names from AOSP). */
    fun smsErrorName(code: Int): String = when (code) {
        1 -> "GENERIC_FAILURE"
        2 -> "RADIO_OFF"
        3 -> "NULL_PDU"
        4 -> "NO_SERVICE"
        5 -> "LIMIT_EXCEEDED"
        6 -> "FDN_CHECK_FAILURE"
        7 -> "SHORT_CODE_NOT_ALLOWED"
        8 -> "SHORT_CODE_NEVER_ALLOWED"
        9 -> "RADIO_NOT_AVAILABLE"
        10 -> "NETWORK_REJECT"
        100 -> "RIL_RADIO_NOT_AVAILABLE"
        101 -> "RIL_SMS_SEND_FAIL_RETRY (network refused; often no credit)"
        102 -> "RIL_NETWORK_REJECT"
        103 -> "RIL_INVALID_STATE"
        107 -> "RIL_SYSTEM_ERR"
        111 -> "RIL_INVALID_SMS_FORMAT"
        112 -> "RIL_NETWORK_ERR"
        else -> "code $code"
    }.let { "$it ($code)" }

    @Suppress("DEPRECATION")
    private fun smsManager(context: Context, subId: Int): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
        } else {
            SmsManager.getSmsManagerForSubscriptionId(subId)
        }
}
