package io.github.zachelks.proxkeep.core.api.vnc

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Answers the VNC authentication challenge: the 16 challenge bytes,
 * encrypted with DES under the password.
 *
 * The scheme only uses the first 8 bytes of the password, and it reverses
 * the bit order of each key byte, a quirk of the original implementation
 * that every server expects.
 */
internal fun vncAuthResponse(challenge: ByteArray, password: String): ByteArray {
    require(challenge.size == CHALLENGE_SIZE) { "The challenge must be $CHALLENGE_SIZE bytes" }
    val key = ByteArray(KEY_SIZE)
    val bytes = password.toByteArray(Charsets.ISO_8859_1)
    for (i in 0 until minOf(KEY_SIZE, bytes.size)) key[i] = reverseBits(bytes[i])
    val cipher = Cipher.getInstance("DES/ECB/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
    return cipher.doFinal(challenge)
}

private fun reverseBits(value: Byte): Byte {
    var input = value.toInt() and 0xFF
    var output = 0
    repeat(8) {
        output = (output shl 1) or (input and 1)
        input = input shr 1
    }
    return output.toByte()
}

internal const val CHALLENGE_SIZE = 16
private const val KEY_SIZE = 8
