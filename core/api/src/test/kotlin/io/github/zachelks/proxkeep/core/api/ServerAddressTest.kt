package io.github.zachelks.proxkeep.core.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerAddressTest {

    @Test
    fun bareHostGetsTheDefaultPort() {
        assertEquals(ServerAddress("192.168.1.225", 8006), ServerAddress.parse("192.168.1.225"))
        assertEquals(ServerAddress("pve.example.com", 8006), ServerAddress.parse("  PVE.Example.com "))
    }

    @Test
    fun explicitPortIsKept() {
        assertEquals(ServerAddress("pve.example.com", 443), ServerAddress.parse("pve.example.com:443"))
        assertEquals(ServerAddress("10.0.0.5", 8443), ServerAddress.parse("10.0.0.5:8443"))
    }

    @Test
    fun pastedUrlsAreAccepted() {
        assertEquals(ServerAddress("192.168.1.225", 8006), ServerAddress.parse("https://192.168.1.225:8006/#v1:0:18:4"))
        assertEquals(ServerAddress("pve.example.com", 8006), ServerAddress.parse("https://pve.example.com/"))
    }

    @Test
    fun ipv6LiteralsAreAccepted() {
        assertEquals(ServerAddress("fd00::10", 8006), ServerAddress.parse("[fd00::10]"))
        assertEquals(ServerAddress("fd00::10", 9000), ServerAddress.parse("[fd00::10]:9000"))
        assertEquals("[fd00::10]:9000", ServerAddress("fd00::10", 9000).display)
    }

    @Test
    fun unusableInputIsRejected() {
        assertNull(ServerAddress.parse(""))
        assertNull(ServerAddress.parse("   "))
        assertNull(ServerAddress.parse("host name"))
        assertNull(ServerAddress.parse("root@pve.example.com"))
        assertNull(ServerAddress.parse("pve.example.com:99999"))
    }

    @Test
    fun apiBaseUrlPointsAtTheJsonApi() {
        assertEquals("https://192.168.1.225:8006/api2/json/", ServerAddress("192.168.1.225").apiBaseUrl.toString())
        assertEquals("192.168.1.225:8006", ServerAddress("192.168.1.225").display)
    }
}
