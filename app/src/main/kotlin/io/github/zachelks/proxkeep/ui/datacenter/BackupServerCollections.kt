package io.github.zachelks.proxkeep.ui.datacenter

import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.pbs.PBS_NODE

/**
 * The collections of a Proxmox Backup Server. Paths are the Backup Server's
 * own schema templates; `{node}` is always [PBS_NODE].
 */
internal val backupServerCollections: List<CollectionSpec> = listOf(
    // --- Datastores and jobs -----------------------------------------------
    CollectionSpec(
        id = "pbs-datastores", title = R.string.pbs_datastore_config, listPath = "/config/datastore", idKey = "name",
        titleKeys = listOf("name"), detailKeys = listOf("path", "gc-schedule", "comment"),
        itemPath = "/config/datastore/{name}", itemParam = "name",
    ),
    CollectionSpec(
        id = "pbs-remotes", title = R.string.pbs_remotes, listPath = "/config/remote", idKey = "name",
        titleKeys = listOf("name"), detailKeys = listOf("host", "auth-id", "comment"),
        itemPath = "/config/remote/{name}", itemParam = "name",
    ),
    CollectionSpec(
        id = "pbs-sync", title = R.string.pbs_sync_jobs, listPath = "/config/sync", idKey = "id",
        titleKeys = listOf("id"), detailKeys = listOf("store", "remote", "remote-store", "schedule", "comment"),
        itemPath = "/config/sync/{id}", itemParam = "id",
        actions = listOf(ItemAction(R.string.action_run_now, "POST", "/admin/sync/{id}/run", R.string.pbs_job_done)),
    ),
    CollectionSpec(
        id = "pbs-verify", title = R.string.pbs_verify_jobs, listPath = "/config/verify", idKey = "id",
        titleKeys = listOf("id"), detailKeys = listOf("store", "ns", "schedule", "comment"),
        itemPath = "/config/verify/{id}", itemParam = "id",
        actions = listOf(ItemAction(R.string.action_run_now, "POST", "/admin/verify/{id}/run", R.string.pbs_job_done)),
    ),
    CollectionSpec(
        id = "pbs-prune", title = R.string.pbs_prune_jobs, listPath = "/config/prune", idKey = "id",
        titleKeys = listOf("id"), detailKeys = listOf("store", "ns", "schedule", "comment"),
        itemPath = "/config/prune/{id}", itemParam = "id",
        actions = listOf(ItemAction(R.string.action_run_now, "POST", "/admin/prune/{id}/run", R.string.pbs_job_done)),
    ),
    CollectionSpec(
        id = "pbs-traffic", title = R.string.pbs_traffic_control, listPath = "/config/traffic-control", idKey = "name",
        titleKeys = listOf("name"), detailKeys = listOf("rate-in", "rate-out", "network", "comment"),
        itemPath = "/config/traffic-control/{name}", itemParam = "name",
    ),
    // --- Access ----------------------------------------------------------------
    CollectionSpec(
        id = "pbs-users", title = R.string.dc_users, listPath = "/access/users", idKey = "userid",
        titleKeys = listOf("userid"), detailKeys = listOf("firstname", "lastname", "email", "comment"),
        itemPath = "/access/users/{userid}", itemParam = "userid",
    ),
    // --- The server ------------------------------------------------------------
    CollectionSpec(
        id = "pbs-services", title = R.string.node_tab_services, listPath = "/nodes/{node}/services", idKey = "service",
        titleKeys = listOf("service"), detailKeys = listOf("desc", "state"),
        canCreate = false, editable = false, deletable = false,
        actions = listOf(
            ItemAction(R.string.action_start, "POST", "/nodes/{node}/services/{service}/start", R.string.node_service_done),
            ItemAction(R.string.action_restart_service, "POST", "/nodes/{node}/services/{service}/restart", R.string.node_service_done),
        ),
    ),
    CollectionSpec(
        id = "pbs-network", title = R.string.node_tab_network, listPath = "/nodes/{node}/network", idKey = "name",
        titleKeys = listOf("name"), detailKeys = listOf("type", "cidr", "gateway", "comments"),
        itemPath = "/nodes/{node}/network/{iface}", itemParam = "iface", types = listOf("bridge", "bond", "vlan"),
    ),
    CollectionSpec(
        id = "pbs-updates", title = R.string.node_tab_updates, listPath = "/nodes/{node}/apt/update", idKey = "Package",
        titleKeys = listOf("Package"), detailKeys = listOf("OldVersion", "Version"),
        canCreate = false, editable = false, deletable = false,
    ),
    CollectionSpec(
        id = "pbs-disks", title = R.string.node_tab_disks, listPath = "/nodes/{node}/disks/list", idKey = "name",
        titleKeys = listOf("name"), detailKeys = listOf("model", "used", "disk-type", "status"),
        canCreate = false, editable = false, deletable = false,
    ),
    // --- Notifications -----------------------------------------------------
    CollectionSpec(
        id = "pbs-notify-targets", title = R.string.dc_notification_targets, listPath = "/config/notifications/targets", idKey = "name",
        titleKeys = listOf("name"), detailKeys = listOf("type", "comment"),
        canCreate = false, editable = false, deletable = false,
        actions = listOf(ItemAction(R.string.action_send_test, "POST", "/config/notifications/targets/{name}/test", R.string.dc_test_sent)),
    ),
    CollectionSpec(
        id = "pbs-notify-matchers", title = R.string.dc_notification_matchers, listPath = "/config/notifications/matchers", idKey = "name",
        titleKeys = listOf("name"), detailKeys = listOf("target", "comment"),
        itemPath = "/config/notifications/matchers/{name}", itemParam = "name",
    ),
) + listOf("sendmail" to R.string.dc_endpoint_sendmail, "smtp" to R.string.dc_endpoint_smtp, "gotify" to R.string.dc_endpoint_gotify, "webhook" to R.string.dc_endpoint_webhook)
    .map { (type, title) ->
        CollectionSpec(
            id = "pbs-notify-$type", title = title, listPath = "/config/notifications/endpoints/$type", idKey = "name",
            titleKeys = listOf("name"), detailKeys = listOf("comment", "server", "mailto", "url"),
            itemPath = "/config/notifications/endpoints/$type/{name}", itemParam = "name",
        )
    }
