package app.simmonitor.service

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.time.Instant

/** Carrier SMS (allowlisted senders only) from [app.simmonitor.probe.SmsReceiver] to the service. */
object SmsBus {
    data class Sms(val subId: Int, val sender: String, val body: String, val ts: Instant)

    private val _flow = MutableSharedFlow<Sms>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val flow: SharedFlow<Sms> = _flow

    fun post(sms: Sms) {
        _flow.tryEmit(sms)
    }
}
