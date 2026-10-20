package io.github.zachelks.proxkeep.ui.search

import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import kotlin.test.Test
import kotlin.test.assertEquals

class ResourceSearchTest {

    private val web = ClusterResource(id = "qemu/100", type = "qemu", node = "pve1", vmid = 100, name = "web", tags = "prod;nginx")
    private val webdev = ClusterResource(id = "qemu/101", type = "qemu", node = "pve2", vmid = 101, name = "webdev", pool = "dev")
    private val dns = ClusterResource(id = "lxc/200", type = "lxc", node = "pve1", vmid = 200, name = "dns")
    private val pve1 = ClusterResource(id = "node/pve1", type = "node", node = "pve1")
    private val pve2 = ClusterResource(id = "node/pve2", type = "node", node = "pve2")
    private val local = ClusterResource(id = "storage/pve1/local", type = "storage", node = "pve1", storage = "local", plugintype = "dir")
    private val pool = ClusterResource(id = "pool/dev", type = "pool")
    private val all = listOf(local, pve2, pve1, dns, webdev, web, pool)

    @Test
    fun anEmptyQueryFindsNothing() {
        assertEquals(emptyList(), searchResources(all, "  "))
    }

    @Test
    fun anExactNameComesFirst() {
        assertEquals(listOf(web, webdev), searchResources(all, "web"))
    }

    @Test
    fun guestIdsAreSearched() {
        assertEquals(listOf(web, webdev), searchResources(all, "10"))
        assertEquals(listOf(dns), searchResources(all, "200"))
    }

    @Test
    fun aNodeNameFindsTheNodeBeforeWhatRunsOnIt() {
        assertEquals(listOf(pve1, dns, web, local), searchResources(all, "PVE1"))
    }

    @Test
    fun tagsAndPoolsAreSearched() {
        val staged = ClusterResource(id = "qemu/300", type = "qemu", node = "pve2", vmid = 300, name = "db", pool = "staging")

        assertEquals(listOf(web), searchResources(all, "nginx"))
        assertEquals(listOf(staged), searchResources(all + staged, "staging"))
    }

    @Test
    fun storageIsFoundByNameOrType() {
        assertEquals(listOf(local), searchResources(all, "local"))
        assertEquals(listOf(local), searchResources(all, "dir"))
    }

    @Test
    fun poolsThemselvesAreNotResults() {
        assertEquals(listOf(webdev), searchResources(all, "dev"))
    }

    @Test
    fun titlesFollowTheKind() {
        assertEquals(listOf("web", "pve1", "local"), listOf(web, pve1, local).map { it.title })
    }
}
