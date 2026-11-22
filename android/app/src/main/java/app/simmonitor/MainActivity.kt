package app.simmonitor

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import app.simmonitor.net.Pairing
import app.simmonitor.probe.ProbeLog
import app.simmonitor.service.MonitorService
import app.simmonitor.settings.Prefs
import app.simmonitor.ui.DiagnosticsScreen
import app.simmonitor.ui.SettingsScreen
import app.simmonitor.ui.Setup
import app.simmonitor.ui.StatusScreen

class MainActivity : ComponentActivity() {
    /** Pairing waiting for the user's confirmation (from a QR scan, pasted link or deep link). */
    private var pendingPairing by mutableStateOf<Pairing?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Prefs.load(this)
        ProbeLog.load(this)
        handleIntent(intent)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                var tab by rememberSaveable { mutableStateOf(0) }
                Scaffold(bottomBar = {
                    NavigationBar {
                        TABS.forEachIndexed { i, (label, icon) ->
                            NavigationBarItem(
                                selected = tab == i, onClick = { tab = i },
                                icon = { Icon(painterResource(icon), contentDescription = null) },
                                label = { Text(label) },
                            )
                        }
                    }
                }) { padding ->
                    val m = Modifier.padding(padding)
                    when (tab) {
                        0 -> StatusScreen(onOpenSettings = { tab = 1 }, modifier = m)
                        1 -> SettingsScreen(onPairing = { pendingPairing = it }, modifier = m)
                        else -> DiagnosticsScreen(m)
                    }
                }
                pendingPairing?.let { p -> ConfirmPairing(p) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "simmonitor") Pairing.parse(uri.toString())?.let { pendingPairing = it }
    }

    @androidx.compose.runtime.Composable
    private fun ConfirmPairing(p: Pairing) {
        AlertDialog(
            onDismissRequest = { pendingPairing = null },
            title = { Text("Pair with this desktop?") },
            text = {
                Text(
                    "Server ${p.host}:${p.port}\nCertificate ${p.caSha256.take(16).chunked(4).joinToString(" ")}…\n\n" +
                        "Only continue if this came from your own dashboard. The desktop will be able to run " +
                        "balance checks on this phone's SIMs.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Prefs.savePairing(this, p)
                    pendingPairing = null
                    if (Setup.missing(this, Setup.phonePermissions).isEmpty()) MonitorService.start(this)
                }) { Text("Pair") }
            },
            dismissButton = { TextButton(onClick = { pendingPairing = null }) { Text("Cancel") } },
        )
    }

    companion object {
        private val TABS = listOf(
            "Status" to R.drawable.ic_tab_status,
            "Settings" to R.drawable.ic_tab_settings,
            "Diagnostics" to R.drawable.ic_tab_diagnostics,
        )
    }
}
