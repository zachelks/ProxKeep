package io.github.zachelks.proxkeep.data

import io.github.zachelks.proxkeep.core.api.PveApiException
import io.github.zachelks.proxkeep.core.api.auth.ApiTokenCredentials
import io.github.zachelks.proxkeep.core.api.auth.TicketCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class ServerRepositoryTest {

    private val fixture = PinnedServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        fixture.close()
    }

    @Test
    fun rejectedCredentialsSignOutOfTheServer(): Unit = runBlocking {
        val repository = fixture.signedInRepository(scope)
        fixture.enqueue("""{"data":null}""", code = 401, reason = "authentication failure")

        assertFailsWith<PveApiException> { repository.request { it.version() } }

        val session = assertIs<Session.SignedOut>(repository.session.value)
        assertEquals(fixture.profile.id, session.profile.id)
        assertEquals(null, repository.activeProfileId.first())
    }

    @Test
    fun otherErrorsKeepTheSession(): Unit = runBlocking {
        val repository = fixture.signedInRepository(scope)
        fixture.enqueue("""{"data":null}""", code = 500, reason = "Internal error")

        assertFailsWith<PveApiException> { repository.request { it.version() } }

        assertIs<Session.Active>(repository.session.value)
        assertEquals(fixture.profile.id, repository.activeProfileId.first())
    }

    @Test
    fun requestsNeedASignedInServer(): Unit = runBlocking {
        val repository = ServerRepository(MemoryProfileStorage(), PlainSecrets(), OkHttpClient(), scope)
        repository.loaded.first { it }

        assertFailsWith<NotSignedInException> { repository.request { it.version() } }
        assertEquals(0, fixture.server.requestCount)
    }

    @Test
    fun requestsSendTheSessionsCredentials(): Unit = runBlocking {
        val repository = fixture.signedInRepository(scope)
        fixture.enqueue("""{"data":{"version":"9.2.1"}}""")

        assertEquals(9, repository.request { it.version() }.major)
        assertEquals("PVEAPIToken=root@pam!test=secret", fixture.server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun aSavedTokenSessionIsRestoredOnStart(): Unit = runBlocking {
        val stored = fixture.profile.copy(encryptedSecret = "plain:secret")
        val repository = startedRepository(MemoryProfileStorage(StoredServers(listOf(stored), stored.id)))
        fixture.enqueue("""{"data":{"version":"9.2.1"}}""")

        assertIs<Session.Active>(repository.session.value)
        repository.request { it.version() }
        assertEquals("PVEAPIToken=root@pam!test=secret", fixture.server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun aPasswordServerStartsSignedOut(): Unit = runBlocking {
        val stored = fixture.profile.copy(authMethod = AuthMethod.Password, principal = "root@pam")
        val repository = startedRepository(MemoryProfileStorage(StoredServers(listOf(stored), stored.id)))

        assertEquals(Session.SignedOut(stored), repository.session.value)
    }

    @Test
    fun aSecretThatCannotBeDecryptedStartsSignedOut(): Unit = runBlocking {
        val stored = fixture.profile.copy(encryptedSecret = "from another device")
        val locked = object : SecretCipher by PlainSecrets() {
            override fun decrypt(encoded: String): String? = null
        }
        val repository = ServerRepository(MemoryProfileStorage(StoredServers(listOf(stored), stored.id)), locked, OkHttpClient(), scope)
        repository.loaded.first { it }

        assertEquals(Session.SignedOut(stored), repository.session.value)
    }

    @Test
    fun nothingSavedMeansNoSession(): Unit = runBlocking {
        assertEquals(Session.None, startedRepository(MemoryProfileStorage()).session.value)
    }

    @Test
    fun savingAServerStoresTheTokenSecretEncrypted(): Unit = runBlocking {
        val storage = MemoryProfileStorage()
        val repository = fixture.signedInRepository(scope, storage)

        val saved = storage.read()
        assertEquals(fixture.profile.id, saved.activeId)
        assertEquals("plain:secret", saved.profiles.single().encryptedSecret)
        assertEquals(saved, repository.servers.value)
    }

    @Test
    fun aPasswordSignInStoresNoSecret(): Unit = runBlocking {
        val storage = MemoryProfileStorage()
        val repository = startedRepository(storage)
        val profile = fixture.profile.copy(authMethod = AuthMethod.Password, principal = "root@pam", encryptedSecret = "stale")

        repository.saveAndActivate(profile, TicketCredentials("root@pam", "PVE:root@pam:TICKET", "csrf"))

        assertNull(storage.read().profiles.single().encryptedSecret)
        assertIs<Session.Active>(repository.session.value)
    }

    @Test
    fun savingAServerAgainReplacesIt(): Unit = runBlocking {
        val repository = fixture.signedInRepository(scope)

        repository.saveAndActivate(fixture.profile.copy(name = "Renamed"), ApiTokenCredentials(fixture.profile.principal, "new"))

        assertEquals(listOf("Renamed"), repository.servers.value.profiles.map { it.name })
        assertEquals("plain:new", repository.servers.value.profiles.single().encryptedSecret)
    }

    @Test
    fun aSecretTheDeviceCannotEncryptIsNotSaved(): Unit = runBlocking {
        val storage = MemoryProfileStorage()
        val broken = object : SecretCipher by PlainSecrets() {
            override fun encrypt(plainText: String): String = throw GeneralSecurityException("no keystore")
        }
        val repository = ServerRepository(storage, broken, OkHttpClient(), scope)
        repository.loaded.first { it }

        assertFailsWith<IOException> {
            repository.saveAndActivate(fixture.profile, ApiTokenCredentials(fixture.profile.principal, "secret"))
        }
        assertEquals(StoredServers(), storage.read())
        assertEquals(Session.None, repository.session.value)
    }

    @Test
    fun activatingAnotherServerSwitchesTheSession(): Unit = runBlocking {
        val other = signedOutProfile("other")
        val storage = MemoryProfileStorage(StoredServers(listOf(fixture.profile.copy(encryptedSecret = "plain:secret"), other), fixture.profile.id))
        val repository = startedRepository(storage)
        assertEquals(fixture.profile.id, repository.activeProfileId.first())

        repository.activate(other.id)

        assertEquals(Session.SignedOut(other), repository.session.value)
        assertEquals(other.id, storage.read().activeId)
        assertEquals(null, repository.activeProfileId.first())
    }

    @Test
    fun activatingTheCurrentServerKeepsItsSession(): Unit = runBlocking {
        val repository = fixture.signedInRepository(scope)
        val before = repository.session.value

        repository.activate(fixture.profile.id)
        repository.activate("unknown")

        assertSame(before, repository.session.value)
    }

    @Test
    fun removingTheSelectedServerSelectsTheNextOne(): Unit = runBlocking {
        val other = signedOutProfile("other")
        val storage = MemoryProfileStorage(StoredServers(listOf(fixture.profile.copy(encryptedSecret = "plain:secret"), other), fixture.profile.id))
        val repository = startedRepository(storage)

        repository.remove(fixture.profile.id)

        assertEquals(StoredServers(listOf(other), other.id), storage.read())
        assertEquals(Session.SignedOut(other), repository.session.value)
    }

    @Test
    fun removingAnotherServerKeepsTheSession(): Unit = runBlocking {
        val other = signedOutProfile("other")
        val storage = MemoryProfileStorage(StoredServers(listOf(fixture.profile.copy(encryptedSecret = "plain:secret"), other), fixture.profile.id))
        val repository = startedRepository(storage)
        val before = repository.session.value

        repository.remove(other.id)

        assertEquals(listOf(fixture.profile.id), storage.read().profiles.map { it.id })
        assertSame(before, repository.session.value)
    }

    @Test
    fun removingTheLastServerLeavesNoSession(): Unit = runBlocking {
        val repository = fixture.signedInRepository(scope)

        repository.remove(fixture.profile.id)

        assertEquals(Session.None, repository.session.value)
        assertEquals(StoredServers(), repository.servers.value)
    }

    @Test
    fun signingOutKeepsTheServer(): Unit = runBlocking {
        val repository = fixture.signedInRepository(scope)

        repository.signOut()

        assertIs<Session.SignedOut>(repository.session.value)
        assertEquals(listOf(fixture.profile.id), repository.servers.value.profiles.map { it.id })
        assertEquals(null, repository.activeProfileId.first())
    }

    @Test
    fun anExpiryForAnotherServerIsIgnored(): Unit = runBlocking {
        val repository = fixture.signedInRepository(scope)

        repository.sessionExpired("other")

        assertIs<Session.Active>(repository.session.value)
    }

    @Test
    fun theActiveServerFallsBackToTheFirst() {
        val first = signedOutProfile("first")
        val second = signedOutProfile("second")

        assertEquals(second, StoredServers(listOf(first, second), "second").active)
        assertEquals(first, StoredServers(listOf(first, second), "gone").active)
        assertNull(StoredServers().active)
    }

    private suspend fun startedRepository(storage: ProfileStorage): ServerRepository {
        val repository = ServerRepository(storage, PlainSecrets(), OkHttpClient(), scope)
        repository.loaded.first { it }
        return repository
    }

    private fun signedOutProfile(id: String) =
        fixture.profile.copy(id = id, name = id, authMethod = AuthMethod.Password, principal = "root@pam")
}
