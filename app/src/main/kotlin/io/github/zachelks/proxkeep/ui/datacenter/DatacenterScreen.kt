@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.zachelks.proxkeep.ui.datacenter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.core.api.schema.ApiSchema
import io.github.zachelks.proxkeep.data.ClusterState
import io.github.zachelks.proxkeep.ui.appContainer
import io.github.zachelks.proxkeep.ui.lifecycle.ConfirmDialog
import io.github.zachelks.proxkeep.ui.navigation.Route
import io.github.zachelks.proxkeep.ui.toUiMessage
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * One entry of the datacenter screen.
 *
 * @property schemaPath the path whose presence in the server's schema decides whether the server has this
 */
sealed interface DatacenterEntry {
    val label: Int
    val schemaPath: String

    data class Collection(val specId: String, override val label: Int, override val schemaPath: String, val args: (node: String?) -> Map<String, String> = { emptyMap() }) : DatacenterEntry

    data class Settings(override val label: Int, val path: String, override val schemaPath: String = path) : DatacenterEntry

    data class Screen(override val label: Int, val route: Route, override val schemaPath: String) : DatacenterEntry

    /** An action that changes the whole datacenter, such as applying the SDN configuration. */
    data class Apply(override val label: Int, val method: String, val path: String, val confirmBody: Int, val done: Int, override val schemaPath: String = path) : DatacenterEntry
}

/** A titled group of entries. */
data class DatacenterGroup(val title: Int, val entries: List<DatacenterEntry>)

private fun collection(specId: String, label: Int) = Collections.get(specId)!!.let { DatacenterEntry.Collection(specId, label, it.listPath) }

/** Everything the datacenter offers, grouped as the web interface groups it. */
val DATACENTER_GROUPS = listOf(
    DatacenterGroup(
        R.string.dc_group_general,
        listOf(
            DatacenterEntry.Settings(R.string.dc_options, "/cluster/options"),
            DatacenterEntry.Screen(R.string.backup_jobs_title, Route.BackupJobs, "/cluster/backup"),
            collection("replication", R.string.dc_replication),
        ),
    ),
    DatacenterGroup(
        R.string.dc_group_access,
        listOf(
            collection("users", R.string.dc_users),
            collection("groups", R.string.dc_groups),
            collection("pools", R.string.dc_pools),
            collection("roles", R.string.dc_roles),
            collection("acl", R.string.dc_permissions),
            collection("realms", R.string.dc_realms),
        ),
    ),
    DatacenterGroup(
        R.string.dc_group_firewall,
        listOf(
            DatacenterEntry.Settings(R.string.dc_firewall_options, "/cluster/firewall/options"),
            collection("fw-rules", R.string.dc_firewall_rules),
            collection("fw-groups", R.string.dc_security_groups),
            collection("fw-aliases", R.string.dc_aliases),
            collection("fw-ipsets", R.string.dc_ipsets),
        ),
    ),
    DatacenterGroup(
        R.string.dc_group_ha,
        listOf(
            collection("ha-status", R.string.dc_ha_status),
            collection("ha-resources", R.string.dc_ha_resources),
            collection("ha-rules", R.string.dc_ha_rules),
            collection("ha-groups", R.string.dc_ha_groups),
        ),
    ),
    DatacenterGroup(
        R.string.dc_group_sdn,
        listOf(
            collection("sdn-zones", R.string.dc_sdn_zones),
            collection("sdn-vnets", R.string.dc_sdn_vnets),
            collection("sdn-controllers", R.string.dc_sdn_controllers),
            collection("sdn-ipams", R.string.dc_sdn_ipams),
            collection("sdn-dns", R.string.dc_sdn_dns),
            DatacenterEntry.Apply(R.string.dc_sdn_apply, "PUT", "/cluster/sdn", R.string.dc_sdn_apply_body, R.string.dc_sdn_applied),
        ),
    ),
    DatacenterGroup(
        R.string.dc_group_ceph,
        listOf(
            DatacenterEntry.Screen(R.string.dc_ceph_status, Route.Ceph, "/cluster/ceph/status"),
            DatacenterEntry.Collection("ceph-pools", R.string.dc_ceph_pools, "/nodes/{node}/ceph/pool") { node -> mapOf("node" to node.orEmpty()) },
        ),
    ),
    DatacenterGroup(
        R.string.dc_group_notifications,
        listOf(
            collection("notify-targets", R.string.dc_notification_targets),
            collection("notify-matchers", R.string.dc_notification_matchers),
            collection("notify-sendmail", R.string.dc_endpoint_sendmail),
            collection("notify-smtp", R.string.dc_endpoint_smtp),
            collection("notify-gotify", R.string.dc_endpoint_gotify),
            collection("notify-webhook", R.string.dc_endpoint_webhook),
        ),
    ),
)

/** The entries a server has: all of them while its schema is not known. */
fun availableEntries(schema: ApiSchema?, group: DatacenterGroup): List<DatacenterEntry> =
    if (schema == null) group.entries else group.entries.filter { schema.endpoint(it.schemaPath) != null }

/** The datacenter: cluster options, access control, firewall, high availability, SDN, Ceph, notifications and backups. */
@Composable
fun DatacenterScreen(onClose: () -> Unit, onOpenRoute: (Route) -> Unit) {
    val container = appContainer()
    val cluster by container.cluster.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // Node-scoped lists, such as Ceph pools, are read through the first online node.
    val node = (cluster as? ClusterState.Loaded)?.resources
        ?.filter { it.kind == ResourceKind.Node && it.status == "online" }?.mapNotNull { it.node }?.minOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.section_datacenter)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        SectionList(DATACENTER_GROUPS, onOpenRoute, snackbar, Modifier.padding(innerPadding), node)
    }
}

/**
 * Grouped entries that open collections, settings and screens, leaving out
 * what the server's schema does not have.
 *
 * @param node fills `{node}` in node-scoped entries
 */
@Composable
fun SectionList(groups: List<DatacenterGroup>, onOpenRoute: (Route) -> Unit, snackbar: SnackbarHostState, modifier: Modifier = Modifier, node: String? = null) {
    val container = appContainer()
    var schema by remember { mutableStateOf<ApiSchema?>(null) }
    var confirm by remember { mutableStateOf<DatacenterEntry.Apply?>(null) }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    LaunchedEffect(Unit) {
        schema = try {
            container.schemas.schema()
        } catch (e: IOException) {
            null
        }
    }

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        groups.forEach { group ->
            val entries = availableEntries(schema, group)
            if (entries.isEmpty()) return@forEach
            item(key = "group:${group.title}") {
                Text(
                    stringResource(group.title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
                )
            }
            items(entries, key = { "entry:${group.title}:${it.label}" }) { entry ->
                val label = stringResource(entry.label)
                ListItem(
                    headlineContent = { Text(label) },
                    modifier = Modifier.clickable {
                        when (entry) {
                            is DatacenterEntry.Collection -> onOpenRoute(Route.Collection(entry.specId, entry.args(node)))
                            is DatacenterEntry.Settings -> onOpenRoute(Route.SchemaEdit(label, entry.path, entry.schemaPath, "PUT"))
                            is DatacenterEntry.Screen -> onOpenRoute(entry.route)
                            is DatacenterEntry.Apply -> confirm = entry
                        }
                    },
                )
            }
        }
    }

    confirm?.let { entry ->
        ConfirmDialog(
            title = stringResource(entry.label),
            body = stringResource(entry.confirmBody),
            confirmLabel = stringResource(R.string.action_apply),
            destructive = true,
            onConfirm = {
                confirm = null
                scope.launch {
                    val message = try {
                        container.repository.request { it.write(entry.method, entry.path, emptyMap()) }
                        resources.getString(entry.done)
                    } catch (e: IOException) {
                        val failure = e.toUiMessage()
                        resources.getString(failure.id) + failure.detail?.let { "\n$it" }.orEmpty()
                    }
                    snackbar.showSnackbar(message)
                }
            },
            onDismiss = { confirm = null },
        )
    }
}
