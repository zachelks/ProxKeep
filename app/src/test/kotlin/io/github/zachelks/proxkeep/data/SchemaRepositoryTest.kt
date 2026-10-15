package io.github.zachelks.proxkeep.data

import io.github.zachelks.proxkeep.core.api.SchemaUnavailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SchemaRepositoryTest {

    private val fixture = PinnedServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cacheDir = Files.createTempDirectory("schema").toFile()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        fixture.close()
        cacheDir.deleteRecursively()
    }

    @Test
    fun theSchemaIsDownloadedOncePerVersion(): Unit = runBlocking {
        var version = "9.0.3"
        fixture.route("/version") { response("""{"data":{"version":"$version","release":"9.0","repoid":"abc"}}""") }
        fixture.route(SCHEMA_PATH, schemaFile("/version"))
        val repository = SchemaRepository(fixture.signedInRepository(scope), cacheDir)

        val first = repository.schema()
        assertNotNull(first.method("/version", "GET"))
        assertSame(first, repository.schema())
        assertEquals(1, fixture.requestCount(SCHEMA_PATH))
        assertEquals(1, cacheDir.listFiles()!!.size)

        // A new repository, as after the app restarts, reads the saved copy.
        val restarted = SchemaRepository(fixture.signedInRepository(scope), cacheDir)
        assertNotNull(restarted.schema().method("/version", "GET"))
        assertEquals(1, fixture.requestCount(SCHEMA_PATH))

        // An upgraded server's schema replaces the old one.
        version = "9.1.0"
        fixture.route(SCHEMA_PATH, schemaFile("/cluster/status"))
        assertNotNull(restarted.schema().method("/cluster/status", "GET"))
        assertEquals(2, fixture.requestCount(SCHEMA_PATH))
        assertEquals(1, cacheDir.listFiles()!!.size)
    }

    @Test
    fun aMissingSchemaDoesNotSignOut(): Unit = runBlocking {
        fixture.route("/version", """{"data":{"version":"9.0.3"}}""")
        fixture.route(SCHEMA_PATH, "Not found", code = 401)
        val servers = fixture.signedInRepository(scope)
        val repository = SchemaRepository(servers, cacheDir)

        assertFailsWith<SchemaUnavailableException> { repository.schema() }
        assertTrue(servers.session.value is Session.Active)
    }

    private fun schemaFile(path: String) =
        """const apiSchema = [{"path":"$path","text":"x","leaf":1,"info":{"GET":{"method":"GET","name":"x"}}}];"""

    private companion object {
        const val SCHEMA_PATH = "/pve-docs/api-viewer/apidoc.js"
    }
}
