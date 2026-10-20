package io.github.zachelks.proxkeep.ui.datacenter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.PveClient
import io.github.zachelks.proxkeep.core.api.access.TfaEntry
import io.github.zachelks.proxkeep.core.api.access.Totp
import io.github.zachelks.proxkeep.core.api.access.UserTfa
import io.github.zachelks.proxkeep.core.api.access.addRecoveryKeys
import io.github.zachelks.proxkeep.core.api.access.addTotp
import io.github.zachelks.proxkeep.core.api.access.deleteTfa
import io.github.zachelks.proxkeep.core.api.access.setTfaEnabled
import io.github.zachelks.proxkeep.core.api.access.tfaUsers
import io.github.zachelks.proxkeep.core.api.access.unlockTfa
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.ServerRepository
import io.github.zachelks.proxkeep.data.Session
import io.github.zachelks.proxkeep.ui.UiMessage
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import io.github.zachelks.proxkeep.ui.toUiMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

/** What changes one second factor. */
enum class EntryChange { Enable, Disable, Delete }

/**
 * The dialog open on the two-factor screen. It lives in the view model, so
 * a new authenticator's secret survives a rotation and a dialog closes only
 * once its change has succeeded.
 */
sealed interface TfaDialog {
    val userid: String

    data class AddTotp(override val userid: String, val totp: Totp) : TfaDialog

    data class AddRecovery(override val userid: String) : TfaDialog

    /** New recovery keys, shown only this once. */
    data class RecoveryKeys(override val userid: String, val keys: List<String>) : TfaDialog

    data class Change(override val userid: String, val entry: TfaEntry, val change: EntryChange) : TfaDialog
}

/** Every user's second factors: adding authenticator apps and recovery keys, disabling, deleting and unlocking. */
class TwoFactorViewModel(
    private val servers: ServerRepository,
    private val refreshSignal: RefreshSignal,
) : ViewModel() {

    private val profile = (servers.session.value as? Session.Active)?.profile

    /** The signed-in user, without an API token's name. */
    val signedInUser: String? = profile?.principal?.substringBefore('!')

    /** The server asks everyone but `root@pam` for their own password to change second factors. */
    val needsPassword: Boolean = signedInUser != ROOT

    /** How an authenticator app lists this server: the name the person gave it. */
    val issuer: String = profile?.name ?: "ProxKeep"

    /** The users and their second factors; the signed-in user is listed even without any. */
    val users: StateFlow<Loadable<List<UserTfa>>> = flow {
        var current: Loadable<List<UserTfa>> = Loadable.Loading
        emit(current)
        refreshSignal.ticks().collect {
            current = try {
                Loadable.Loaded(withSignedInUser(servers.request { it.tfaUsers() }))
            } catch (e: IOException) {
                val previous = current
                if (previous is Loadable.Loaded) previous.copy(refreshError = e.toUiMessage()) else Loadable.Failed(e.toUiMessage())
            }
            emit(current)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), Loadable.Loading)

    private fun withSignedInUser(users: List<UserTfa>): List<UserTfa> {
        val own = signedInUser?.takeIf { user -> users.none { it.userid == user } }?.let { UserTfa(it) }
        return (users + listOfNotNull(own)).sortedBy { it.userid }
    }

    private val _dialog = MutableStateFlow<TfaDialog?>(null)
    val dialog: StateFlow<TfaDialog?> = _dialog.asStateFlow()

    /** Why the open dialog's change failed, shown in the dialog. */
    private val _dialogError = MutableStateFlow<UiMessage?>(null)
    val dialogError: StateFlow<UiMessage?> = _dialogError.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = MESSAGE_BUFFER)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    fun retry() = refreshSignal.request()

    fun addTotp(userid: String) = open(TfaDialog.AddTotp(userid, Totp.generate()))

    fun addRecoveryKeys(userid: String) = open(TfaDialog.AddRecovery(userid))

    fun change(userid: String, entry: TfaEntry, change: EntryChange) = open(TfaDialog.Change(userid, entry, change))

    private fun open(dialog: TfaDialog) {
        _dialogError.value = null
        _dialog.value = dialog
    }

    fun dismiss() {
        if (_busy.value) return
        _dialogError.value = null
        _dialog.value = null
    }

    /** Adds the open dialog's authenticator once the server accepts [code] from it. */
    fun confirmTotp(description: String, code: String, password: String) {
        val dialog = _dialog.value as? TfaDialog.AddTotp ?: return
        run(R.string.tfa_added) { client ->
            client.addTotp(dialog.userid, description, dialog.totp.uri(issuer, dialog.userid), code.trim(), password.takeIf { needsPassword })
            null
        }
    }

    fun confirmRecoveryKeys(description: String, password: String) {
        val dialog = _dialog.value as? TfaDialog.AddRecovery ?: return
        run(done = null) { client ->
            val keys = client.addRecoveryKeys(dialog.userid, description, password.takeIf { needsPassword })
            TfaDialog.RecoveryKeys(dialog.userid, keys)
        }
    }

    fun confirmChange(password: String) {
        val dialog = _dialog.value as? TfaDialog.Change ?: return
        val secret = password.takeIf { needsPassword }
        val done = when (dialog.change) {
            EntryChange.Enable -> R.string.tfa_enabled
            EntryChange.Disable -> R.string.tfa_disabled
            EntryChange.Delete -> R.string.tfa_deleted
        }
        run(done) { client ->
            when (dialog.change) {
                EntryChange.Enable -> client.setTfaEnabled(dialog.userid, dialog.entry.id, enable = true, secret)
                EntryChange.Disable -> client.setTfaEnabled(dialog.userid, dialog.entry.id, enable = false, secret)
                EntryChange.Delete -> client.deleteTfa(dialog.userid, dialog.entry.id, secret)
            }
            null
        }
    }

    fun unlock(userid: String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                servers.request { it.unlockTfa(userid) }
                _messages.emit(UiMessage(R.string.tfa_unlocked))
                refreshSignal.request()
            } catch (e: IOException) {
                _messages.emit(e.toUiMessage())
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Runs the open dialog's change. On success the dialog closes, or moves
     * on to what [block] returns, and [done] is reported; on failure the
     * dialog stays open and says why.
     */
    private fun run(done: Int?, block: suspend (PveClient) -> TfaDialog?) {
        if (_busy.value) return
        _busy.value = true
        _dialogError.value = null
        viewModelScope.launch {
            try {
                val next = servers.request { block(it) }
                // Not busy before the next dialog shows, so it can be closed at once.
                _busy.value = false
                _dialog.value = next
                done?.let { _messages.emit(UiMessage(it)) }
                refreshSignal.request()
            } catch (e: IOException) {
                _dialogError.value = e.toUiMessage()
            } finally {
                _busy.value = false
            }
        }
    }

    private companion object {
        const val ROOT = "root@pam"
        const val STOP_TIMEOUT_MS = 5_000L
        const val MESSAGE_BUFFER = 4
    }
}
