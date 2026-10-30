@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.zachelks.proxkeep.ui.create

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.ui.appContainer
import io.github.zachelks.proxkeep.ui.components.CenteredProgress
import io.github.zachelks.proxkeep.ui.components.CheckRow
import io.github.zachelks.proxkeep.ui.components.MenuField
import io.github.zachelks.proxkeep.ui.components.MessageWithAction
import io.github.zachelks.proxkeep.ui.components.RadioChoices
import io.github.zachelks.proxkeep.ui.components.SuggestField
import io.github.zachelks.proxkeep.ui.components.WholeNumberField
import io.github.zachelks.proxkeep.ui.config.DiskBus
import io.github.zachelks.proxkeep.ui.config.HardwareFormats
import io.github.zachelks.proxkeep.ui.config.IpMode
import io.github.zachelks.proxkeep.ui.format.formatBytes
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import io.github.zachelks.proxkeep.ui.text

@Composable
fun createGuestViewModel(kind: ResourceKind): CreateGuestViewModel {
    val container = appContainer()
    return viewModel(key = "create:$kind") { CreateGuestViewModel(container.repository, container.cluster, container.guestLifecycle, kind) }
}

/** The new virtual machine or container wizard. It closes once creation has started; the result appears as a message. */
@Composable
fun CreateGuestScreen(kind: ResourceKind, onClose: () -> Unit, viewModel: CreateGuestViewModel = createGuestViewModel(kind)) {
    val cluster by viewModel.cluster.collectAsStateWithLifecycle()
    val node by viewModel.node.collectAsStateWithLifecycle()
    val vm by viewModel.vm.collectAsStateWithLifecycle()
    val ct by viewModel.ct.collectAsStateWithLifecycle()
    val qemu = kind == ResourceKind.Qemu
    val steps = if (qemu) VmStep.entries.size else CtStep.entries.size
    var step by rememberSaveable { mutableIntStateOf(0) }
    val taken = cluster?.takenIds.orEmpty()
    val valid = if (qemu) vm.isValid(VmStep.entries[step], taken) else ct.isValid(CtStep.entries[step], taken)
    val last = step == steps - 1
    val stepTitle = stringResource(if (qemu) VmStep.entries[step].title else CtStep.entries[step].title)

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(if (qemu) R.string.create_vm_title else R.string.create_ct_title)) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_cancel))
                        }
                    },
                )
                LinearProgressIndicator(progress = { (step + 1f) / steps }, modifier = Modifier.fillMaxWidth())
            }
        },
        bottomBar = {
            BottomAppBar {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.create_step, step + 1, steps), modifier = Modifier.weight(1f))
                    TextButton(onClick = { if (step == 0) onClose() else step-- }) {
                        Text(stringResource(if (step == 0) R.string.action_cancel else R.string.action_back))
                    }
                    Button(
                        onClick = {
                            if (!last) step++ else if (viewModel.create()) onClose()
                        },
                        enabled = valid,
                    ) { Text(stringResource(if (last) R.string.action_create else R.string.action_next)) }
                }
            }
        },
    ) { innerPadding ->
        val choices = cluster
        if (choices == null) {
            CenteredProgress(modifier = Modifier.padding(innerPadding))
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stepTitle, style = MaterialTheme.typography.titleLarge)
                if (qemu) {
                    VmStepContent(VmStep.entries[step], vm, choices, node, viewModel)
                } else {
                    CtStepContent(CtStep.entries[step], ct, choices, node, viewModel)
                }
            }
        }
    }
}

private val VmStep.title: Int
    get() = when (this) {
        VmStep.General -> R.string.create_step_general
        VmStep.System -> R.string.create_step_system
        VmStep.Disk -> R.string.create_step_disk
        VmStep.Processor -> R.string.create_step_processor
        VmStep.Network -> R.string.create_step_network
        VmStep.Confirm -> R.string.create_step_confirm
    }

private val CtStep.title: Int
    get() = when (this) {
        CtStep.General -> R.string.create_step_general
        CtStep.Template -> R.string.create_step_template
        CtStep.Disk -> R.string.create_step_disk
        CtStep.Processor -> R.string.create_step_processor
        CtStep.Network -> R.string.create_step_network
        CtStep.Confirm -> R.string.create_step_confirm
    }

/** Shows [content] once the node's storages, images and bridges are read. */
@Composable
private fun WithNode(node: Loadable<NodeChoices>, onRetry: () -> Unit, content: @Composable (NodeChoices) -> Unit) {
    when (node) {
        Loadable.Loading -> CenteredProgress(modifier = Modifier.padding(32.dp))
        is Loadable.Failed -> MessageWithAction(node.message.text(), actionLabel = stringResource(R.string.action_retry), onAction = onRetry)
        is Loadable.Loaded -> content(node.value)
    }
}

@Composable
private fun GeneralFields(nodeName: String, vmid: String, choices: ClusterChoices, onNode: (String) -> Unit, onVmid: (String) -> Unit) {
    MenuField(stringResource(R.string.create_node), choices.nodes, nodeName.takeIf { it.isNotEmpty() }, { it }, onNode)
    val taken = vmid.toIntOrNull()?.let { it in choices.takenIds } == true
    WholeNumberField(
        label = stringResource(R.string.create_id),
        value = vmid,
        onValueChange = onVmid,
        min = 100,
        max = 999_999_999,
        error = stringResource(R.string.create_id_range),
        supportingText = if (taken) stringResource(R.string.create_id_taken) else null,
    )
}

@Composable
private fun TextInput(label: String, value: String, onValueChange: (String) -> Unit, error: String? = null, placeholder: String? = null, help: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        isError = error != null,
        supportingText = (error ?: help)?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun StorageField(choices: NodeChoices, selected: String, onSelect: (String) -> Unit) {
    if (choices.diskStorages.isEmpty()) {
        Text(stringResource(R.string.hardware_no_disk_storage), color = MaterialTheme.colorScheme.error)
        return
    }
    val resources = LocalResources.current
    MenuField(
        stringResource(R.string.hardware_storage),
        choices.diskStorages,
        choices.diskStorages.firstOrNull { it.storage == selected },
        { option -> option.avail?.let { resources.getString(R.string.hardware_storage_free, option.storage, formatBytes(it)) } ?: option.storage },
        { onSelect(it.storage) },
    )
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

// --- Virtual machine ------------------------------------------------------

@Composable
private fun VmStepContent(step: VmStep, draft: VmDraft, choices: ClusterChoices, node: Loadable<NodeChoices>, viewModel: CreateGuestViewModel) {
    val update = viewModel::updateVm
    when (step) {
        VmStep.General -> {
            GeneralFields(draft.node, draft.vmid, choices, viewModel::setNode) { id -> update { it.copy(vmid = id) } }
            TextInput(
                stringResource(R.string.setting_name), draft.name, { name -> update { it.copy(name = name) } },
                error = if (isValidGuestName(draft.name)) null else stringResource(R.string.create_name_invalid),
                help = stringResource(R.string.create_name_help),
            )
        }
        VmStep.System -> WithNode(node, viewModel::retryNode) { offered ->
            val osLabels = GuestOs.entries.associateWith { stringResource(it.label) }
            Text(stringResource(R.string.create_os), style = MaterialTheme.typography.titleSmall)
            RadioChoices(GuestOs.entries, draft.os, label = { osLabels.getValue(it) }) { os -> update { it.withOs(os) } }
            if (draft.os == GuestOs.Windows) Text(stringResource(R.string.create_windows_help), style = MaterialTheme.typography.bodyMedium)
            val none = stringResource(R.string.hardware_no_media)
            MenuField(
                stringResource(R.string.create_iso), listOf<String?>(null) + offered.isos.map { it.volid }, draft.iso,
                { it?.substringAfter('/') ?: none }, { iso -> update { it.copy(iso = iso) } },
                supportingText = if (offered.isos.isEmpty()) stringResource(R.string.create_no_isos) else null,
            )
            CheckRow(stringResource(R.string.create_uefi), draft.uefi) { on -> update { it.copy(uefi = on || it.os == GuestOs.Windows) } }
            CheckRow(stringResource(R.string.create_agent), draft.agent) { on -> update { it.copy(agent = on) } }
        }
        VmStep.Disk -> WithNode(node, viewModel::retryNode) { offered ->
            MenuField(stringResource(R.string.hardware_bus), DiskBus.entries, draft.bus, { it.key.uppercase() }, { bus -> update { it.copy(bus = bus) } })
            StorageField(offered, draft.storage) { storage -> update { it.copy(storage = storage) } }
            WholeNumberField(
                stringResource(R.string.hardware_size_gib), draft.diskGiB, { size -> update { it.copy(diskGiB = size) } }, 1, MAX_DISK_GIB,
                error = stringResource(R.string.problem_range, 1, MAX_DISK_GIB),
            )
            if (draft.uefi) Text(stringResource(R.string.create_efi_disk_help), style = MaterialTheme.typography.bodyMedium)
        }
        VmStep.Processor -> {
            WholeNumberField(stringResource(R.string.hardware_sockets), draft.sockets, { v -> update { it.copy(sockets = v) } }, 1, 4, stringResource(R.string.problem_range, 1, 4))
            WholeNumberField(stringResource(R.string.hardware_cores), draft.cores, { v -> update { it.copy(cores = v) } }, 1, 8192, stringResource(R.string.problem_range, 1, 8192))
            SuggestField(stringResource(R.string.hardware_cpu_type), draft.cpuType, HardwareFormats.CPU_TYPES, { v -> update { it.copy(cpuType = v.trim()) } })
            MemoryField(draft.memoryMiB) { v -> update { it.copy(memoryMiB = v) } }
        }
        VmStep.Network -> WithNode(node, viewModel::retryNode) { offered ->
            CheckRow(stringResource(R.string.create_with_network), draft.bridge != null) { on ->
                update { it.copy(bridge = if (on) offered.bridges.firstOrNull() ?: "vmbr0" else null) }
            }
            draft.bridge?.let { bridge ->
                SuggestField(stringResource(R.string.hardware_bridge), bridge, offered.bridges, { v -> update { it.copy(bridge = v.trim()) } })
                VlanField(draft.vlan) { v -> update { it.copy(vlan = v) } }
                CheckRow(stringResource(R.string.hardware_firewall), draft.firewall) { on -> update { it.copy(firewall = on) } }
            }
        }
        VmStep.Confirm -> {
            val none = stringResource(R.string.hardware_no_media)
            SummaryRow(stringResource(R.string.create_node), draft.node)
            SummaryRow(stringResource(R.string.create_id), draft.vmid)
            if (draft.name.isNotBlank()) SummaryRow(stringResource(R.string.setting_name), draft.name.trim())
            SummaryRow(stringResource(R.string.create_os), stringResource(draft.os.label))
            SummaryRow(stringResource(R.string.create_iso), draft.iso?.substringAfter('/') ?: none)
            SummaryRow(stringResource(R.string.create_step_disk), stringResource(R.string.create_disk_summary, draft.diskGiB, draft.storage, draft.bus.key.uppercase()))
            SummaryRow(
                stringResource(R.string.create_step_processor),
                stringResource(R.string.create_cpu_summary, draft.sockets, draft.cores, draft.cpuType, draft.memoryMiB),
            )
            SummaryRow(stringResource(R.string.create_step_network), draft.bridge ?: stringResource(R.string.create_no_network))
            CheckRow(stringResource(R.string.create_start_after), draft.start) { on -> update { it.copy(start = on) } }
        }
    }
}

private val GuestOs.label: Int
    get() = when (this) {
        GuestOs.Linux -> R.string.create_os_linux
        GuestOs.Windows -> R.string.create_os_windows
        GuestOs.Other -> R.string.create_os_other
    }

@Composable
private fun MemoryField(value: String, onValueChange: (String) -> Unit) {
    WholeNumberField(
        stringResource(R.string.hardware_memory_mib), value, onValueChange, 16, MAX_MEMORY_MIB,
        error = stringResource(R.string.problem_range, 16, MAX_MEMORY_MIB),
        supportingText = value.toLongOrNull()?.let { formatBytes(it * 1024 * 1024) },
    )
}

@Composable
private fun VlanField(value: String, onValueChange: (String) -> Unit) {
    WholeNumberField(
        stringResource(R.string.hardware_vlan), value, onValueChange, 1, 4094,
        error = stringResource(R.string.problem_range, 1, 4094),
        supportingText = stringResource(R.string.hardware_vlan_help),
    )
}

// --- Container ------------------------------------------------------------

@Composable
private fun CtStepContent(step: CtStep, draft: CtDraft, choices: ClusterChoices, node: Loadable<NodeChoices>, viewModel: CreateGuestViewModel) {
    val update = viewModel::updateCt
    when (step) {
        CtStep.General -> {
            GeneralFields(draft.node, draft.vmid, choices, viewModel::setNode) { id -> update { it.copy(vmid = id) } }
            TextInput(
                stringResource(R.string.setting_hostname), draft.hostname, { name -> update { it.copy(hostname = name) } },
                error = if (isValidGuestName(draft.hostname)) null else stringResource(R.string.create_name_invalid),
            )
            PasswordInput(stringResource(R.string.create_password), draft.password) { v -> update { it.copy(password = v) } }
            PasswordInput(
                stringResource(R.string.create_password_again), draft.confirmPassword,
                error = if (draft.confirmPassword.isNotEmpty() && draft.confirmPassword != draft.password) stringResource(R.string.create_password_mismatch) else null,
            ) { v -> update { it.copy(confirmPassword = v) } }
            OutlinedTextField(
                value = draft.sshKeys,
                onValueChange = { v -> update { it.copy(sshKeys = v) } },
                label = { Text(stringResource(R.string.setting_sshkeys)) },
                minLines = 2,
                supportingText = { Text(stringResource(R.string.create_credentials_help)) },
                modifier = Modifier.fillMaxWidth(),
            )
            CheckRow(stringResource(R.string.setting_unprivileged), draft.unprivileged) { on -> update { it.copy(unprivileged = on) } }
            CheckRow(stringResource(R.string.create_nesting), draft.nesting) { on -> update { it.copy(nesting = on) } }
        }
        CtStep.Template -> WithNode(node, viewModel::retryNode) { offered ->
            if (offered.templates.isEmpty()) {
                Text(stringResource(R.string.create_no_templates))
            } else {
                RadioChoices(offered.templates.map { it.volid }, draft.template, label = { it.substringAfter('/') }) { template ->
                    update { it.copy(template = template) }
                }
            }
        }
        CtStep.Disk -> WithNode(node, viewModel::retryNode) { offered ->
            StorageField(offered, draft.storage) { storage -> update { it.copy(storage = storage) } }
            WholeNumberField(
                stringResource(R.string.hardware_size_gib), draft.diskGiB, { size -> update { it.copy(diskGiB = size) } }, 1, MAX_DISK_GIB,
                error = stringResource(R.string.problem_range, 1, MAX_DISK_GIB),
            )
        }
        CtStep.Processor -> {
            WholeNumberField(stringResource(R.string.hardware_cores), draft.cores, { v -> update { it.copy(cores = v) } }, 1, 8192, stringResource(R.string.problem_range, 1, 8192))
            MemoryField(draft.memoryMiB) { v -> update { it.copy(memoryMiB = v) } }
            WholeNumberField(
                stringResource(R.string.hardware_swap_mib), draft.swapMiB, { v -> update { it.copy(swapMiB = v) } }, 0, MAX_MEMORY_MIB,
                error = stringResource(R.string.problem_range, 0, MAX_MEMORY_MIB),
            )
        }
        CtStep.Network -> WithNode(node, viewModel::retryNode) { offered ->
            SuggestField(stringResource(R.string.hardware_bridge), draft.bridge, offered.bridges, { v -> update { it.copy(bridge = v.trim()) } })
            VlanField(draft.vlan) { v -> update { it.copy(vlan = v) } }
            CheckRow(stringResource(R.string.hardware_firewall), draft.firewall) { on -> update { it.copy(firewall = on) } }
            Text(stringResource(R.string.hardware_ipv4), style = MaterialTheme.typography.titleSmall)
            val modeLabels = mapOf(
                IpMode.Dhcp to stringResource(R.string.hardware_ip_dhcp),
                IpMode.Static to stringResource(R.string.hardware_ip_static),
                IpMode.None to stringResource(R.string.hardware_ip_none),
            )
            RadioChoices(IpMode.entries, draft.ipMode, label = { modeLabels.getValue(it) }) { mode -> update { it.copy(ipMode = mode) } }
            if (draft.ipMode == IpMode.Static) {
                TextInput(
                    stringResource(R.string.hardware_ip_address), draft.address, { v -> update { it.copy(address = v) } },
                    error = if (draft.address.isNotEmpty() && '/' !in draft.address) stringResource(R.string.hardware_ip_help) else null,
                    placeholder = "192.0.2.20/24",
                )
                TextInput(stringResource(R.string.hardware_gateway), draft.gateway, { v -> update { it.copy(gateway = v) } })
            }
            TextInput(
                stringResource(R.string.setting_nameserver), draft.dnsServers, { v -> update { it.copy(dnsServers = v) } },
                help = stringResource(R.string.create_dns_help),
            )
            TextInput(stringResource(R.string.setting_searchdomain), draft.searchDomain, { v -> update { it.copy(searchDomain = v) } })
        }
        CtStep.Confirm -> {
            SummaryRow(stringResource(R.string.create_node), draft.node)
            SummaryRow(stringResource(R.string.create_id), draft.vmid)
            SummaryRow(stringResource(R.string.setting_hostname), draft.hostname.trim())
            SummaryRow(stringResource(R.string.create_step_template), draft.template.substringAfter('/'))
            SummaryRow(stringResource(R.string.create_step_disk), stringResource(R.string.create_root_summary, draft.diskGiB, draft.storage))
            SummaryRow(stringResource(R.string.create_step_processor), stringResource(R.string.create_ct_cpu_summary, draft.cores, draft.memoryMiB, draft.swapMiB))
            val address = when (draft.ipMode) {
                IpMode.Dhcp -> stringResource(R.string.hardware_ip_dhcp)
                IpMode.Static -> draft.address
                IpMode.None -> stringResource(R.string.hardware_ip_none)
            }
            SummaryRow(stringResource(R.string.create_step_network), "${draft.bridge} · $address")
            CheckRow(stringResource(R.string.create_start_after), draft.start) { on -> update { it.copy(start = on) } }
        }
    }
}

@Composable
private fun PasswordInput(label: String, value: String, error: String? = null, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val MAX_DISK_GIB = 128L * 1024
private const val MAX_MEMORY_MIB = 64L * 1024 * 1024
