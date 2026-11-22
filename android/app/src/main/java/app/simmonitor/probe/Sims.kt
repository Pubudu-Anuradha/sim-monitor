package app.simmonitor.probe

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager

/** A SIM as the phone sees it. Android hides the ICCID from normal apps, so subId is the key. */
data class Sim(
    val subId: Int,
    val slot: Int,
    val carrierName: String,
    val displayName: String,
    val mcc: String?,
    val mnc: String?,
    val carrierId: Int?,
    val number: String?,
) {
    val carrier: Carrier get() = Carrier.detect(mcc, mnc, carrierName)
}

/** Sri Lankan networks by MCC 413 / MNC, with name matching as a fallback. */
enum class Carrier(val label: String) {
    DIALOG("Dialog"),
    MOBITEL("SLT-Mobitel"),
    HUTCH("Hutch"),
    UNKNOWN("Unknown");

    companion object {
        fun detect(mcc: String?, mnc: String?, name: String): Carrier {
            if (mcc == "413") {
                when (mnc?.trimStart('0')) {
                    "1" -> return MOBITEL
                    "2", "5" -> return DIALOG // 05 = Airtel, merged into Dialog
                    "3", "8" -> return HUTCH // 03 = Etisalat, merged into Hutch
                }
            }
            val n = name.lowercase()
            return when {
                "dialog" in n -> DIALOG
                "mobitel" in n || "slt" in n -> MOBITEL
                "hutch" in n || "etisalat" in n -> HUTCH
                else -> UNKNOWN
            }
        }
    }
}

object Sims {
    @SuppressLint("MissingPermission")
    fun list(context: Context): List<Sim> {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) return emptyList()
        val sm = context.getSystemService(SubscriptionManager::class.java)
        val infos = sm.activeSubscriptionInfoList ?: return emptyList()
        return infos.sortedBy { it.simSlotIndex }.map { it.toSim(context, sm) }
    }

    @Suppress("DEPRECATION")
    private fun SubscriptionInfo.toSim(context: Context, sm: SubscriptionManager): Sim {
        val mccS: String?
        val mncS: String?
        if (Build.VERSION.SDK_INT >= 29) {
            mccS = mccString; mncS = mncString
        } else {
            mccS = mcc.toString(); mncS = mnc.toString().padStart(2, '0')
        }
        return Sim(
            subId = subscriptionId,
            slot = simSlotIndex,
            carrierName = carrierName?.toString().orEmpty(),
            displayName = displayName?.toString().orEmpty(),
            mcc = mccS,
            mnc = mncS,
            carrierId = if (Build.VERSION.SDK_INT >= 29) carrierId else null,
            number = number(context, sm, this),
        )
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun number(context: Context, sm: SubscriptionManager, info: SubscriptionInfo): String? {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_PHONE_NUMBERS
        else Manifest.permission.READ_PHONE_STATE
        if (context.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) return null
        val n = runCatching {
            if (Build.VERSION.SDK_INT >= 33) sm.getPhoneNumber(info.subscriptionId) else info.number
        }.getOrNull()
        return n?.takeIf { it.isNotBlank() }
    }
}
