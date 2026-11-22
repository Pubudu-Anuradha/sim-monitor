package app.simmonitor.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.simmonitor.probe.Sims
import app.simmonitor.service.MonitorService
import app.simmonitor.service.MonitorState
import app.simmonitor.service.MonitorState.Kind
import app.simmonitor.settings.Prefs

@Composable
fun StatusScreen(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by MonitorState.state.collectAsState()
    val pairing by Prefs.pairing.collectAsState()
    // Re-read permissions/settings when coming back from system screens.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    val missingPhone = remember(refresh) { Setup.missing(context, Setup.phonePermissions) }
    val notifications = remember(refresh) { Setup.notificationsGranted(context) }
    val battery = remember(refresh) { Setup.batteryExempt(context) }
    val sims = remember(refresh, missingPhone) { Sims.list(context) }
    val running = state.kind != Kind.STOPPED

    val phoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh++
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("SIM Monitor", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp)) }

        item {
            val color = when (state.kind) {
                Kind.CONNECTED -> MaterialTheme.colorScheme.primaryContainer
                Kind.BAD_PAIRING, Kind.RETRYING -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = color)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.title, style = MaterialTheme.typography.titleLarge)
                    state.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (running) {
                            OutlinedButton(onClick = { MonitorService.stop(context) }) { Text("Stop") }
                        } else {
                            Button(
                                onClick = { MonitorService.start(context) },
                                enabled = pairing != null && missingPhone.isEmpty(),
                            ) { Text("Start") }
                        }
                        if (pairing == null || state.kind == Kind.BAD_PAIRING) {
                            TextButton(onClick = onOpenSettings) { Text("Pair…") }
                        }
                    }
                }
            }
        }

        item { Text("Setup", style = MaterialTheme.typography.titleMedium) }
        item {
            SetupItem("Paired with desktop", pairing != null, pairing?.let { "${it.host}:${it.port}" }) {
                TextButton(onClick = onOpenSettings) { Text("Pair") }
            }
        }
        item {
            SetupItem(
                "Phone & SMS permissions", missingPhone.isEmpty(),
                if (missingPhone.isEmpty()) null else "Missing: ${missingPhone.joinToString { it.substringAfterLast('.') }}. " +
                    "If SMS can't be granted: App info → ⋮ → Allow restricted settings.",
            ) {
                TextButton(onClick = { phoneLauncher.launch(Setup.phonePermissions) }) { Text("Grant") }
                TextButton(onClick = { Setup.openAppSettings(context) }) { Text("App info") }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) item {
            SetupItem("Notifications", notifications, "Shows the connection status while running.") {
                TextButton(onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow") }
            }
        }
        item {
            SetupItem(
                "Unrestricted battery", battery,
                listOfNotNull("Keeps the connection alive while the screen is off.", Setup.vendorHint()).joinToString(" "),
            ) {
                TextButton(onClick = { Setup.requestBatteryExemption(context) }) { Text("Allow") }
                TextButton(onClick = { Setup.openAppSettings(context) }) { Text("App info") }
            }
        }

        item { Text("SIMs", style = MaterialTheme.typography.titleMedium) }
        if (sims.isEmpty()) item { Text("No SIMs visible (grant phone permissions).", style = MaterialTheme.typography.bodySmall) }
        items(sims, key = { it.subId }) { sim ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Slot ${sim.slot + 1} · ${sim.carrier.label}", fontWeight = FontWeight.SemiBold)
                    Text(
                        "${sim.carrierName} · ${sim.number ?: "number unknown"} · sub ${sim.subId}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item { Text(" ") }
    }
}

@Composable
private fun SetupItem(title: String, done: Boolean, detail: String?, actions: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (done) "✓" else "!", style = MaterialTheme.typography.titleLarge,
                color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (!done) Column(horizontalAlignment = Alignment.End) { actions() }
        }
    }
}
