package io.github.zachelks.proxkeep.core.api

import io.github.zachelks.proxkeep.core.api.json.PveJson
import io.github.zachelks.proxkeep.core.api.model.Permissions
import io.github.zachelks.proxkeep.core.api.model.Privilege
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermissionsTest {

    private fun permissions(json: String): Permissions =
        Permissions.fromJson(PveJson.parseToJsonElement(json) as JsonObject)

    @Test
    fun propagatingPrivilegesApplyBelowTheirPath() {
        val permissions = permissions("""{"/":{"VM.PowerMgmt":1,"Sys.Audit":0}}""")

        assertTrue(permissions.has("/vms/100", Privilege.VM_POWER_MGMT))
        assertTrue(permissions.has("/", "Sys.Audit"))
        assertFalse(permissions.has("/nodes/pve1", "Sys.Audit"))
    }

    @Test
    fun theMostSpecificListedPathDecides() {
        val permissions = permissions(
            """{"/":{"VM.PowerMgmt":1,"VM.Console":1},"/vms/100":{"VM.Console":1}}""",
        )

        assertFalse(permissions.has("/vms/100", Privilege.VM_POWER_MGMT))
        assertTrue(permissions.has("/vms/100", Privilege.VM_CONSOLE))
        assertTrue(permissions.has("/vms/101", Privilege.VM_POWER_MGMT))
    }

    @Test
    fun nothingListedMeansNothingGranted() {
        val permissions = permissions("""{"/vms/100":{"VM.Console":1}}""")

        assertFalse(permissions.has("/vms/101", Privilege.VM_CONSOLE))
        assertFalse(permissions.has("/", Privilege.VM_CONSOLE))
        assertFalse(permissions.has("/nodes/pve1", Privilege.SYS_CONSOLE))
    }

    @Test
    fun aGuestIsCoveredByItsPool() {
        val permissions = permissions("""{"/pool/lab":{"VM.PowerMgmt":1}}""")

        assertTrue(permissions.hasAny(Permissions.guestPaths(100, "lab"), Privilege.VM_POWER_MGMT))
        assertFalse(permissions.hasAny(Permissions.guestPaths(100, null), Privilege.VM_POWER_MGMT))
        assertEquals(listOf("/vms/100"), Permissions.guestPaths(100, ""))
    }

    @Test
    fun pathsAreComparedWithoutTrailingSlashes() {
        val permissions = permissions("""{"/nodes/pve1/":{"Sys.Console":"1"}}""")

        assertTrue(permissions.has(Permissions.nodePath("pve1"), Privilege.SYS_CONSOLE))
        assertTrue(permissions.has("nodes/pve1/", Privilege.SYS_CONSOLE))
    }
}
