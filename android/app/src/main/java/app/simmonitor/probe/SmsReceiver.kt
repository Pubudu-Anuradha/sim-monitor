package app.simmonitor.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import app.simmonitor.service.SmsBus
import app.simmonitor.settings.Prefs
import java.time.Instant

/**
 * Incoming SMS (multi-part messages joined). Carrier senders go to the diagnostics log and to the
 * monitor service; anything else is logged as sender-only and never leaves the phone.
 * The allowlist comes from the desktop once connected, with [CarrierSenders] as the fallback.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val subId = intent.getIntExtra(
            "android.telephony.extra.SUBSCRIPTION_INDEX", // SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX (API 30)
            intent.getIntExtra("subscription", -1), // older OEM/AOSP extra
        )
        val allowed = Prefs.allowedSenders(context)
        parts.groupBy { it.displayOriginatingAddress }.forEach { (sender, msgs) ->
            val ok = if (allowed != null) {
                sender != null && CarrierSenders.normalize(sender) in allowed
            } else {
                CarrierSenders.isAllowed(sender)
            }
            if (!ok) {
                ProbeLog.add(context, "sms_ignored", "sub_id" to subId, "sender" to sender)
                return@forEach
            }
            val body = msgs.joinToString("") { it.displayMessageBody }
            ProbeLog.add(context, "sms_received", "sub_id" to subId, "sender" to sender, "body" to body)
            SmsBus.post(SmsBus.Sms(subId, sender, body, Instant.now()))
        }
    }
}
