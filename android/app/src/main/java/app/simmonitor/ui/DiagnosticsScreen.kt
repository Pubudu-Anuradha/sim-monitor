package app.simmonitor.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.simmonitor.probe.Instruction
import app.simmonitor.probe.Presets
import app.simmonitor.probe.ProbeLog
import app.simmonitor.probe.RunResult
import app.simmonitor.probe.Sim
import app.simmonitor.probe.Sims
import app.simmonitor.probe.SmsRunner
import app.simmonitor.probe.UssdRunner
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val PROBE_PERMISSIONS = arrayOf(
    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.READ_PHONE_NUMBERS,
    Manifest.permission.CALL_PHONE,
    Manifest.permission.SEND_SMS,
    Manifest.permission.RECEIVE_SMS,
)

/** The milestone-2 probe: run codes by hand and see raw replies. Kept for diagnosing carriers. */
@Composable
fun DiagnosticsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val missing = remember(refresh) {
        PROBE_PERMISSIONS.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }
    val sims = remember(refresh) { Sims.list(context) }
    val log by ProbeLog.entries.collectAsState()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refresh++ }

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Diagnostics", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = { refresh++ }) { Text("Refresh") }
            }
        }
        if (missing.isNotEmpty()) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Missing permissions: ${missing.joinToString { it.substringAfterLast('.') }}")
                    Text(
                        "If SMS permissions can't be granted, open App info → ⋮ → " +
                            "Allow restricted settings, or install with adb.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { launcher.launch(PROBE_PERMISSIONS) }) { Text("Grant") }
                }
            }
        }
        if (sims.isEmpty()) item { Text("No active SIMs found.") }
        items(sims, key = { it.subId }) { SimCard(it) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Log (${log.size})", style = MaterialTheme.typography.titleMedium)
                Row {
                    TextButton(onClick = { shareLog(context) }, enabled = log.isNotEmpty()) { Text("Share") }
                    TextButton(onClick = { ProbeLog.clear(context) }, enabled = log.isNotEmpty()) { Text("Clear") }
                }
            }
        }
        items(log) { e ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("${TIME.format(e.ts)}  ${e.kind}", style = MaterialTheme.typography.labelMedium)
                Text(e.summary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimCard(sim: Sim) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var ussd by remember { mutableStateOf("") }
    var smsTo by remember { mutableStateOf("") }
    var smsBody by remember { mutableStateOf("") }

    fun run(i: Instruction) {
        busy = true
        scope.launch {
            try {
                execute(context, sim, i)
            } finally {
                busy = false
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Slot ${sim.slot + 1} · ${sim.carrier.label} · sub ${sim.subId}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "${sim.carrierName} / ${sim.displayName} · MCC-MNC ${sim.mcc}-${sim.mnc}" +
                    (sim.carrierId?.let { " · carrierId $it" } ?: "") +
                    " · ${sim.number ?: "number unknown"}",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Presets.forCarrier(sim.carrier).forEach { p ->
                    OutlinedButton(onClick = { run(p) }, enabled = !busy) { Text(p.label) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    ussd, { ussd = it }, Modifier.weight(1f), label = { Text("USSD") }, singleLine = true,
                )
                Button(onClick = { run(Instruction.Ussd(ussd.trim())) }, enabled = !busy && ussd.isNotBlank()) {
                    Text("Run")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    smsTo, { smsTo = it }, Modifier.weight(1f), label = { Text("SMS to") }, singleLine = true,
                )
                OutlinedTextField(
                    smsBody, { smsBody = it }, Modifier.weight(1f), label = { Text("Text") }, singleLine = true,
                )
                Button(
                    onClick = { run(Instruction.Sms(smsTo.trim(), smsBody.trim())) },
                    enabled = !busy && smsTo.isNotBlank() && smsBody.isNotBlank(),
                ) { Text("Send") }
            }
            if (busy) Text("Waiting for reply…", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private suspend fun execute(context: android.content.Context, sim: Sim, i: Instruction) {
    val base = arrayOf(
        "sub_id" to sim.subId, "slot" to sim.slot, "carrier" to sim.carrier.label,
        "mcc_mnc" to "${sim.mcc}-${sim.mnc}",
    )
    when (i) {
        is Instruction.Ussd -> {
            ProbeLog.add(context, "ussd_request", *base, "code" to i.code)
            val r = try {
                UssdRunner.run(context, sim.subId, i.code)
            } catch (e: SecurityException) {
                RunResult.Failed("permission: ${e.message}")
            }
            when (r) {
                is RunResult.Reply -> ProbeLog.add(context, "ussd_reply", *base, "code" to i.code, "reply" to r.text)
                is RunResult.Failed -> ProbeLog.add(context, "ussd_failed", *base, "code" to i.code, "error" to r.reason)
            }
        }
        is Instruction.Sms -> {
            ProbeLog.add(context, "sms_send", *base, "to" to i.to, "body" to i.body)
            val r = try {
                SmsRunner.send(context, sim.subId, i.to, i.body)
            } catch (e: SecurityException) {
                RunResult.Failed("permission: ${e.message}")
            }
            when (r) {
                is RunResult.Reply -> ProbeLog.add(context, "sms_sent", *base, "to" to i.to)
                is RunResult.Failed -> ProbeLog.add(context, "sms_failed", *base, "to" to i.to, "error" to r.reason)
            }
            // The carrier's reply arrives through SmsReceiver as an sms_received entry.
        }
    }
}

private fun shareLog(context: android.content.Context) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", ProbeLog.file(context))
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/json")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "SIM probe log")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share probe log"))
}

private val TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
