package app.simmonitor.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingTest {
    private val pin = "a".repeat(64)
    private val token = "Zx_9-abcdefghijklmnopqrstuvwxyz0123456789"

    @Test fun parsesDashboardUri() {
        // Same encoding as the server's urlencode().
        val p = Pairing.parse("simmonitor://pair?host=192.0.2.10&port=8443&token=$token&ca_sha256=$pin")
        assertEquals(Pairing("192.0.2.10", 8443, token, pin), p)
        assertEquals("wss://192.0.2.10:8443/ws", p!!.url)
    }

    @Test fun decodesAndNormalizes() {
        val p = Pairing.parse(" simmonitor://pair?host=desk&port=1&token=abc%2Bdefghijklmnopq&ca_sha256=${"AB".repeat(32)} ")
        assertEquals("abc+defghijklmnopq", p!!.token)
        assertEquals("ab".repeat(32), p.caSha256)
    }

    @Test fun rejectsBadInput() {
        assertNull(Pairing.parse("https://pair?host=x&port=1&token=$token&ca_sha256=$pin"))
        assertNull(Pairing.parse("simmonitor://other?host=x&port=1&token=$token&ca_sha256=$pin"))
        assertNull(Pairing.parse("simmonitor://pair?host=x&port=99999&token=$token&ca_sha256=$pin"))
        assertNull(Pairing.parse("simmonitor://pair?host=x&port=1&token=short&ca_sha256=$pin"))
        assertNull(Pairing.parse("simmonitor://pair?host=x&port=1&token=$token&ca_sha256=xyz"))
        assertNull(Pairing.parse("simmonitor://pair"))
        assertNull(Pairing.parse("not a uri at all"))
    }
}
