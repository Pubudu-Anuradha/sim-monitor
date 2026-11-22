package app.simmonitor.net

import android.net.Network
import app.simmonitor.service.MonitorState
import app.simmonitor.service.MonitorState.Kind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Keeps one WebSocket open to the desktop while Wi-Fi is up, reconnecting with backoff.
 * All traffic is bound to the Wi-Fi [Network], so it never goes out over mobile data. A
 * successful TLS handshake against the pinned CA is also what proves we're on the home network.
 */
class DesktopConnection(
    private val scope: CoroutineScope,
    private val onOpen: (WebSocket) -> Unit,
    private val onText: (String) -> Unit,
) {
    private var job: Job? = null

    @Volatile
    private var socket: WebSocket? = null

    /** (Re)starts the connection loop on [network], or stops it when null. */
    fun bind(network: Network?, pairing: Pairing?) {
        job?.cancel()
        socket?.close(1000, "network change")
        socket = null
        when {
            pairing == null -> MonitorState.set(Kind.NOT_PAIRED)
            network == null -> MonitorState.set(Kind.WAITING_WIFI)
            else -> job = scope.launch { loop(network, pairing) }
        }
    }

    fun stop() = bind(null, null).also { MonitorState.set(Kind.STOPPED) }

    fun send(text: String): Boolean = socket?.send(text) ?: false

    val connected: Boolean get() = socket != null

    private suspend fun loop(network: Network, pairing: Pairing) {
        val trust = PinnedTrust(pairing.caSha256)
        val client = OkHttpClient.Builder()
            .socketFactory(network.socketFactory)
            .sslSocketFactory(trust.socketFactory(), trust)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // the socket idles between checks
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(pairing.url)
            .header("Authorization", "Bearer ${pairing.token}")
            .build()
        var backoffMs = 1_000L
        try {
            while (scope.isActive) {
                MonitorState.set(Kind.CONNECTING)
                val closed = CompletableDeferred<Pair<Int, String>>()
                val ws = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        socket = webSocket
                        backoffMs = 1_000L
                        MonitorState.set(Kind.CONNECTED, pairing.url)
                        onOpen(webSocket)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) = onText(text)

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, null)
                        closed.complete(code to reason)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        closed.complete(code to reason)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        // The server rejects a bad token before accepting; uvicorn turns that
                        // into an HTTP 403 handshake response rather than close code 4401.
                        val code = if (response?.code == 401 || response?.code == 403) 4401 else -1
                        closed.complete(code to (t.message ?: t::class.simpleName ?: "error"))
                    }
                })
                val (code, reason) = try {
                    closed.await()
                } finally {
                    socket = null
                    ws.cancel()
                }
                if (code == 4401) { // bad token: retrying won't help
                    MonitorState.set(Kind.BAD_PAIRING, "The desktop rejected this phone's token. Pair again.")
                    return
                }
                if (code == 4409) backoffMs = 1_000L // replaced by our own newer socket
                MonitorState.set(Kind.RETRYING, describe(reason))
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun describe(reason: String): String = when {
        "paired desktop" in reason -> "Certificate doesn't match the paired desktop. Pair again."
        "ECONNREFUSED" in reason || "Failed to connect" in reason ->
            "Desktop not answering. Is the server running? ($reason)"
        "timeout" in reason.lowercase() -> "Timed out. Is this phone on the home network?"
        else -> reason
    }
}
