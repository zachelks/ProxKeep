@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.zachelks.proxkeep.ui.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.ChecksumAlgorithm
import io.github.zachelks.proxkeep.core.api.model.Privilege
import io.github.zachelks.proxkeep.core.api.model.StorageVolume
import io.github.zachelks.proxkeep.data.Transfer
import io.github.zachelks.proxkeep.ui.appContainer
import io.github.zachelks.proxkeep.ui.components.CenteredProgress
import io.github.zachelks.proxkeep.ui.components.CheckRow
import io.github.zachelks.proxkeep.ui.components.MenuField
import io.github.zachelks.proxkeep.ui.components.MessageWithAction
import io.github.zachelks.proxkeep.ui.components.RadioChoices
import io.github.zachelks.proxkeep.ui.components.UsageBar
import io.github.zachelks.proxkeep.ui.format.formatBytes
import io.github.zachelks.proxkeep.ui.format.usageFraction
import io.github.zachelks.proxkeep.ui.lifecycle.ConfirmDialog
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import io.github.zachelks.proxkeep.ui.navigation.Route
import io.github.zachelks.proxkeep.ui.text

@Composable
fun storageViewModel(node: String, storage: String): StorageViewModel {
    val container = appContainer()
    return viewModel(key = "storage:$node:$storage") {
        StorageViewModel(container.repository, container.cluster, container.transfers, container.refresh, node, storage)
    }
}

/** What the add button is doing. */
private sealed interface AddStep {
    data class ChooseContent(val uri: Uri, val name: String, val size: Long) : AddStep
    data object FromUrl : AddStep
    data object Templates : AddStep
}

/** A storage on one node: what is on it, deleting volumes, and adding files by upload, URL or the template catalog. */
@Composable
fun StorageScreen(
    node: String,
    storage: String,
    onClose: () -> Unit,
    onOpenRoute: (Route) -> Unit,
    viewModel: StorageViewModel = storageViewModel(node, storage),
) {
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val transfers by viewModel.activeTransfers.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val access by appContainer().access.access.collectAsStateWithLifecycle()
    val paths = listOf("/storage/$storage")
    val canAllocate = access?.allows(paths, Privilege.DATASTORE_ALLOCATE_SPACE) ?: true
    val canTemplate = access?.allows(paths, Privilege.DATASTORE_ALLOCATE_TEMPLATE) ?: true
    val canConfigure = access?.allows(paths, Privilege.DATASTORE_ALLOCATE) ?: true
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<StorageVolume?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf<AddStep?>(null) }
    val loaded = (overview as? Loadable.Loaded)?.value
    val transferable = loaded?.status?.contentTypes.orEmpty().filter { it in StorageOverview.TRANSFERABLE }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val base = resources.getString(message.id)
            snackbar.showSnackbar(if (message.detail.isNullOrBlank()) base else "$base\n${message.detail.trim()}")
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val (name, size) = describe(context, uri)
            if (size != null) step = AddStep.ChooseContent(uri, name, size)
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.storage_title, storage, node)) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        if (canConfigure) {
                            val title = stringResource(R.string.storage_configure_title, storage)
                            TextButton(onClick = {
                                onOpenRoute(Route.SchemaEdit(title, "/storage/$storage", "/storage/{storage}", "PUT", exclude = listOf("type")))
                            }) { Text(stringResource(R.string.action_configure)) }
                        }
                        IconButton(onClick = viewModel::retry) {
                            Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.action_refresh))
                        }
                    },
                )
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        floatingActionButton = {
            if (canTemplate && transferable.isNotEmpty()) {
                Box {
                    ExtendedFloatingActionButton(
                        onClick = { addMenu = true },
                        text = { Text(stringResource(R.string.action_add)) },
                        icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                    )
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.storage_upload)) }, onClick = {
                            addMenu = false
                            picker.launch(arrayOf("*/*"))
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.storage_from_url)) }, onClick = {
                            addMenu = false
                            step = AddStep.FromUrl
                        })
                        if ("vztmpl" in transferable) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.storage_templates)) }, onClick = {
                                addMenu = false
                                viewModel.loadTemplates()
                                step = AddStep.Templates
                            })
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        when (val state = overview) {
            Loadable.Loading -> CenteredProgress(modifier = Modifier.padding(innerPadding))
            is Loadable.Failed -> MessageWithAction(state.message.text(), Modifier.padding(innerPadding), stringResource(R.string.action_retry), viewModel::retry)
            is Loadable.Loaded -> {
                val value = state.value
                val shown = value.volumes.filter { filter == null || it.content == filter }
                LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding), contentPadding = PaddingValues(bottom = 96.dp)) {
                    item(key = "usage") {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(listOfNotNull(value.status.type, if (value.status.shared == true) stringResource(R.string.storage_shared) else null).joinToString(" · "))
                            val used = value.status.total?.let { total -> value.status.avail?.let { total - it } }
                            usageFraction(used, value.status.total)?.let { fraction ->
                                UsageBar(
                                    label = stringResource(R.string.usage_space),
                                    fraction = fraction,
                                    detail = stringResource(R.string.used_of_total, formatBytes(used ?: 0), formatBytes(value.status.total ?: 0)),
                                )
                            }
                        }
                    }
                    if (transfers.isNotEmpty()) {
                        items(transfers, key = { "transfer:${it.id}" }) { TransferRow(it) }
                    }
                    item(key = "filters") {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text(stringResource(R.string.storage_all)) })
                            value.contentTypes.forEach { type ->
                                FilterChip(selected = filter == type, onClick = { filter = type }, label = { Text(stringResource(contentLabel(type))) })
                            }
                        }
                    }
                    if (shown.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                stringResource(R.string.storage_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    items(shown, key = { it.volid }) { volume ->
                        VolumeRow(volume, canDelete = canAllocate && !busy) { confirmDelete = volume }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    confirmDelete?.let { volume ->
        ConfirmDialog(
            title = stringResource(R.string.confirm_delete_volume_title, StorageViewModel.displayName(volume.volid)),
            body = stringResource(R.string.confirm_delete_volume_body),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = {
                confirmDelete = null
                viewModel.deleteVolume(volume)
            },
            onDismiss = { confirmDelete = null },
        )
    }
    when (val current = step) {
        null -> Unit
        is AddStep.ChooseContent -> UploadDialog(current, transferable, onDismiss = { step = null }) { content ->
            step = null
            viewModel.upload(content, current.name, current.size) {
                context.contentResolver.openInputStream(current.uri) ?: throw java.io.FileNotFoundException(current.uri.toString())
            }
        }
        AddStep.FromUrl -> UrlDialog(viewModel, transferable, onDismiss = {
            step = null
            viewModel.clearUrlCheck()
        }) { step = null }
        AddStep.Templates -> TemplatesDialog(viewModel, onDismiss = { step = null })
    }
}

/** A document's display name and size, as the system's file picker reports them. */
private fun describe(context: Context, uri: Uri): Pair<String, Long?> {
    var name = uri.lastPathSegment ?: "upload"
    var size: Long? = null
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getString(0)?.let { name = it }
            if (!cursor.isNull(1)) size = cursor.getLong(1)
        }
    }
    return name to size
}

fun contentLabel(type: String): Int = when (type) {
    "iso" -> R.string.content_iso
    "vztmpl" -> R.string.content_vztmpl
    "backup" -> R.string.content_backup
    "images" -> R.string.content_images
    "rootdir" -> R.string.content_rootdir
    "import" -> R.string.content_import
    "snippets" -> R.string.content_snippets
    else -> R.string.content_other
}

@Composable
private fun TransferRow(transfer: Transfer) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val fraction = transfer.fraction
        Text(
            if (fraction != null) {
                stringResource(R.string.storage_uploading, transfer.fileName, formatBytes(transfer.sent), formatBytes(transfer.total ?: 0))
            } else {
                stringResource(R.string.storage_storing, transfer.fileName)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun VolumeRow(volume: StorageVolume, canDelete: Boolean, onDelete: () -> Unit) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val name = StorageViewModel.displayName(volume.volid)
    ListItem(
        headlineContent = { Text(name) },
        overlineContent = volume.content?.let { { Text(stringResource(contentLabel(it))) } },
        supportingContent = {
            val parts = listOfNotNull(
                volume.size?.let(::formatBytes),
                volume.ctime?.let { DateUtils.formatDateTime(context, it * 1000L, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME) },
                volume.vmid?.let { stringResource(R.string.storage_volume_guest, it) },
                stringResource(R.string.storage_protected).takeIf { volume.protected == true },
            )
            Column {
                Text(parts.joinToString(" · "))
                volume.notes?.takeIf { it.isNotBlank() }?.let { Text(it.trim(), style = MaterialTheme.typography.bodySmall) }
            }
        },
        trailingContent = if (canDelete && volume.protected != true) {
            {
                Box {
                    IconButton(onClick = { expanded = true }) {
                        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more_for, name))
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = {
                            expanded = false
                            onDelete()
                        })
                    }
                }
            }
        } else null,
    )
}

/** Picks what kind of content an uploaded file is, guessing from its name. */
@Composable
private fun UploadDialog(file: AddStep.ChooseContent, allowed: List<String>, onDismiss: () -> Unit, onUpload: (String) -> Unit) {
    val guess = allowed.firstOrNull { StorageViewModel.isAllowedFileName(file.name, it) } ?: allowed.first()
    var content by rememberSaveable { mutableStateOf(guess) }
    val labels = allowed.associateWith { stringResource(contentLabel(it)) }
    val valid = StorageViewModel.isAllowedFileName(file.name, content)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.storage_upload_title, file.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(formatBytes(file.size))
                if (allowed.size > 1) RadioChoices(allowed, content, label = { labels.getValue(it) }) { content = it }
                if (!valid) Text(stringResource(R.string.storage_wrong_extension), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { onUpload(content) }, enabled = valid) { Text(stringResource(R.string.action_upload)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun UrlDialog(viewModel: StorageViewModel, allowed: List<String>, onDismiss: () -> Unit, onStarted: () -> Unit) {
    val check by viewModel.urlCheck.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf("") }
    var fileName by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf(allowed.first()) }
    var verify by rememberSaveable { mutableStateOf(true) }
    var algorithm by rememberSaveable { mutableStateOf<ChecksumAlgorithm?>(null) }
    var checksum by rememberSaveable { mutableStateOf("") }
    val labels = allowed.associateWith { stringResource(contentLabel(it)) }
    val none = stringResource(R.string.storage_no_checksum)

    LaunchedEffect(check) {
        val name = ((check as? Loadable.Loaded)?.value?.filename)
        if (name != null && fileName.isEmpty()) fileName = name
    }
    val checked = check is Loadable.Loaded
    val valid = checked && StorageViewModel.isAllowedFileName(fileName, content) && (algorithm == null || checksum.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.storage_from_url)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(url, { url = it; viewModel.clearUrlCheck() }, singleLine = true, label = { Text(stringResource(R.string.storage_url)) }, modifier = Modifier.fillMaxWidth())
                CheckRow(stringResource(R.string.storage_verify_certificates), verify) { verify = it; viewModel.clearUrlCheck() }
                when (val state = check) {
                    null -> TextButton(onClick = { viewModel.checkUrl(url, verify) }, enabled = url.startsWith("http")) { Text(stringResource(R.string.action_check_url)) }
                    Loadable.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    is Loadable.Failed -> Text(state.message.text(), color = MaterialTheme.colorScheme.error)
                    is Loadable.Loaded -> state.value.size?.let { Text(stringResource(R.string.storage_url_size, formatBytes(it))) }
                }
                if (checked) {
                    OutlinedTextField(fileName, { fileName = it }, singleLine = true, label = { Text(stringResource(R.string.storage_file_name)) }, modifier = Modifier.fillMaxWidth())
                    if (allowed.size > 1) RadioChoices(allowed, content, label = { labels.getValue(it) }) { content = it }
                    MenuField(
                        stringResource(R.string.storage_checksum), listOf<ChecksumAlgorithm?>(null) + ChecksumAlgorithm.entries, algorithm,
                        { it?.name?.uppercase() ?: none }, { algorithm = it },
                    )
                    if (algorithm != null) {
                        OutlinedTextField(checksum, { checksum = it.trim() }, singleLine = true, label = { Text(stringResource(R.string.storage_checksum_value)) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (viewModel.download(content, url, fileName, algorithm?.let { it to checksum }, verify)) onStarted()
            }, enabled = valid) { Text(stringResource(R.string.action_download)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun TemplatesDialog(viewModel: StorageViewModel, onDismiss: () -> Unit) {
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.storage_templates)) },
        text = {
            when (val state = templates) {
                null, Loadable.Loading -> CircularProgressIndicator()
                is Loadable.Failed -> Text(state.message.text(), color = MaterialTheme.colorScheme.error)
                is Loadable.Loaded -> LazyColumn {
                    items(state.value, key = { it.template }) { template ->
                        ListItem(
                            headlineContent = { Text(template.headline ?: template.template) },
                            supportingContent = { Text(listOfNotNull(template.section, template.version).joinToString(" · ")) },
                            trailingContent = {
                                TextButton(onClick = { if (viewModel.downloadTemplate(template.template)) onDismiss() }) {
                                    Text(stringResource(R.string.action_download))
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
