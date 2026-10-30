package io.github.zachelks.proxkeep.core.api.vnc

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class VncAuthTest {

    @Test
    fun onlyTheFirstEightCharactersCount() {
        val challenge = ByteArray(16) { (it * 7).toByte() }

        assertContentEquals(vncAuthResponse(challenge, "12345678"), vncAuthResponse(challenge, "123456789abc"))
    }

    @Test
    fun aShortPasswordIsPaddedWithZeros() {
        val challenge = ByteArray(16) { it.toByte() }

        assertContentEquals(vncAuthResponse(challenge, "pw"), vncAuthResponse(challenge, "pw\u0000\u0000"))
    }

    @Test
    fun theChallengeMustBeSixteenBytes() {
        assertFailsWith<IllegalArgumentException> { vncAuthResponse(ByteArray(8), "pw") }
    }
}
