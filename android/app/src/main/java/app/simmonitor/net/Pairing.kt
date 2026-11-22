package app.simmonitor.net

import java.net.URI
import java.net.URLDecoder

/** What the dashboard's QR code carries: `simmonitor://pair?host=…&port=…&token=…&ca_sha256=…`. */
data class Pairing(val host: String, val port: Int, val token: String, val caSha256: String) {
    val url: String get() = "wss://$host:$port/ws"

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        /** Parses a pairing URI; returns null for anything malformed. Pure JVM, unit-tested. */
        fun parse(uri: String): Pairing? {
            val u = runCatching { URI(uri.trim()) }.getOrNull() ?: return null
            if (u.scheme != "simmonitor" || u.host != "pair") return null
            val q = (u.rawQuery ?: return null).split("&").mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }.toMap()
            val host = q["host"]?.takeIf { it.isNotBlank() } ?: return null
            val port = q["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val token = q["token"]?.takeIf { it.length >= 16 } ?: return null
            val pin = q["ca_sha256"]?.lowercase()?.takeIf { HEX64.matches(it) } ?: return null
            return Pairing(host, port, token, pin)
        }
    }
}
