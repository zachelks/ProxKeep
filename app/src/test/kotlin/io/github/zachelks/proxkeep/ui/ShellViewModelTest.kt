package io.github.zachelks.proxkeep.ui

import androidx.lifecycle.viewModelScope
import io.github.zachelks.proxkeep.MainDispatcherRule
import io.github.zachelks.proxkeep.data.AuthMethod
import io.github.zachelks.proxkeep.data.GuestActions
import io.github.zachelks.proxkeep.data.MemoryProfileStorage
import io.github.zachelks.proxkeep.data.PinnedServer
import io.github.zachelks.proxkeep.data.PlainSecrets
import io.github.zachelks.proxkeep.data.ProfileStorage
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.ServerRepository
import io.github.zachelks.proxkeep.data.Session
import io.github.zachelks.proxkeep.data.StoredServers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Rule
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ShellViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val fixture = PinnedServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refresh = RefreshSignal()
    private val other = fixture.profile.copy(id = "other", name = "Other", authMethod = AuthMethod.Password, principal = "root@pam")
    private lateinit var viewModel: ShellViewModel

    @AfterTest
    fun tearDown() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        scope.cancel()
        fixture.close()
    }

    @Test
    fun theServerMenuSwitchesAndRemovesServers(): Unit = runBlocking {
        val repository = repositoryWith(MemoryProfileStorage(StoredServers(listOf(tokenProfile(), other), fixture.profile.id)))
        viewModel = ShellViewModel(repository, GuestActions(repository, refresh, scope), refresh)

        viewModel.activate(other.id)
        withTimeout(TIMEOUT_MS) { viewModel.session.first { it == Session.SignedOut(other) } }

        viewModel.remove(other.id)
        val next = withTimeout(TIMEOUT_MS) { viewModel.session.first { it is Session.Active } }
        assertEquals(fixture.profile.id, assertIs<Session.Active>(next).profile.id, "the remaining server is selected and signed in")
        assertEquals(listOf(fixture.profile.id), viewModel.servers.value.profiles.map { it.id })
    }

    @Test
    fun signingOutKeepsTheServerListed(): Unit = runBlocking {
        val repository = repositoryWith(MemoryProfileStorage(StoredServers(listOf(tokenProfile()), fixture.profile.id)))
        viewModel = ShellViewModel(repository, GuestActions(repository, refresh, scope), refresh)

        viewModel.signOut()

        withTimeout(TIMEOUT_MS) { viewModel.session.first { it is Session.SignedOut } }
        assertEquals(1, viewModel.servers.value.profiles.size)
    }

    @Test
    fun aChangeThatCannotBeSavedChangesNothing(): Unit = runBlocking {
        val failing = object : ProfileStorage {
            override suspend fun read() = StoredServers(listOf(tokenProfile(), other), fixture.profile.id)

            override suspend fun write(state: StoredServers) = throw IOException("disk full")
        }
        val repository = repositoryWith(failing)
        viewModel = ShellViewModel(repository, GuestActions(repository, refresh, scope), refresh)
        val before = viewModel.session.value

        viewModel.activate(other.id)
        viewModel.remove(fixture.profile.id)
        delay(200)

        assertEquals(before, viewModel.session.value)
        assertEquals(2, viewModel.servers.value.profiles.size)
    }

    @Test
    fun refreshReloadsEverythingOnScreen(): Unit = runBlocking {
        val repository = repositoryWith(MemoryProfileStorage())
        viewModel = ShellViewModel(repository, GuestActions(repository, refresh, scope), refresh)
        val ticks = async(start = CoroutineStart.UNDISPATCHED) { refresh.ticks(intervalMs = 60_000).take(2).toList() }
        delay(100)

        viewModel.refresh()

        assertEquals(2, withTimeout(TIMEOUT_MS) { ticks.await() }.size)
    }

    private fun tokenProfile() = fixture.profile.copy(encryptedSecret = "plain:secret")

    private suspend fun repositoryWith(storage: ProfileStorage): ServerRepository {
        val repository = ServerRepository(storage, PlainSecrets(), OkHttpClient(), scope)
        repository.loaded.first { it }
        return repository
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
