package io.github.zachelks.proxkeep.ui.storage

import io.github.zachelks.proxkeep.core.api.model.NodeStorage
import io.github.zachelks.proxkeep.core.api.model.StorageVolume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageViewModelTest {

    @Test
    fun volumesAreNamedWithoutTheirStorageOrFolder() {
        assertEquals("debian-13.iso", StorageViewModel.displayName("local:iso/debian-13.iso"))
        assertEquals("vzdump-qemu-100-2026_10_14-03_00_02.vma.zst", StorageViewModel.displayName("local:backup/vzdump-qemu-100-2026_10_14-03_00_02.vma.zst"))
        assertEquals("vm-100-disk-0", StorageViewModel.displayName("local-lvm:vm-100-disk-0"))
        assertEquals("100/vm-100-disk-0.qcow2", StorageViewModel.displayName("local:100/vm-100-disk-0.qcow2"))
    }

    @Test
    fun fileNamesMustFitTheirContent() {
        assertTrue(StorageViewModel.isAllowedFileName("debian.iso", "iso"))
        assertFalse(StorageViewModel.isAllowedFileName("debian.zip", "iso"))
        assertTrue(StorageViewModel.isAllowedFileName("alpine-3.22.tar.xz", "vztmpl"))
        assertTrue(StorageViewModel.isAllowedFileName("appliance.ova", "import"))
        assertFalse(StorageViewModel.isAllowedFileName("../etc.iso", "iso"))
        assertFalse(StorageViewModel.isAllowedFileName(".hidden.iso", "iso"))
    }

    @Test
    fun contentTypesFollowAFixedOrder() {
        val overview = StorageOverview(
            NodeStorage("local", content = "backup,iso,vztmpl"),
            listOf(StorageVolume("local:snippets/a.yaml", content = "snippets")),
        )
        assertEquals(listOf("iso", "vztmpl", "backup", "snippets"), overview.contentTypes)
    }
}
