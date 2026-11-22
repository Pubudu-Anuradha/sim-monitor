package app.simmonitor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.simmonitor.settings.Prefs

/** Restarts the monitor after a reboot or an app update (installs kill the service). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        Prefs.load(context)
        if (Prefs.enabled(context) && Prefs.pairing.value != null) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
            }
        }
    }
}
