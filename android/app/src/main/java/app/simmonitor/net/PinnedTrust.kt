package app.simmonitor.net

import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Trusts exactly one CA: the desktop's private CA, identified by the SHA-256 of its certificate
 * (from pairing). The server sends that CA in its chain; we check its fingerprint, then run normal
 * chain validation with it as the only anchor. The system CA store is never consulted.
 */
// Not a "trust everything" manager: it pins the CA, then delegates to the platform's own validation.
@android.annotation.SuppressLint("CustomX509TrustManager")
class PinnedTrust(private val caSha256: String) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val ca = chain.firstOrNull { sha256(it.encoded) == caSha256 }
            ?: throw CertificateException("Server certificate is not from the paired desktop (re-pair?)")
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("ca", ca)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        val delegate = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        delegate.checkServerTrusted(chain, authType)
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        throw CertificateException("client certificates not used")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    fun socketFactory(): SSLSocketFactory =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(this@PinnedTrust), null) }.socketFactory

    companion object {
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
