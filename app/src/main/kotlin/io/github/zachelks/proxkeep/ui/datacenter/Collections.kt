package io.github.zachelks.proxkeep.ui.datacenter

import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.encodePathValue

/**
 * Something done to one item of a collection, such as sending a test
 * notification.
 *
 * @property path the call's path template, filled like [CollectionSpec.itemPath]
 */
data class ItemAction(val label: Int, val method: String, val path: String, val done: Int)

/**
 * One list of API objects, such as the firewall's aliases, and how it is
 * browsed and changed. Paths are the schema's own templates, such as
 * `/access/users/{userid}/token/{tokenid}`, so the schema says whether the
 * server has them and how to edit them.
 *
 * @property idKey the key in each listed object that identifies it
 * @property itemPath the template of one object's path; null when objects have none
 * @property itemParam the name in [itemPath] that the object's ID fills
 * @property createWithId whether a new object's ID is part of the path it is created at, so it is asked for first
 * @property types values for `type` that creating offers, one per kind of object
 * @property child the collection an item opens, with [itemParam] filled from the item
 * @property readOnlyWhen a key and value that mark an item as built in, so it cannot be edited or deleted
 * @property aclStyle whether this is the permissions list, whose entries have no paths of their own
 * @property positional whether items are addressed by their position in the list, such as firewall rules,
 *   so a change to a stale list must carry the list's digest to be refused rather than hit another item
 */
data class CollectionSpec(
    val id: String,
    val title: Int,
    val listPath: String,
    val idKey: String,
    val titleKeys: List<String>,
    val detailKeys: List<String> = emptyList(),
    val itemPath: String? = null,
    val itemParam: String? = null,
    val canCreate: Boolean = true,
    val createWithId: Boolean = false,
    val types: List<String> = emptyList(),
    val child: String? = null,
    val editable: Boolean = true,
    val deletable: Boolean = true,
    val readOnlyWhen: Pair<String, String>? = null,
    val actions: List<ItemAction> = emptyList(),
    val aclStyle: Boolean = false,
    val exclude: List<String> = emptyList(),
    val positional: Boolean = false,
) {
    fun list(args: Map<String, String>): String = fill(listPath, args)

    fun item(args: Map<String, String>, id: String): String? =
        itemPath?.let { fill(it, args + (itemParam!! to id)) }

    companion object {
        /** Fills `{name}` placeholders with [args], each kept to one path segment. */
        fun fill(template: String, args: Map<String, String>): String =
            Regex("\\{([^}]+)}").replace(template) { match -> args[match.groupValues[1]]?.let(::encodePathValue) ?: match.value }
    }
}

/** Every collection the datacenter screens browse, by ID. */
object Collections {

    private val firewallRuleKeys = listOf("type", "action", "macro", "source", "dest", "proto", "dport", "comment")

    val all: List<CollectionSpec> = listOf(
        // --- Access control -------------------------------------------------
        CollectionSpec(
            id = "users", title = R.string.dc_users, listPath = "/access/users", idKey = "userid",
            titleKeys = listOf("userid"), detailKeys = listOf("firstname", "lastname", "email", "comment"),
            itemPath = "/access/users/{userid}", itemParam = "userid", child = "tokens",
        ),
        CollectionSpec(
            id = "tokens", title = R.string.dc_tokens, listPath = "/access/users/{userid}/token", idKey = "tokenid",
            titleKeys = listOf("tokenid"), detailKeys = listOf("comment"),
            itemPath = "/access/users/{userid}/token/{tokenid}", itemParam = "tokenid", createWithId = true,
        ),
        CollectionSpec(
            id = "groups", title = R.string.dc_groups, listPath = "/access/groups", idKey = "groupid",
            titleKeys = listOf("groupid"), detailKeys = listOf("comment", "users"),
            itemPath = "/access/groups/{groupid}", itemParam = "groupid",
        ),
        CollectionSpec(
            id = "roles", title = R.string.dc_roles, listPath = "/access/roles", idKey = "roleid",
            titleKeys = listOf("roleid"), detailKeys = listOf("privs"),
            itemPath = "/access/roles/{roleid}", itemParam = "roleid", readOnlyWhen = "special" to "1",
        ),
        CollectionSpec(
            id = "acl", title = R.string.dc_permissions, listPath = "/access/acl", idKey = "path",
            titleKeys = listOf("path"), detailKeys = listOf("ugid", "roleid", "type"),
            editable = false, aclStyle = true,
        ),
        CollectionSpec(
            id = "realms", title = R.string.dc_realms, listPath = "/access/domains", idKey = "realm",
            titleKeys = listOf("realm"), detailKeys = listOf("type", "comment"),
            itemPath = "/access/domains/{realm}", itemParam = "realm", types = listOf("ad", "ldap", "openid"),
            readOnlyWhen = "type" to "pam",
        ),
        CollectionSpec(
            id = "pools", title = R.string.dc_pools, listPath = "/pools", idKey = "poolid",
            titleKeys = listOf("poolid"), detailKeys = listOf("comment"),
            itemPath = "/pools/{poolid}", itemParam = "poolid",
        ),
        // --- Firewall ---------------------------------------------------------
        CollectionSpec(
            id = "fw-rules", title = R.string.dc_firewall_rules, listPath = "/cluster/firewall/rules", idKey = "pos",
            titleKeys = listOf("action", "type"), detailKeys = firewallRuleKeys,
            itemPath = "/cluster/firewall/rules/{pos}", itemParam = "pos", positional = true,
        ),
        CollectionSpec(
            id = "fw-groups", title = R.string.dc_security_groups, listPath = "/cluster/firewall/groups", idKey = "group",
            titleKeys = listOf("group"), detailKeys = listOf("comment"),
            itemPath = "/cluster/firewall/groups/{group}", itemParam = "group", child = "fw-group-rules", editable = false,
        ),
        CollectionSpec(
            id = "fw-group-rules", title = R.string.dc_firewall_rules, listPath = "/cluster/firewall/groups/{group}", idKey = "pos",
            titleKeys = listOf("action", "type"), detailKeys = firewallRuleKeys,
            itemPath = "/cluster/firewall/groups/{group}/{pos}", itemParam = "pos", positional = true,
        ),
        CollectionSpec(
            id = "fw-aliases", title = R.string.dc_aliases, listPath = "/cluster/firewall/aliases", idKey = "name",
            titleKeys = listOf("name"), detailKeys = listOf("cidr", "comment"),
            itemPath = "/cluster/firewall/aliases/{name}", itemParam = "name",
        ),
        CollectionSpec(
            id = "fw-ipsets", title = R.string.dc_ipsets, listPath = "/cluster/firewall/ipset", idKey = "name",
            titleKeys = listOf("name"), detailKeys = listOf("comment"),
            itemPath = "/cluster/firewall/ipset/{name}", itemParam = "name", child = "fw-ipset-entries", editable = false,
        ),
        CollectionSpec(
            id = "fw-ipset-entries", title = R.string.dc_ipset_entries, listPath = "/cluster/firewall/ipset/{name}", idKey = "cidr",
            titleKeys = listOf("cidr"), detailKeys = listOf("comment"),
            itemPath = "/cluster/firewall/ipset/{name}/{cidr}", itemParam = "cidr",
        ),
        // --- High availability ----------------------------------------------
        CollectionSpec(
            id = "ha-status", title = R.string.dc_ha_status, listPath = "/cluster/ha/status/current", idKey = "id",
            titleKeys = listOf("id"), detailKeys = listOf("status", "node", "state"),
            canCreate = false, editable = false, deletable = false,
        ),
        CollectionSpec(
            id = "ha-resources", title = R.string.dc_ha_resources, listPath = "/cluster/ha/resources", idKey = "sid",
            titleKeys = listOf("sid"), detailKeys = listOf("state", "group", "comment"),
            itemPath = "/cluster/ha/resources/{sid}", itemParam = "sid",
        ),
        CollectionSpec(
            id = "ha-rules", title = R.string.dc_ha_rules, listPath = "/cluster/ha/rules", idKey = "rule",
            titleKeys = listOf("rule"), detailKeys = listOf("type", "resources", "nodes"),
            itemPath = "/cluster/ha/rules/{rule}", itemParam = "rule", types = listOf("node-affinity", "resource-affinity"),
        ),
        CollectionSpec(
            id = "ha-groups", title = R.string.dc_ha_groups, listPath = "/cluster/ha/groups", idKey = "group",
            titleKeys = listOf("group"), detailKeys = listOf("nodes", "comment"),
            itemPath = "/cluster/ha/groups/{group}", itemParam = "group",
        ),
        // --- Replication -------------------------------------------------------
        CollectionSpec(
            id = "replication", title = R.string.dc_replication, listPath = "/cluster/replication", idKey = "id",
            titleKeys = listOf("id"), detailKeys = listOf("target", "schedule", "comment"),
            itemPath = "/cluster/replication/{id}", itemParam = "id", exclude = listOf("type"),
        ),
        // --- SDN ---------------------------------------------------------------
        CollectionSpec(
            id = "sdn-zones", title = R.string.dc_sdn_zones, listPath = "/cluster/sdn/zones", idKey = "zone",
            titleKeys = listOf("zone"), detailKeys = listOf("type", "nodes"),
            itemPath = "/cluster/sdn/zones/{zone}", itemParam = "zone", types = listOf("simple", "vlan", "qinq", "vxlan", "evpn"),
        ),
        CollectionSpec(
            id = "sdn-vnets", title = R.string.dc_sdn_vnets, listPath = "/cluster/sdn/vnets", idKey = "vnet",
            titleKeys = listOf("vnet"), detailKeys = listOf("zone", "tag", "alias"),
            itemPath = "/cluster/sdn/vnets/{vnet}", itemParam = "vnet", child = "sdn-subnets",
        ),
        CollectionSpec(
            id = "sdn-subnets", title = R.string.dc_sdn_subnets, listPath = "/cluster/sdn/vnets/{vnet}/subnets", idKey = "subnet",
            titleKeys = listOf("cidr", "subnet"), detailKeys = listOf("gateway", "snat"),
            itemPath = "/cluster/sdn/vnets/{vnet}/subnets/{subnet}", itemParam = "subnet", exclude = listOf("type"),
        ),
        CollectionSpec(
            id = "sdn-controllers", title = R.string.dc_sdn_controllers, listPath = "/cluster/sdn/controllers", idKey = "controller",
            titleKeys = listOf("controller"), detailKeys = listOf("type"),
            itemPath = "/cluster/sdn/controllers/{controller}", itemParam = "controller", types = listOf("evpn", "bgp", "isis"),
        ),
        CollectionSpec(
            id = "sdn-ipams", title = R.string.dc_sdn_ipams, listPath = "/cluster/sdn/ipams", idKey = "ipam",
            titleKeys = listOf("ipam"), detailKeys = listOf("type"),
            itemPath = "/cluster/sdn/ipams/{ipam}", itemParam = "ipam", types = listOf("pve", "netbox", "phpipam"),
        ),
        CollectionSpec(
            id = "sdn-dns", title = R.string.dc_sdn_dns, listPath = "/cluster/sdn/dns", idKey = "dns",
            titleKeys = listOf("dns"), detailKeys = listOf("type", "url"),
            itemPath = "/cluster/sdn/dns/{dns}", itemParam = "dns", types = listOf("powerdns"),
        ),
        // --- Ceph ----------------------------------------------------------------
        CollectionSpec(
            id = "ceph-pools", title = R.string.dc_ceph_pools, listPath = "/nodes/{node}/ceph/pool", idKey = "pool_name",
            titleKeys = listOf("pool_name", "pool"), detailKeys = listOf("size", "min_size", "pg_num", "crush_rule_name"),
            itemPath = "/nodes/{node}/ceph/pool/{name}", itemParam = "name",
        ),
        // --- Notifications ------------------------------------------------------
        CollectionSpec(
            id = "notify-targets", title = R.string.dc_notification_targets, listPath = "/cluster/notifications/targets", idKey = "name",
            titleKeys = listOf("name"), detailKeys = listOf("type", "comment"),
            canCreate = false, editable = false, deletable = false,
            actions = listOf(
                ItemAction(R.string.action_send_test, "POST", "/cluster/notifications/targets/{name}/test", R.string.dc_test_sent),
            ),
        ),
        CollectionSpec(
            id = "notify-matchers", title = R.string.dc_notification_matchers, listPath = "/cluster/notifications/matchers", idKey = "name",
            titleKeys = listOf("name"), detailKeys = listOf("target", "comment"),
            itemPath = "/cluster/notifications/matchers/{name}", itemParam = "name",
        ),
    ) + listOf("sendmail", "smtp", "gotify", "webhook").map { type ->
        CollectionSpec(
            id = "notify-$type", title = endpointTitle(type), listPath = "/cluster/notifications/endpoints/$type", idKey = "name",
            titleKeys = listOf("name"), detailKeys = listOf("comment", "server", "mailto", "url"),
            itemPath = "/cluster/notifications/endpoints/$type/{name}", itemParam = "name",
        )
    } + backupServerCollections + managerCollections

    private fun endpointTitle(type: String): Int = when (type) {
        "sendmail" -> R.string.dc_endpoint_sendmail
        "smtp" -> R.string.dc_endpoint_smtp
        "gotify" -> R.string.dc_endpoint_gotify
        else -> R.string.dc_endpoint_webhook
    }

    private val byId = all.associateBy { it.id }

    fun get(id: String): CollectionSpec? = byId[id]
}
