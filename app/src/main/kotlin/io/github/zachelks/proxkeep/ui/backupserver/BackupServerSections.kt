package io.github.zachelks.proxkeep.ui.backupserver

import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.pbs.PBS_NODE
import io.github.zachelks.proxkeep.ui.datacenter.Collections
import io.github.zachelks.proxkeep.ui.datacenter.DatacenterEntry
import io.github.zachelks.proxkeep.ui.datacenter.DatacenterGroup

// Kept apart from the collections themselves, which must be built before these look them up.

private val node: (String?) -> Map<String, String> = { mapOf("node" to PBS_NODE) }

private fun entry(specId: String, label: Int, nodeScoped: Boolean = false): DatacenterEntry.Collection {
    val spec = Collections.get(specId)!!
    return if (nodeScoped) DatacenterEntry.Collection(specId, label, spec.listPath, node) else DatacenterEntry.Collection(specId, label, spec.listPath)
}

/** The Backup Server's administration, grouped as its web interface groups it. */
val BACKUP_SERVER_GROUPS = listOf(
    DatacenterGroup(
        R.string.pbs_group_datastores,
        listOf(
            entry("pbs-datastores", R.string.pbs_datastore_config),
            entry("pbs-sync", R.string.pbs_sync_jobs),
            entry("pbs-verify", R.string.pbs_verify_jobs),
            entry("pbs-prune", R.string.pbs_prune_jobs),
            entry("pbs-remotes", R.string.pbs_remotes),
            entry("pbs-traffic", R.string.pbs_traffic_control),
        ),
    ),
    DatacenterGroup(R.string.dc_group_access, listOf(entry("pbs-users", R.string.dc_users))),
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
            DatacenterEntry.Apply(
                R.string.pbs_network_apply, "PUT", "/nodes/$PBS_NODE/network", R.string.pbs_network_apply_body,
                R.string.pbs_network_applied, schemaPath = "/nodes/{node}/network",
            ),
            DatacenterEntry.Settings(R.string.pbs_dns, "/nodes/$PBS_NODE/dns", "/nodes/{node}/dns"),
            DatacenterEntry.Settings(R.string.pbs_time, "/nodes/$PBS_NODE/time", "/nodes/{node}/time"),
            entry("pbs-disks", R.string.node_tab_disks, nodeScoped = true),
        ),
    ),
    DatacenterGroup(
        R.string.dc_group_notifications,
        listOf(
            entry("pbs-notify-targets", R.string.dc_notification_targets),
            entry("pbs-notify-matchers", R.string.dc_notification_matchers),
            entry("pbs-notify-sendmail", R.string.dc_endpoint_sendmail),
            entry("pbs-notify-smtp", R.string.dc_endpoint_smtp),
            entry("pbs-notify-gotify", R.string.dc_endpoint_gotify),
            entry("pbs-notify-webhook", R.string.dc_endpoint_webhook),
        ),
    ),
)
