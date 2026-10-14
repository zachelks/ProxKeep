package io.github.zachelks.proxkeep.ui.lifecycle

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.data.LifecycleEvent
import io.github.zachelks.proxkeep.data.LifecycleOperation
import io.github.zachelks.proxkeep.ui.appContainer

/** The lifecycle view model for one guest, shared by its screens and dialogs within one back stack entry. */
@Composable
fun lifecycleViewModel(resourceId: String): LifecycleViewModel {
    val container = appContainer()
    return viewModel(key = "lifecycle:$resourceId") {
        LifecycleViewModel(container.repository, container.cluster, container.guestLifecycle, container.refresh, resourceId)
    }
}

private class OperationText(@StringRes val progress: Int, @StringRes val done: Int, @StringRes val failed: Int)

private fun LifecycleOperation.text(): OperationText = when (this) {
    LifecycleOperation.Create -> OperationText(R.string.progress_create, R.string.done_create, R.string.failed_create)
    LifecycleOperation.TakeSnapshot -> OperationText(R.string.progress_snapshot, R.string.done_snapshot, R.string.failed_snapshot)
    LifecycleOperation.RollBack -> OperationText(R.string.progress_rollback, R.string.done_rollback, R.string.failed_rollback)
    LifecycleOperation.DeleteSnapshot -> OperationText(R.string.progress_delete_snapshot, R.string.done_delete_snapshot, R.string.failed_delete_snapshot)
    LifecycleOperation.Backup -> OperationText(R.string.progress_backup, R.string.done_backup, R.string.failed_backup)
    LifecycleOperation.Restore -> OperationText(R.string.progress_restore, R.string.done_restore, R.string.failed_restore)
    LifecycleOperation.Migrate -> OperationText(R.string.progress_migrate, R.string.done_migrate, R.string.failed_migrate)
    LifecycleOperation.Clone -> OperationText(R.string.progress_clone, R.string.done_clone, R.string.failed_clone)
    LifecycleOperation.ConvertToTemplate -> OperationText(R.string.progress_template, R.string.done_template, R.string.failed_template)
    LifecycleOperation.Delete -> OperationText(R.string.progress_delete_guest, R.string.done_delete_guest, R.string.failed_delete_guest)
}

@get:StringRes
val LifecycleOperation.progressLabel: Int
    get() = text().progress

/** One line saying how an operation ended, with the server's reason when it failed. */
fun LifecycleEvent.message(resources: Resources): String {
    val text = operation.text()
    if (succeeded) return resources.getString(text.done, guestName)
    val summary = resources.getString(text.failed, guestName)
    val reason = detail?.trim().orEmpty()
    return if (reason.isEmpty()) summary else "$summary\n$reason"
}
