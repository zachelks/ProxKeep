package io.github.zachelks.proxkeep.core.api.tls

import java.security.cert.CertificateParsingException
import java.security.cert.X509Certificate

/** The parts of a server certificate shown to a person deciding whether to trust it. */
data class CertificateInfo(
    val sha256Fingerprint: String,
    val subject: String,
    val issuer: String,
    /** Start of validity, in milliseconds since the epoch. */
    val notBefore: Long,
    /** End of validity, in milliseconds since the epoch. */
    val notAfter: Long,
    /** DNS names and IP addresses the certificate was issued for. */
    val subjectAltNames: List<String>,
) {
    val isSelfSigned: Boolean
        get() = subject == issuer

    companion object {
        private const val ALT_NAME_DNS = 2
        private const val ALT_NAME_IP = 7

        fun from(certificate: X509Certificate): CertificateInfo = CertificateInfo(
            sha256Fingerprint = Fingerprints.sha256(certificate),
            subject = certificate.subjectX500Principal.name,
            issuer = certificate.issuerX500Principal.name,
            notBefore = certificate.notBefore.time,
            notAfter = certificate.notAfter.time,
            subjectAltNames = altNames(certificate),
        )

        private fun altNames(certificate: X509Certificate): List<String> {
            val entries = try {
                certificate.subjectAlternativeNames
            } catch (ignored: CertificateParsingException) {
                null
            } ?: return emptyList()
            return entries.mapNotNull { entry ->
                val type = entry.getOrNull(0) as? Int
                val value = entry.getOrNull(1) as? String
                if (value != null && (type == ALT_NAME_DNS || type == ALT_NAME_IP)) value else null
            }
        }
    }
}
