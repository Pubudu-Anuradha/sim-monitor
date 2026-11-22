package app.simmonitor.service

import android.annotation.SuppressLint
import android.content.Context
import app.simmonitor.probe.CarrierSenders
import app.simmonitor.probe.ProbeLog
import app.simmonitor.probe.RunResult
import app.simmonitor.probe.SmsRunner
import app.simmonitor.probe.UssdRunner
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs one desktop instruction at a time (USSD sessions can't overlap) and returns the raw result:
 * `{ok, ussd_reply, sms:[{sender, body, ts}], error}`. See docs/api.md.
 */
class InstructionRunner(private val context: Context) {
    private class Collector(val subId: Int, val senders: Set<String>, val channel: Channel<SmsBus.Sms>)

    private val mutex = Mutex()

    @Volatile
    private var collector: Collector? = null

    /** Offers an incoming carrier SMS to the running instruction. Returns true if it was claimed. */
    fun offer(sms: SmsBus.Sms): Boolean {
        val c = collector ?: return false
        if (sms.subId != c.subId || CarrierSenders.normalize(sms.sender) !in c.senders) return false
        return c.channel.trySend(sms).isSuccess
    }

    @SuppressLint("MissingPermission") // runners catch SecurityException → reported as an error
    suspend fun run(subId: Int, ins: JSONObject): JSONObject = mutex.withLock {
        val method = ins.optString("method")
        val expect = ins.optJSONArray("expect_sms_from").strings().map(CarrierSenders::normalize).toSet()
        val waitS = ins.optInt("sms_wait_s", 0)
        val max = ins.optInt("sms_max", 1).coerceAtLeast(1)
        val channel = Channel<SmsBus.Sms>(Channel.UNLIMITED)
        // Listen before running: the carrier SMS can arrive before the USSD reply does.
        collector = Collector(subId, expect, channel)
        val label = ins.optString("code").ifEmpty { "${ins.optString("body")} → ${ins.optString("to")}" }
        ProbeLog.add(context, "check_run", "sub_id" to subId, "instruction" to ins.toString())
        try {
            var ussdReply: String? = null
            val outcome: RunResult = try {
                when (method) {
                    "ussd" -> UssdRunner.run(context, subId, ins.getString("code")).also {
                        if (it is RunResult.Reply) ussdReply = it.text
                    }
                    "sms" -> SmsRunner.send(context, subId, ins.getString("to"), ins.getString("body"))
                    else -> RunResult.Failed("unknown method '$method'")
                }
            } catch (e: SecurityException) {
                RunResult.Failed("permission missing: ${e.message}")
            } catch (e: Exception) {
                RunResult.Failed("${e::class.simpleName}: ${e.message}")
            }

            val received = mutableListOf<SmsBus.Sms>()
            if (outcome is RunResult.Reply && expect.isNotEmpty() && waitS > 0) {
                withTimeoutOrNull(waitS * 1000L) {
                    while (received.size < max) received += channel.receive()
                }
            }
            while (true) received += channel.tryReceive().getOrNull() ?: break

            val result = JSONObject()
                .put("ok", outcome is RunResult.Reply)
                .put("ussd_reply", ussdReply ?: JSONObject.NULL)
                .put("sms", JSONArray(received.map {
                    JSONObject().put("sender", it.sender).put("body", it.body).put("ts", it.ts.toString())
                }))
                .put("error", (outcome as? RunResult.Failed)?.reason ?: JSONObject.NULL)
            ProbeLog.add(context, "check_result", "sub_id" to subId, "label" to label, "result" to result.toString())
            result
        } finally {
            collector = null
            channel.close()
        }
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }
}
