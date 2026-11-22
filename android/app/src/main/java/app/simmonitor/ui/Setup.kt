package app.simmonitor.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

/** Permission / system-setting checks shared by the status screen. */
object Setup {
    val phonePermissions: Array<String> = buildList {
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_PHONE_NUMBERS)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.RECEIVE_SMS)
    }.toTypedArray()

    fun missing(c: Context, perms: Array<String>) =
        perms.filter { c.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    fun notificationsGranted(c: Context) =
        Build.VERSION.SDK_INT < 33 ||
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun batteryExempt(c: Context) =
        c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

    @SuppressLint("BatteryLife") // sideloaded, not on Play; staying connected is the app's whole job
    fun requestBatteryExemption(c: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${c.packageName}".toUri())
        runCatching { c.startActivity(direct) }.onFailure {
            c.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    fun openAppSettings(c: Context) {
        c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${c.packageName}".toUri()))
    }

    /** Extra background-killing steps some brands need beyond the standard exemption. */
    fun vendorHint(): String? = when (Build.MANUFACTURER.lowercase()) {
        "oneplus", "oppo", "realme" ->
            "OnePlus/Oppo: App info → Battery → Allow background activity, and lock the app in Recents " +
                "(swipe down on its card)."
        "xiaomi", "redmi", "poco" -> "Xiaomi: App info → Autostart on, Battery saver → No restrictions."
        "samsung" -> "Samsung: Battery → Background usage limits → add SIM Monitor to Never sleeping apps."
        "huawei", "honor" -> "Huawei: Battery → App launch → SIM Monitor → Manage manually, all on."
        "vivo", "iqoo" -> "Vivo: Battery → Background power consumption → allow SIM Monitor."
        else -> null
    }
}
