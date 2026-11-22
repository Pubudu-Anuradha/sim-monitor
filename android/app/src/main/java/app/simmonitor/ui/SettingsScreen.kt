package app.simmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import app.simmonitor.net.Pairing
import app.simmonitor.settings.Prefs

/**
 * Phone name and pairing. Pairing comes from the dashboard's QR code (in-app scanner, no Google
 * services) or from pasting the `simmonitor://pair?...` link. [onPairing] asks for confirmation.
 */
@Composable
fun SettingsScreen(onPairing: (Pairing) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val pairing by Prefs.pairing.collectAsState()
    var name by remember { mutableStateOf(Prefs.phoneName(context)) }
    var link by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        Pairing.parse(text)?.let { error = null; onPairing(it) }
            ?: run { error = "That QR code isn't a SIM Monitor pairing code." }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Phone name", style = MaterialTheme.typography.titleMedium)
                Text("Shown on the dashboard.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, Modifier.weight(1f), singleLine = true)
                    Button(
                        onClick = { Prefs.setPhoneName(context, name) },
                        enabled = name.isNotBlank() && name.trim() != Prefs.phoneName(context),
                    ) { Text("Save") }
                }
                Text("Takes effect on the next connection.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Desktop pairing", style = MaterialTheme.typography.titleMedium)
                val p = pairing
                if (p == null) {
                    Text("Not paired. On the desktop dashboard, click Pair phone and scan its QR code.")
                } else {
                    Text("Server ${p.host}:${p.port}")
                    Text(
                        "Certificate ${p.caSha256.take(16).chunked(4).joinToString(" ")}…",
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scanner.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("Scan the QR code from the dashboard's Pair phone dialog")
                                .setBeepEnabled(false)
                                .setOrientationLocked(false),
                        )
                    }) { Text(if (p == null) "Scan QR code" else "Re-pair") }
                    if (p != null) OutlinedButton(onClick = { Prefs.savePairing(context, null) }) { Text("Unpair") }
                }
                OutlinedTextField(
                    link, { link = it }, Modifier.fillMaxWidth(),
                    label = { Text("…or paste a pairing link") }, singleLine = true,
                )
                Button(onClick = {
                    Pairing.parse(link)?.let { error = null; link = ""; onPairing(it) }
                        ?: run { error = "Not a valid simmonitor://pair link." }
                }, enabled = link.isNotBlank()) { Text("Use link") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
