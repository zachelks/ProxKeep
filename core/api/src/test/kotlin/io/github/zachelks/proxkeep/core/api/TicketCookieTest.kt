package io.github.zachelks.proxkeep.core.api

import io.github.zachelks.proxkeep.core.api.auth.TicketCookie
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TicketCookieTest {

    @Test
    fun theTicketIsReadFromACookieHeader() {
        assertEquals("PVE:root@pam:1::c2ln+/w=", TicketCookie.fromCookieHeader("lang=en; PVEAuthCookie=PVE%3Aroot%40pam%3A1%3A%3Ac2ln+%2Fw%3D", "PVEAuthCookie"))
        assertEquals("PBS:root@pam:1::x", TicketCookie.fromCookieHeader("__Host-PBSAuthCookie=PBS%3Aroot%40pam%3A1%3A%3Ax", "PBSAuthCookie"))
        assertNull(TicketCookie.fromCookieHeader("PVEAuthCookie=PVE%3Aroot%40pam%3A1%3A%3Ax", "PBSAuthCookie"), "another product's cookie")
        assertNull(TicketCookie.fromCookieHeader("PVEAuthCookie=", "PVEAuthCookie"))
        assertNull(TicketCookie.fromCookieHeader(null, "PVEAuthCookie"))
    }

    @Test
    fun theTicketIsReadFromSetCookieHeaders() {
        val headers = listOf(
            "PVEAuthCookie=; Path=/; Max-Age=0",
            "__Host-PVEAuthCookie=PVE%3Aroot%40pam%3A1%3A%3Ax; Path=/; Secure; HttpOnly",
        )
        assertEquals("PVE:root@pam:1::x", TicketCookie.fromSetCookies(headers, "PVEAuthCookie"))
        assertNull(TicketCookie.fromSetCookies(listOf("other=1; Path=/"), "PVEAuthCookie"))
    }

    @Test
    fun aValueThatCannotBeDecodedIsKeptAsSent() {
        assertEquals("%zz", TicketCookie.fromCookieHeader("PVEAuthCookie=%zz", "PVEAuthCookie"))
    }
}
