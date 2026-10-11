package io.github.zachelks.proxkeep.core.api

import io.github.zachelks.proxkeep.core.api.json.PveJson
import io.github.zachelks.proxkeep.core.api.model.ApiEnvelope
import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.core.api.model.VersionInfo
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelParsingTest {

    private val resourcesJson = """
        {"data":[
          {"id":"node/pve1","type":"node","node":"pve1","status":"online",
           "cpu":0.0123,"maxcpu":8,"mem":4294967296,"maxmem":"16777216000",
           "disk":1000,"maxdisk":2000,"uptime":86400,"level":""},
          {"id":"qemu/100","type":"qemu","node":"pve1","vmid":"100","name":"web",
           "status":"running","template":0,"cpu":0.5,"maxcpu":2,
           "mem":1073741824,"maxmem":2147483648,"tags":"prod;web","netin":1.5e9},
          {"id":"lxc/101","type":"lxc","node":"pve1","vmid":101,"name":"dns",
           "status":"stopped","template":"1","futurefield":{"a":1}},
          {"id":"storage/pve1/local","type":"storage","node":"pve1","storage":"local",
           "status":"available","shared":0,"plugintype":"dir","content":"iso,backup"},
          {"id":"sdn/pve1/localnetwork","type":"sdn","node":"pve1","status":"ok"},
          {"id":"future/1","type":"somethingnew"}
        ]}
    """.trimIndent()

    private fun resources(): List<ClusterResource> {
        val envelope = PveJson.decodeFromString<ApiEnvelope<List<ClusterResource>>>(resourcesJson)
        return assertNotNull(envelope.data)
    }

    @Test
    fun clusterResourcesParseEveryKind() {
        val kinds = resources().map { it.kind }
        assertEquals(
            listOf(
                ResourceKind.Node,
                ResourceKind.Qemu,
                ResourceKind.Lxc,
                ResourceKind.Storage,
                ResourceKind.Sdn,
                ResourceKind.Unknown,
            ),
            kinds,
        )
    }

    @Test
    fun nodeRowReadsMixedNumberForms() {
        val node = resources().first { it.kind == ResourceKind.Node }
        assertEquals("pve1", node.node)
        assertEquals(8.0, node.maxcpu)
        assertEquals(4294967296L, node.mem)
        assertEquals(16777216000L, node.maxmem)
        assertEquals(86400L, node.uptime)
        assertFalse(node.isGuest)
    }

    @Test
    fun guestRowsReadIdsFlagsAndTags() {
        val vm = resources().first { it.kind == ResourceKind.Qemu }
        assertEquals(100, vm.vmid)
        assertTrue(vm.isGuest)
        assertFalse(vm.isTemplate)
        assertEquals(listOf("prod", "web"), vm.tagList)
        assertEquals(1500000000L, vm.netin)

        val container = resources().first { it.kind == ResourceKind.Lxc }
        assertEquals(101, container.vmid)
        assertTrue(container.isTemplate)
        assertEquals(emptyList(), container.tagList)
    }

    @Test
    fun storageRowReadsSharedFlag() {
        val storage = resources().first { it.kind == ResourceKind.Storage }
        assertEquals("local", storage.storage)
        assertEquals(false, storage.shared)
        assertEquals("dir", storage.plugintype)
    }

    @Test
    fun errorEnvelopeCarriesParameterErrors() {
        val json = """
            {"data":null,"errors":{"vmid":"invalid format - value does not look like a valid VM ID"},
             "message":"Parameter verification failed.\n"}
        """.trimIndent()
        val envelope = PveJson.decodeFromString<ApiEnvelope<String>>(json)
        assertNull(envelope.data)
        assertEquals(setOf("vmid"), envelope.errors?.keys)
        assertEquals("Parameter verification failed.\n", envelope.message)
    }

    @Test
    fun versionInfoExposesMajorVersion() {
        val json = """{"data":{"version":"9.2.1","release":"9.2","repoid":"0123abcd"}}"""
        val version = assertNotNull(PveJson.decodeFromString<ApiEnvelope<VersionInfo>>(json).data)
        assertEquals("9.2.1", version.version)
        assertEquals(9, version.major)
    }
}
