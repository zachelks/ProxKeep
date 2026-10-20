package io.github.zachelks.proxkeep.ui.manager

import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.pbs.PBS_NODE
import io.github.zachelks.proxkeep.ui.datacenter.Collections
import io.github.zachelks.proxkeep.ui.datacenter.DatacenterEntry
import io.github.zachelks.proxkeep.ui.datacenter.DatacenterGroup
import io.github.zachelks.proxkeep.ui.navigation.Route

// Kept apart from the collections themselves, which must be built before these look them up.

private val node: (String?) -> Map<String, String> = { mapOf("node" to PBS_NODE) }

private fun entry(specId: String, label: Int, nodeScoped: Boolean = false): DatacenterEntry.Collection {
    val spec = Collections.get(specId)!!
    return if (nodeScoped) DatacenterEntry.Collection(specId, label, spec.listPath, node) else DatacenterEntry.Collection(specId, label, spec.listPath)
}

/**
 * The Datacenter Manager's administration. Like a Backup Server, it calls
 * itself `localhost` and shares the same server settings, which the schema
 * hides where the manager lacks them.
 */
val MANAGER_GROUPS = listOf(
    DatacenterGroup(R.string.pdm_group_remotes, listOf(entry("pdm-remotes", R.string.pdm_remotes))),
    DatacenterGroup(
        R.string.dc_group_access,
        listOf(entry("pbs-users", R.string.dc_users), DatacenterEntry.Screen(R.string.tfa_title, Route.TwoFactor, "/access/tfa")),
    ),
    DatacenterGroup(
        R.string.pbs_group_server,
        listOf(
            entry("pbs-services", R.string.node_tab_services, nodeScoped = true),
            entry("pbs-updates", R.string.node_tab_updates, nodeScoped = true),
            DatacenterEntry.Apply(
                R.string.pbs_refresh_updates, "POST", "/nodes/$PBS_NODE/apt/update", R.string.pbs_refresh_updates_body,
                R.string.pbs_refresh_updates_started, schemaPath = "/nodes/{node}/apt/update",
            ),
            entry("pbs-network", R.string.node_tab_network, nodeScoped = true),
            DatacenterEntry.Settings(R.string.pbs_dns, "/nodes/$PBS_NODE/dns", "/nodes/{node}/dns"),
            DatacenterEntry.Settings(R.string.pbs_time, "/nodes/$PBS_NODE/time", "/nodes/{node}/time"),
        ),
    ),
)
