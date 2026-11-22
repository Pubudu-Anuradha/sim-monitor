package app.simmonitor.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the monitor service is doing, for the notification and the status screen. */
object MonitorState {
    enum class Kind { STOPPED, NOT_PAIRED, WAITING_WIFI, CONNECTING, CONNECTED, RETRYING, BAD_PAIRING }

    data class State(val kind: Kind, val detail: String? = null, val busy: String? = null) {
        val title: String
            get() = when (kind) {
                Kind.STOPPED -> "Stopped"
                Kind.NOT_PAIRED -> "Not paired"
                Kind.WAITING_WIFI -> "Waiting for Wi-Fi"
                Kind.CONNECTING -> "Connecting…"
                Kind.CONNECTED -> if (busy != null) "Checking $busy…" else "Connected to desktop"
                Kind.RETRYING -> "Can't reach desktop"
                Kind.BAD_PAIRING -> "Pairing rejected"
            }
    }

    private val _state = MutableStateFlow(State(Kind.STOPPED))
    val state: StateFlow<State> = _state.asStateFlow()

    fun set(kind: Kind, detail: String? = null) {
        _state.value = State(kind, detail, if (kind == Kind.CONNECTED) _state.value.busy else null)
    }

    fun setBusy(label: String?) {
        _state.value = _state.value.copy(busy = label)
    }
}
