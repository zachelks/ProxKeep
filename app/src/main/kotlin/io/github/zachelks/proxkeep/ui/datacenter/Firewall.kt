@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.zachelks.proxkeep.ui.datacenter

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.data.ClusterState
import io.github.zachelks.proxkeep.ui.appContainer
import io.github.zachelks.proxkeep.ui.components.CenteredProgress
import io.github.zachelks.proxkeep.ui.navigation.Route

/**
 * A firewall's options and lists, with [args] filling its path, such as
 * `node` and `vmid` for a guest's firewall.
 */
fun firewallGroups(scope: FirewallScope, args: Map<String, String>): List<DatacenterGroup> {
    val options = "${scope.base}/options"
    val lists = listOf("rules" to R.string.dc_firewall_rules, "aliases" to R.string.dc_aliases, "ipsets" to R.string.dc_ipsets, "log" to R.string.fw_log)
        .mapNotNull { (list, label) ->
            Collections.get(scope.specId(list))?.let { spec -> DatacenterEntry.Collection(spec.id, label, spec.listPath) { args } }
        }
    return listOf(
        DatacenterGroup(
            R.string.dc_group_firewall,
            listOf(DatacenterEntry.Settings(R.string.dc_firewall_options, CollectionSpec.fill(options, args), options)) + lists,
        ),
    )
}

/** The firewall of a guest; a rule applies only on network devices whose firewall is on. */
@Composable
fun GuestFirewallScreen(resourceId: String, onClose: () -> Unit, onOpenRoute: (Route) -> Unit) {
    val cluster by appContainer().cluster.state.collectAsStateWithLifecycle()
    val guest = (cluster as? ClusterState.Loaded)?.resources?.firstOrNull { it.id == resourceId }
    val snackbar = remember { SnackbarHostState() }
    val name = guest?.name ?: guest?.vmid?.toString() ?: resourceId

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.fw_title, name)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        val node = guest?.node
        val vmid = guest?.vmid
        if (node == null || vmid == null) {
            CenteredProgress(Modifier.padding(innerPadding))
        } else {
            val scope = if (guest.kind == ResourceKind.Lxc) FirewallScope.Lxc else FirewallScope.Qemu
            val groups = remember(scope, node, vmid) { firewallGroups(scope, mapOf("node" to node, "vmid" to vmid.toString())) }
            SectionList(groups, onOpenRoute, snackbar, Modifier.padding(innerPadding))
        }
    }
}
