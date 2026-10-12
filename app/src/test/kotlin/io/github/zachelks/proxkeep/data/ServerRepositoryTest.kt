package io.github.zachelks.proxkeep.data

import io.github.zachelks.proxkeep.core.api.PveApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

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
}
