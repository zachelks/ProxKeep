package io.github.zachelks.proxkeep.data

import io.github.zachelks.proxkeep.core.api.PveApiException
import io.github.zachelks.proxkeep.core.api.PveClient
import io.github.zachelks.proxkeep.core.api.awaitTask
import io.github.zachelks.proxkeep.core.api.model.ChecksumAlgorithm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * A file on its way to a storage.
 *
 * @property sent bytes uploaded so far; for a download the node makes itself, 0
 * @property total the file's size, or null when not known
 * @property storing whether the bytes have arrived and the node is storing or fetching the file
 */
data class Transfer(
    val id: Long,
    val profileId: String,
    val node: String,
    val storage: String,
    val fileName: String,
    val sent: Long = 0,
    val total: Long? = null,
    val storing: Boolean = false,
) {
    val fraction: Float?
        get() = total?.takeIf { it > 0 && !storing }?.let { (sent.toDouble() / it).toFloat().coerceIn(0f, 1f) }
}

/** How a transfer ended. */
data class TransferEvent(val fileName: String, val storage: String, val succeeded: Boolean, val detail: String? = null)

/**
 * Uploads and downloads onto storages, run at app level so they carry on
 * when the screen that started them closes, like [GuestLifecycle] does for
 * guests. They end with the app's process; a foreground service would keep
 * them going beyond that.
 */
class StorageTransfers(
    private val servers: ServerRepository,
    private val refresh: RefreshSignal,
    private val scope: CoroutineScope,
) {
    private val ids = AtomicLong()

    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = _transfers.asStateFlow()

    private val _events = MutableSharedFlow<TransferEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<TransferEvent> = _events.asSharedFlow()

    /**
     * Uploads a file from the phone.
     *
     * @param content `iso`, `vztmpl` or `import`
     * @param open opens the file; it is read once, on a network thread
     */
    fun upload(profileId: String, node: String, storage: String, content: String, fileName: String, length: Long, open: () -> InputStream) {
        val transfer = Transfer(ids.incrementAndGet(), profileId, node, storage, fileName, total = length)
        start(transfer) { client ->
            var reported = 0L
            client.upload(node, storage, content, fileName, length, open) { sent, total ->
                // A change every percent is plenty for a progress bar.
                if (sent - reported >= total / PROGRESS_STEPS || sent == total) {
                    reported = sent
                    update(transfer.id) { it.copy(sent = sent, storing = sent == total) }
                }
            }
        }
    }

    /** Has the node fetch a file from a URL itself. */
    fun download(
        profileId: String,
        node: String,
        storage: String,
        content: String,
        url: String,
        fileName: String,
        checksum: Pair<ChecksumAlgorithm, String>?,
        verifyCertificates: Boolean,
        size: Long?,
    ) {
        val transfer = Transfer(ids.incrementAndGet(), profileId, node, storage, fileName, total = size, storing = true)
        start(transfer) { client -> client.downloadUrl(node, storage, content, url, fileName, checksum, verifyCertificates) }
    }

    /** Downloads a container template from the Proxmox catalog. */
    fun downloadTemplate(profileId: String, node: String, storage: String, template: String) {
        val transfer = Transfer(ids.incrementAndGet(), profileId, node, storage, template, storing = true)
        start(transfer) { client -> client.downloadTemplate(node, storage, template) }
    }

    private fun start(transfer: Transfer, begin: suspend (PveClient) -> String?) {
        _transfers.update { it + transfer }
        scope.launch {
            val event = try {
                val outcome = servers.request { client ->
                    val upid = begin(client) ?: return@request null
                    update(transfer.id) { it.copy(storing = true) }
                    client.awaitTask(transfer.node, upid, pollIntervalMs = POLL_MS)
                }
                val succeeded = outcome?.succeeded ?: true
                TransferEvent(transfer.fileName, transfer.storage, succeeded, outcome?.exitstatus.takeUnless { succeeded })
            } catch (e: PveApiException) {
                TransferEvent(transfer.fileName, transfer.storage, false, e.statusMessage)
            } catch (e: IOException) {
                TransferEvent(transfer.fileName, transfer.storage, false, e.message)
            } finally {
                _transfers.update { list -> list.filterNot { it.id == transfer.id } }
            }
            _events.emit(event)
            refresh.request()
        }
    }

    private fun update(id: Long, change: (Transfer) -> Transfer) {
        _transfers.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private companion object {
        const val EVENT_BUFFER = 8
        const val PROGRESS_STEPS = 100
        const val POLL_MS = 2_000L
    }
}
