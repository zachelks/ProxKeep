package io.github.zachelks.proxkeep.core.api

import io.github.zachelks.proxkeep.core.api.auth.TicketCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CredentialsTest {

    @Test
    fun theUserIsReadFromATicket() {
        assertEquals("root@pam", TicketCredentials.usernameFromTicket("PVE:root@pam:6712F3A1::c2lnbmF0dXJl"))
        assertEquals("alice@oidc", TicketCredentials.usernameFromTicket("PVE:alice@oidc:6712F3A1::c2ln"))
    }

    @Test
    fun aChallengeOrSomethingElseIsNotATicket() {
        assertNull(TicketCredentials.usernameFromTicket("PVE:!tfa!%7B%22totp%22%3Atrue%7D:6712F3A1::Y2hh"))
        assertNull(TicketCredentials.usernameFromTicket("PMG:root@pmg:6712F3A1::c2ln"))
        assertNull(TicketCredentials.usernameFromTicket("garbage"))
    }
}
