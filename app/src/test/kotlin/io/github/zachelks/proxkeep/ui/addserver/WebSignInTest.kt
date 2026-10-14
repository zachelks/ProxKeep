package io.github.zachelks.proxkeep.ui.addserver

import android.net.http.SslCertificate
import io.github.zachelks.proxkeep.core.api.tls.Fingerprints
import okhttp3.tls.HeldCertificate
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class WebSignInTest {

    private val pinned = HeldCertificate.Builder().commonName("pve.lan").build().certificate
    private val other = HeldCertificate.Builder().commonName("pve.lan").build().certificate

    @Test
    fun onlyThePinnedCertificateIsAccepted() {
        val pin = Fingerprints.sha256(pinned)

        assertTrue(matchesPin(SslCertificate(pinned), pin))
        assertTrue(matchesPin(SslCertificate(pinned), pin.replace(":", "").lowercase()), "any spelling of the pin")
        assertFalse(matchesPin(SslCertificate(other), pin))
        assertFalse(matchesPin(SslCertificate(pinned), null), "nothing is accepted without a pin")
        assertFalse(matchesPin(null, pin))
    }

    @Test
    fun theTicketIsTakenFromTheCookieOnceThePageHasSignedIn() {
        assertEquals(
            "PVE:alice@oidc:6712F3A1::c2ln",
            ticketFromCookies("lang=en; PVEAuthCookie=PVE%3Aalice%40oidc%3A6712F3A1%3A%3Ac2ln; other=1", "PVEAuthCookie"),
        )
        assertEquals(
            "PBS:backup@pbs:6712F3A1::c2ln",
            ticketFromCookies("PVEAuthCookie=PVE%3Aother%40pam%3A1%3A%3Ax; PBSAuthCookie=PBS%3Abackup%40pbs%3A6712F3A1%3A%3Ac2ln", "PBSAuthCookie"),
            "a Backup Server's page keeps its ticket in its own cookie",
        )
        assertEquals(
            "PDM:admin@pdm:6712F3A1::c2ln",
            ticketFromCookies("__Host-PDMAuthCookie=PDM%3Aadmin%40pdm%3A6712F3A1%3A%3Ac2ln", "PDMAuthCookie"),
            "newer servers name the cookie with the __Host- prefix",
        )
        assertNull(ticketFromCookies(null, "PVEAuthCookie"))
        assertNull(ticketFromCookies("lang=en", "PVEAuthCookie"))
        assertNull(ticketFromCookies("PVEAuthCookie=PVE%3A!tfa!%7B%7D%3A6712F3A1%3A%3Ac2ln", "PVEAuthCookie"), "a challenge is not a session")
        assertNull(ticketFromCookies("PVEAuthCookie=%zz", "PVEAuthCookie"), "a damaged cookie is not a session, and does not crash")
    }
}
