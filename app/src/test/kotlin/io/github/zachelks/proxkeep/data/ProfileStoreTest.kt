package io.github.zachelks.proxkeep.data

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The saved servers file, on Android's own [android.util.AtomicFile]. */
@RunWith(RobolectricTestRunner::class)
class ProfileStoreTest {

    private lateinit var file: File

    private val profile = ServerProfile(
        id = "a",
        name = "Home lab",
        host = "pve.example.org",
        port = 8006,
        pinnedSha256 = "AB:CD",
        authMethod = AuthMethod.ApiToken,
        principal = "root@pam!app",
        encryptedSecret = "sealed",
    )

    @BeforeTest
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File(context.filesDir, "servers.json")
        file.delete()
    }

    @Test
    fun nothingSavedReadsAsEmpty(): Unit = runBlocking {
        assertEquals(StoredServers(), ProfileStore(file).read())
    }

    @Test
    fun whatIsWrittenIsReadBack(): Unit = runBlocking {
        val saved = StoredServers(listOf(profile, profile.copy(id = "b", authMethod = AuthMethod.Password)), activeId = "b")

        ProfileStore(file).write(saved)

        assertEquals(saved, ProfileStore(file).read())
        assertEquals(listOf("servers.json"), file.parentFile!!.list()!!.toList(), "no backup or temporary file is left")
    }

    @Test
    fun aLaterWriteReplacesTheEarlierOne(): Unit = runBlocking {
        val store = ProfileStore(file)
        store.write(StoredServers(listOf(profile), activeId = "a"))
        store.write(StoredServers())

        assertEquals(StoredServers(), store.read())
    }

    @Test
    fun anUnreadableFileReadsAsEmpty(): Unit = runBlocking {
        file.writeText("{not json")

        assertEquals(StoredServers(), ProfileStore(file).read())
    }

    @Test
    fun fieldsFromANewerVersionAreIgnored(): Unit = runBlocking {
        file.writeText(
            """{"profiles":[{"id":"a","name":"Lab","host":"pve","port":8006,"authMethod":"Password",
               "principal":"root@pam","colour":"blue"}],"activeId":"a","sortOrder":"name"}""",
        )

        val read = ProfileStore(file).read()

        assertEquals(listOf("a"), read.profiles.map { it.id })
        assertEquals("a", read.activeId)
    }
}
