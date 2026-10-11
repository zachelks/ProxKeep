package io.github.zachelks.proxkeep.core.api.tls

import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Decides which server certificates a connection accepts.
 *
 * A certificate is accepted in exactly two cases:
 *
 * 1. The platform trusts it, the same way a browser would, and it matches the
 *    host name.
 * 2. Its SHA-256 fingerprint equals [pinnedSha256], the fingerprint the person
 *    confirmed when adding the server. This is how self-signed certificates,
 *    the Proxmox default, are supported. The host name is not checked in this
 *    case because the fingerprint identifies one exact certificate.
 *
 * Validation is never switched off. Without a matching pin, an untrusted
 * certificate fails the connection.
 *
 * @param pinnedSha256 a fingerprint in [Fingerprints] form, or null for none
 * @param platformTrust the trust manager used for case 1
 */
class ServerTrust(
    pinnedSha256: String? = null,
    platformTrust: X509TrustManager = platformTrustManager(),
) {
    private val pin: String? = pinnedSha256?.let { Fingerprints.normalize(it) }

    val trustManager: X509TrustManager = PinningTrustManager(platformTrust)

    val socketFactory: SSLSocketFactory = SSLContext.getInstance("TLS")
        .apply { init(null, arrayOf(trustManager), null) }
        .socketFactory

    /** The certificate the server presented on the most recent handshake, trusted or not. */
    @Volatile
    var lastServerCertificate: X509Certificate? = null
        private set

    /** Returns a copy of [client] that applies this trust decision. */
    fun applyTo(client: OkHttpClient): OkHttpClient = client.newBuilder()
        .sslSocketFactory(socketFactory, trustManager)
        .hostnameVerifier(PinningHostnameVerifier(client.hostnameVerifier))
        .build()

    private fun matchesPin(certificate: X509Certificate): Boolean =
        pin != null && Fingerprints.sha256(certificate) == pin

    private inner class PinningTrustManager(
        private val delegate: X509TrustManager,
    ) : X509TrustManager {

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull()
                ?: throw CertificateException("The server sent no certificate")
            lastServerCertificate = leaf
            if (matchesPin(leaf)) return
            delegate.checkServerTrusted(chain, authType)
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            delegate.checkClientTrusted(chain, authType)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
    }

    private inner class PinningHostnameVerifier(
        private val delegate: HostnameVerifier,
    ) : HostnameVerifier {

        override fun verify(hostname: String?, session: SSLSession?): Boolean {
            if (session == null) return false
            val leaf = try {
                session.peerCertificates.firstOrNull() as? X509Certificate
            } catch (ignored: SSLPeerUnverifiedException) {
                null
            }
            if (leaf != null && matchesPin(leaf)) return true
            return delegate.verify(hostname, session)
        }
    }

    companion object {
        /** The trust manager the platform uses by default. */
        fun platformTrustManager(): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }
}
