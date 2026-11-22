package app.simmonitor.settings

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import app.simmonitor.net.Pairing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** App settings in private SharedPreferences. [pairing] is observable for the UI. */
object Prefs {
    private const val FILE = "sim_monitor"
    private val _pairing = MutableStateFlow<Pairing?>(null)
    val pairing: StateFlow<Pairing?> = _pairing.asStateFlow()
    private var loaded = false

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    fun load(c: Context) {
        if (loaded) return
        loaded = true
        val p = sp(c)
        val host = p.getString("host", null)
        val token = p.getString("token", null)
        val pin = p.getString("ca_sha256", null)
        if (host != null && token != null && pin != null) {
            _pairing.value = Pairing(host, p.getInt("port", 8443), token, pin)
        }
    }

    fun savePairing(c: Context, pairing: Pairing?) {
        sp(c).edit {
            if (pairing == null) {
                remove("host"); remove("port"); remove("token"); remove("ca_sha256")
            } else {
                putString("host", pairing.host)
                putInt("port", pairing.port)
                putString("token", pairing.token)
                putString("ca_sha256", pairing.caSha256)
            }
        }
        _pairing.value = pairing
    }

    /** Stable random ID for this install; the desktop keys SIMs by it. */
    @Synchronized
    fun phoneId(c: Context): String {
        val p = sp(c)
        return p.getString("phone_id", null) ?: UUID.randomUUID().toString().also {
            p.edit { putString("phone_id", it) }
        }
    }

    fun phoneName(c: Context): String =
        sp(c).getString("phone_name", null)?.takeIf { it.isNotBlank() } ?: defaultName(c)

    fun setPhoneName(c: Context, name: String) = sp(c).edit { putString("phone_name", name.trim()) }

    private fun defaultName(c: Context): String =
        Settings.Global.getString(c.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    /** Whether the user wants the monitor running (restarted at boot and after updates). */
    fun enabled(c: Context) = sp(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, on: Boolean) = sp(c).edit { putBoolean("enabled", on) }

    /** Normalized carrier senders from the desktop's config message; null until first connect. */
    fun allowedSenders(c: Context): Set<String>? = sp(c).getStringSet("allowed_senders", null)
    fun setAllowedSenders(c: Context, senders: Set<String>) = sp(c).edit { putStringSet("allowed_senders", senders) }
}
