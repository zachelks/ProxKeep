package io.github.zachelks.proxkeep.core.api.tls

import java.security.MessageDigest
import java.security.cert.X509Certificate

/** SHA-256 certificate fingerprints in the `AA:BB:…` form the Proxmox web interface shows. */
object Fingerprints {
    private const val HEX_LENGTH = 64

    fun sha256(certificate: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return digest.joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
    }

    /**
     * Brings a fingerprint a person typed or pasted into the canonical form.
     * Returns null if [text] is not a SHA-256 fingerprint.
     */
    fun normalize(text: String): String? {
        val hex = text.filterNot { it == ':' || it.isWhitespace() }.uppercase()
        if (hex.length != HEX_LENGTH || hex.any { it !in '0'..'9' && it !in 'A'..'F' }) return null
        return hex.chunked(2).joinToString(":")
    }
}
