package io.github.zachelks.proxkeep.data

import io.github.zachelks.proxkeep.alerts.AlertWorker
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlertsTest {

    private val fixture = PinnedServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val all = AlertKinds(failedTasks = true, offlineNodes = true)

    @Volatile
    private var tasks = """[]"""

    @Volatile
    private var nodes = """[{"id":"node/pve1","type":"node","node":"pve1","status":"online"},{"id":"node/pve2","type":"node","node":"pve2","status":"online"}]"""

    @AfterTest
    fun tearDown() {
        scope.cancel()
        fixture.close()
    }

    private fun serve() {
        fixture.route("/cluster/tasks") { response("""{"data":$tasks}""") }
        fixture.route("/cluster/resources") { response("""{"data":$nodes}""") }
    }

    private fun task(upid: String, end: Long, status: String) =
        """{"upid":"$upid","node":"pve1","type":"vzdump","id":"100","starttime":${end - 60},"endtime":$end,"status":"$status"}"""

    @Test
    fun theFirstCheckOnlyRecordsWhatItSees(): Unit = runBlocking {
        serve()
        tasks = "[${task("UPID:old", 1000, "job errors")}]"
        nodes = nodes.replace("\"pve2\",\"status\":\"online\"", "\"pve2\",\"status\":\"offline\"")
        val client = fixture.signedInRepository(scope).request { it }

        val (alerts, state) = AlertChecker.check(fixture.profile, client, null, all)

        assertEquals(emptyList(), alerts)
        assertEquals(ServerAlertState(lastTaskEnd = 1000, offlineNodes = setOf("pve2")), state)
    }

    @Test
    fun laterChecksReportNewFailuresAndNodeChanges(): Unit = runBlocking {
        serve()
        val client = fixture.signedInRepository(scope).request { it }
        tasks = "[${task("UPID:old", 1000, "job errors")},${task("UPID:ok", 1100, "OK")},${task("UPID:new", 1200, "unable to open file")}]"
        nodes = nodes.replace("\"pve2\",\"status\":\"online\"", "\"pve2\",\"status\":\"offline\"")

        val (alerts, state) = AlertChecker.check(fixture.profile, client, ServerAlertState(lastTaskEnd = 1000, offlineNodes = setOf("pve1")), all)

        assertEquals(
            listOf(
                Alert.TaskFailed(fixture.profile.id, fixture.profile.name, "UPID:new", "pve1", "vzdump", "100", "unable to open file"),
                Alert.NodeOffline(fixture.profile.id, fixture.profile.name, "pve2"),
                Alert.NodeOnline(fixture.profile.id, fixture.profile.name, "pve1"),
            ),
            alerts,
        )
        assertEquals(ServerAlertState(lastTaskEnd = 1200, offlineNodes = setOf("pve2")), state)
    }

    @Test
    fun onlyTheChosenKindsAreChecked(): Unit = runBlocking {
        serve()
        val client = fixture.signedInRepository(scope).request { it }
        tasks = "[${task("UPID:new", 1200, "failed")}]"
        val (alerts, state) = AlertChecker.check(fixture.profile, client, ServerAlertState(1000, setOf("pve9")), AlertKinds(failedTasks = false, offlineNodes = false))
        assertEquals(emptyList(), alerts)
        assertEquals(ServerAlertState(1000, setOf("pve9")), state)
        assertEquals(0, fixture.requestCount("/cluster/tasks"))
    }

    @Test
    fun everyReachableServerIsCheckedAndForgottenServersAreDropped(): Unit = runBlocking {
        serve()
        val repository = fixture.signedInRepository(scope)
        val store = MemoryAlertStore(mapOf(fixture.profile.id to ServerAlertState(lastTaskEnd = 1000), "removed" to ServerAlertState()))
        tasks = "[${task("UPID:new", 1200, "failed")}]"

        val alerts = AlertWorker.checkAll(repository, store, all)

        assertEquals(listOf("UPID:new"), alerts.filterIsInstance<Alert.TaskFailed>().map { it.upid })
        assertEquals(setOf(fixture.profile.id), store.state.keys)
        assertEquals(1200, store.state.getValue(fixture.profile.id).lastTaskEnd)
    }

    @Test
    fun aSavedPasswordTheServerRejectsIsForgotten(): Unit = runBlocking {
        val withPassword = fixture.profile.copy(id = "second", authMethod = AuthMethod.Password, principal = "root@pam", encryptedPassword = "plain:wrong")
        val storage = MemoryProfileStorage(StoredServers(listOf(fixture.profile.copy(encryptedSecret = "plain:secret"), withPassword), activeId = fixture.profile.id))
        val repository = ServerRepository(storage, PlainSecrets(), OkHttpClient(), scope)
        repository.loaded.first { it }
        fixture.route("/access/ticket", """{"data":null,"message":"authentication failure"}""", code = 401)

        assertNull(repository.backgroundClient(withPassword))
        assertNull(repository.servers.value.profiles.first { it.id == "second" }.encryptedPassword)

        // A token and the signed-in session need no sign-in.
        assertNotNull(repository.backgroundClient(fixture.profile.copy(encryptedSecret = "plain:secret")))
        assertNull(repository.backgroundClient(fixture.profile.copy(id = "web", authMethod = AuthMethod.Web)))
        assertTrue(fixture.requestCount("/access/ticket") == 1)
    }

    private class MemoryAlertStore(var state: Map<String, ServerAlertState>) : AlertStore {
        override fun read() = state

        override fun write(state: Map<String, ServerAlertState>) {
            this.state = state
        }
    }
}
