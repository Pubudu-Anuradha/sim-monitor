package app.simmonitor.probe

/**
 * Candidate check codes to validate on real SIMs (milestone 2). None are verified yet.
 * After validation, the working ones move to the server, which owns carrier knowledge.
 */
sealed interface Instruction {
    val label: String

    data class Ussd(val code: String, override val label: String = code) : Instruction
    data class Sms(val to: String, val body: String, override val label: String = "SMS $body → $to") :
        Instruction
}

/**
 * Senders whose SMS text may be logged. Anything else (banks, OTPs, personal texts) is logged as
 * sender-only so new carrier senders can be spotted without capturing private content.
 * Compared case-insensitively with spaces, dashes and underscores removed.
 */
object CarrierSenders {
    private val ALLOWED = setOf(
        // Dialog
        "dialog", "678", "5555", "456",
        // SLT-Mobitel
        "mobitel", "sltmobitel", "slt", "160", "7678", "100",
        // Hutch
        "hutch", "344", "444",
    )

    fun normalize(sender: String) = sender.lowercase().filter { it.isLetterOrDigit() }

    fun isAllowed(sender: String?) = sender != null && normalize(sender) in ALLOWED
}

object Presets {
    fun forCarrier(carrier: Carrier): List<Instruction> = when (carrier) {
        Carrier.DIALOG -> listOf(
            Instruction.Sms("678", "MYUSE", "SMS MYUSE → 678 (data)"),
            Instruction.Sms("678", "MBB", "SMS MBB → 678 (postpaid data)"),
            Instruction.Ussd("#456#", "#456# balance"),
            Instruction.Ussd("#678#", "#678# data (menu, expect fail)"),
        )
        Carrier.MOBITEL -> listOf(
            Instruction.Ussd("*100#", "*100# balance"),
            Instruction.Sms("160", "DB"),
            Instruction.Sms("7678", "DB"),
        )
        Carrier.HUTCH -> listOf(
            Instruction.Sms("344", "BAL"),
            Instruction.Ussd("*344#", "*344# prepaid"),
            Instruction.Sms("444", "BAL", "SMS BAL → 444 (postpaid)"),
            Instruction.Ussd("*444#", "*444# postpaid"),
        )
        Carrier.UNKNOWN -> emptyList()
    }
}
