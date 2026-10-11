package io.github.zachelks.proxkeep.core.api

import io.github.zachelks.proxkeep.core.api.auth.ApiTokenCredentials
import io.github.zachelks.proxkeep.core.api.auth.SecondFactorKind
import io.github.zachelks.proxkeep.core.api.auth.TfaChallenge
import io.github.zachelks.proxkeep.core.api.auth.TicketCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TfaChallengeTest {

    @Test
    fun ordinaryTicketsCarryNoChallenge() {
        assertNull(TfaChallenge.fromTicket("PVE:root@pam:6712F3A1::c2lnbmF0dXJl"))
    }

    @Test
    fun challengeListsTheAvailableFactors() {
        // {"totp":true,"recovery":[0,1,2],"webauthn":null,"yubico":false}
        val ticket = "PVE:!tfa!%7B%22totp%22%3Atrue%2C%22recovery%22%3A%5B0%2C1%2C2%5D%2C" +
            "%22webauthn%22%3Anull%2C%22yubico%22%3Afalse%7D:6712F3A1::c2lnbmF0dXJl"
        val challenge = assertNotNull(TfaChallenge.fromTicket(ticket))
        assertEquals(ticket, challenge.ticket)
        assertTrue(challenge.totp)
        assertTrue(challenge.recovery)
        assertFalse(challenge.webauthn)
        assertFalse(challenge.yubico)
        assertEquals(listOf(SecondFactorKind.Totp, SecondFactorKind.Recovery), challenge.codeKinds)
    }

    @Test
    fun webauthnChallengeObjectCountsAsOffered() {
        // {"webauthn":{"challenge":"abc"},"recovery":[]}
        val ticket = "PVE:!tfa!%7B%22webauthn%22%3A%7B%22challenge%22%3A%22abc%22%7D%2C" +
            "%22recovery%22%3A%5B%5D%7D:6712F3A1::sig"
        val challenge = assertNotNull(TfaChallenge.fromTicket(ticket))
        assertTrue(challenge.webauthn)
        assertFalse(challenge.recovery)
        assertFalse(challenge.totp)
        assertEquals(emptyList(), challenge.codeKinds)
    }

    @Test
    fun unreadableChallengeStillCountsAsAChallenge() {
        val challenge = assertNotNull(TfaChallenge.fromTicket("PVE:!tfa!not-json:6712F3A1::sig"))
        assertEquals(emptyList(), challenge.codeKinds)
    }

    @Test
    fun secretsStayOutOfToString() {
        val ticket = TicketCredentials("root@pam", "PVE:root@pam:6712F3A1::c2VjcmV0", "csrf-secret")
        assertFalse(ticket.toString().contains("c2VjcmV0"))
        assertFalse(ticket.toString().contains("csrf-secret"))
        val token = ApiTokenCredentials("root@pam!phone", "11111111-2222-3333-4444-555555555555")
        assertFalse(token.toString().contains("11111111"))
        assertEquals("root@pam", token.username)
    }

    @Test
    fun tokenIdsNeedUserRealmAndName() {
        assertTrue(ApiTokenCredentials.isValidTokenId("root@pam!phone"))
        assertFalse(ApiTokenCredentials.isValidTokenId("root@pam"))
        assertFalse(ApiTokenCredentials.isValidTokenId("root!phone"))
        assertFalse(ApiTokenCredentials.isValidTokenId("root@pam!"))
        assertFalse(ApiTokenCredentials.isValidTokenId("@pam!phone"))
    }
}
