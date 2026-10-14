package io.github.zachelks.proxkeep.ui.lifecycle

import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.core.api.model.Privilege
import kotlin.test.Test
import kotlin.test.assertEquals

class LifecycleMenuTest {

    private val running = ClusterResource(id = "qemu/100", type = "qemu", node = "pve1", vmid = 100, status = "running")
    private val everything: (String) -> Boolean = { true }

    @Test
    fun aRunningGuestCannotBeConvertedButCanBeOfferedForDeletion() {
        assertEquals(
            listOf(LifecycleMenuItem.Snapshots, LifecycleMenuItem.Backups, LifecycleMenuItem.Migrate, LifecycleMenuItem.Clone, LifecycleMenuItem.Delete),
            availableLifecycleItems(running, everything, onlineNodes = 2),
        )
    }

    @Test
    fun aStoppedGuestCanBecomeATemplate() {
        val items = availableLifecycleItems(running.copy(status = "stopped"), everything, onlineNodes = 1)

        assertEquals(
            listOf(LifecycleMenuItem.Snapshots, LifecycleMenuItem.Backups, LifecycleMenuItem.Clone, LifecycleMenuItem.ConvertToTemplate, LifecycleMenuItem.Delete),
            items,
            "a single node offers no migration",
        )
    }

    @Test
    fun aTemplateHasNoSnapshotsAndIsNotConvertedAgain() {
        val template = running.copy(status = "stopped", template = true)

        assertEquals(
            listOf(LifecycleMenuItem.Backups, LifecycleMenuItem.Migrate, LifecycleMenuItem.Clone, LifecycleMenuItem.Delete),
            availableLifecycleItems(template, everything, onlineNodes = 3),
        )
    }

    @Test
    fun onlyWhatTheCredentialsAllowIsOffered() {
        val allowed = setOf(Privilege.VM_SNAPSHOT_ROLLBACK, Privilege.VM_CLONE)

        assertEquals(
            listOf(LifecycleMenuItem.Snapshots, LifecycleMenuItem.Clone),
            availableLifecycleItems(running, { it in allowed }, onlineNodes = 3),
        )
    }

    @Test
    fun destructiveItemsAreMarked() {
        assertEquals(
            listOf(LifecycleMenuItem.ConvertToTemplate, LifecycleMenuItem.Delete),
            LifecycleMenuItem.entries.filter { it.isDestructive },
        )
    }
}
