package io.github.zachelks.proxkeep.ui.datacenter

import io.github.zachelks.proxkeep.core.api.schema.ApiSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CollectionsTest {

    @Test
    fun everyCollectionHasAUniqueIdAndItsChildExists() {
        assertEquals(Collections.all.size, Collections.all.map { it.id }.toSet().size)
        Collections.all.forEach { spec ->
            spec.child?.let { assertNotNull(Collections.get(it), "${spec.id} opens $it") }
            if (spec.itemPath != null) assertTrue(spec.itemPath!!.contains("{${spec.itemParam}}"), spec.id)
        }
    }

    @Test
    fun pathsAreFilledOneSegmentPerValue() {
        val entries = Collections.get("fw-ipset-entries")!!
        assertEquals("/cluster/firewall/ipset/office", entries.list(mapOf("name" to "office")))
        assertEquals("/cluster/firewall/ipset/office/10.0.0.0%2F8", entries.item(mapOf("name" to "office"), "10.0.0.0/8"))
        assertEquals("/access/users/ops@pve/token/ci", Collections.get("tokens")!!.item(mapOf("userid" to "ops@pve"), "ci"))
    }

    @Test
    fun removingAPermissionSendsTheSameEntryWithDelete() {
        assertEquals(
            mapOf("path" to "/vms/100", "roles" to "PVEVMUser", "groups" to "ops", "propagate" to "1", "delete" to "1"),
            CollectionViewModel.aclRemoval(mapOf("path" to "/vms/100", "roleid" to "PVEVMUser", "type" to "group", "ugid" to "ops", "propagate" to "1")),
        )
        assertEquals("ops@pve!ci", CollectionViewModel.aclRemoval(mapOf("type" to "token", "ugid" to "ops@pve!ci"))["tokens"])
    }

    @Test
    fun theSchemaDecidesWhatACollectionOffers() {
        val schema = ApiSchema.parse(
            """[{"path":"/cluster/firewall/aliases","info":{"GET":{"method":"GET"},"POST":{"method":"POST"}}},
                {"path":"/cluster/firewall/aliases/{name}","info":{"GET":{"method":"GET"},"DELETE":{"method":"DELETE"}}}]""",
        )
        val spec = Collections.get("fw-aliases")!!
        val abilities = CollectionViewModel.abilitiesFrom(schema, spec, CollectionAbilities(true, true, true))
        assertEquals(CollectionAbilities(create = true, edit = false, delete = true), abilities)

        // A server without the collection keeps the description's own abilities; the list explains the rest.
        val missing = CollectionViewModel.abilitiesFrom(schema, Collections.get("ha-rules")!!, CollectionAbilities(true, true, false))
        assertEquals(CollectionAbilities(true, true, false), missing)
    }

    @Test
    fun theDatacenterShowsOnlyWhatTheServerHas() {
        val schema = ApiSchema.parse("""[{"path":"/cluster/options","info":{"GET":{"method":"GET"}}},{"path":"/access/users","info":{"GET":{"method":"GET"}}}]""")
        val shown = DATACENTER_GROUPS.flatMap { availableEntries(schema, it) }
        assertEquals(listOf("/cluster/options", "/access/users"), shown.map { it.schemaPath })
        assertTrue(DATACENTER_GROUPS.sumOf { availableEntries(null, it).size } > 30, "everything while the schema is unknown")
        assertFalse(shown.any { it is DatacenterEntry.Apply })
    }

    @Test
    fun aGuestsFirewallHasItsOwnListsUnderItsPath() {
        val args = mapOf("node" to "pve1", "vmid" to "200")
        val entries = firewallGroups(FirewallScope.Lxc, args).flatMap { it.entries }
        val options = entries.first() as DatacenterEntry.Settings
        assertEquals("/nodes/pve1/lxc/200/firewall/options", options.path)
        assertEquals("/nodes/{node}/lxc/{vmid}/firewall/options", options.schemaPath)

        val lists = entries.filterIsInstance<DatacenterEntry.Collection>()
        assertEquals(listOf("lxc-fw-rules", "lxc-fw-aliases", "lxc-fw-ipsets", "lxc-fw-log"), lists.map { it.specId })
        val rules = Collections.get("lxc-fw-rules")!!
        assertTrue(rules.positional, "rules are addressed by position")
        assertEquals("/nodes/pve1/lxc/200/firewall/rules/3", rules.item(lists.first().args(null), "3"))
        assertEquals("/nodes/pve1/lxc/200/firewall/ipset/office", Collections.get("lxc-fw-ipset-entries")!!.list(args + ("name" to "office")))
    }

    @Test
    fun aNodesFirewallHasRulesAndALogOnly() {
        val lists = firewallGroups(FirewallScope.Node, mapOf("node" to "pve1")).flatMap { it.entries }.filterIsInstance<DatacenterEntry.Collection>()
        assertEquals(listOf("node-fw-rules", "node-fw-log"), lists.map { it.specId })
        assertEquals("/nodes/pve1/firewall/log", Collections.get("node-fw-log")!!.list(lists.last().args(null)))
    }
}
