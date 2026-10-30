package io.github.zachelks.proxkeep.core.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpidTest {

    @Test
    fun theNodeIsReadFromATaskId() {
        val upid = Upid.parse("UPID:pve2:0003A1B2:01C2D3E4:6712F3A1:qmigrate:100:root@pam:")!!
        assertEquals("pve2", upid.node)
        assertNull(upid.remote)
        assertEquals("UPID:pve2:0003A1B2:01C2D3E4:6712F3A1:qmigrate:100:root@pam:", upid.text)
    }

    @Test
    fun aTaskStartedWithAnApiTokenHasNoRemote() {
        // The token's `!` belongs to the user, not to a Datacenter Manager remote.
        val upid = Upid.parse("UPID:pdm:0003A1B2:01C2D3E4:6712F3A1:remote-add::root@pam!ci:")!!
        assertNull(upid.remote)
        assertEquals("pdm", upid.node)
    }

    @Test
    fun aRemoteTaskNamesItsRemoteAndItsNode() {
        val upid = Upid.parse("prod!UPID:pve1:0003A1B2:01C2D3E4:6712F3A1:qmstart:100:root@pam!pdm:")!!
        assertEquals("prod", upid.remote)
        assertEquals("pve1", upid.node)
    }

    @Test
    fun somethingElseIsNotATask() {
        assertNull(Upid.parse("local-lvm:vm-100-disk-1"), "a volume ID some calls return")
        assertNull(Upid.parse(""))
        assertNull(Upid.parse("UPID:"))
        assertNull(Upid.parse("!UPID:pve1:1:2:3:x::root@pam:"))
    }
}
