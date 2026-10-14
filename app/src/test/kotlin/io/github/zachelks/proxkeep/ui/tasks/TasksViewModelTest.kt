package io.github.zachelks.proxkeep.ui.tasks

import io.github.zachelks.proxkeep.MainDispatcherRule
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.TaskSummary
import io.github.zachelks.proxkeep.data.PinnedServer
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.ServerRepository
import io.github.zachelks.proxkeep.data.response
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import androidx.lifecycle.viewModelScope
import org.junit.Rule
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TasksViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val fixture = PinnedServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refresh = RefreshSignal()
    private lateinit var repository: ServerRepository
    private lateinit var viewModel: TasksViewModel

    private val task = TaskSummary(upid = UPID, node = "pve1", type = "qmstart", id = "100")

    private fun start() = runBlocking {
        repository = fixture.signedInRepository(scope)
        viewModel = TasksViewModel(repository, refresh)
        scope.launch { viewModel.tasks.collect {} }
        scope.launch { viewModel.log.collect {} }
    }

    @AfterTest
    fun tearDown() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        scope.cancel()
        fixture.close()
    }

    @Test
    fun tasksStayOnScreenWhenARefreshFails(): Unit = runBlocking {
        fixture.route("/cluster/tasks", """{"data":[{"upid":"$UPID","node":"pve1","type":"qmstart","id":"100","status":"OK"}]}""")
        start()
        val loaded = withTimeout(TIMEOUT_MS) { viewModel.tasks.first { it is TasksState.Loaded } }

        fixture.route("/cluster/tasks", """{"data":null}""", code = 500)
        refresh.request()
        val stale = withTimeout(TIMEOUT_MS) { viewModel.tasks.first { (it as? TasksState.Loaded)?.refreshError != null } }

        assertEquals(listOf(UPID), assertIs<TasksState.Loaded>(loaded).tasks.map { it.upid })
        assertEquals(loaded.tasks, assertIs<TasksState.Loaded>(stale).tasks)
        assertEquals(R.string.error_server_response, stale.refreshError?.id)
    }

    @Test
    fun aFirstLoadThatFailsIsReported(): Unit = runBlocking {
        fixture.route("/cluster/tasks", """{"data":null}""", code = 403)
        start()

        val failed = withTimeout(TIMEOUT_MS) { viewModel.tasks.first { it is TasksState.Failed } }

        assertEquals(R.string.error_permission_denied, assertIs<TasksState.Failed>(failed).message.id)
    }

    @Test
    fun theLogFollowsARunningTaskToItsEnd(): Unit = runBlocking {
        val statusReads = AtomicInteger()
        fixture.route(STATUS_PATH) {
            if (statusReads.getAndIncrement() == 0) response("""{"data":{"status":"running"}}""") else response(STOPPED)
        }
        fixture.route(LOG_PATH) { request ->
            when (request.requestUrl?.queryParameter("start")) {
                "0" -> response(logLines(1, "starting", "step 1"))
                "2" -> response(logLines(3, "TASK OK"))
                else -> response("""{"data":[]}""")
            }
        }
        start()

        viewModel.select(task)
        val running = withTimeout(TIMEOUT_MS) { viewModel.log.first { it?.status?.isRunning == true } }
        val finished = withTimeout(TIMEOUT_MS) { viewModel.log.first { it?.finished == true } }

        assertEquals(listOf("starting", "step 1"), running?.lines)
        assertEquals(listOf("starting", "step 1", "TASK OK"), finished?.lines)
        assertEquals("OK", finished?.status?.exitstatus)
    }

    @Test
    fun aLongLogIsReadPageByPage(): Unit = runBlocking {
        val starts = CopyOnWriteArrayList<String>()
        fixture.route(STATUS_PATH, STOPPED)
        fixture.route(LOG_PATH) { request ->
            val start = request.requestUrl?.queryParameter("start").orEmpty()
            starts += start
            when (start) {
                "0" -> response(logLines(1, *Array(500) { "line ${it + 1}" }))
                else -> response(logLines(501, "TASK OK"))
            }
        }
        start()

        viewModel.select(task)
        val finished = withTimeout(TIMEOUT_MS) { viewModel.log.first { it?.finished == true } }

        assertEquals(501, finished?.lines?.size)
        assertEquals(listOf("0", "500"), starts)
    }

    @Test
    fun theServersEmptyLogPlaceholderIsNotShown(): Unit = runBlocking {
        fixture.route(STATUS_PATH, STOPPED)
        fixture.route(LOG_PATH, logLines(1, "no content"))
        start()

        viewModel.select(task)
        val finished = withTimeout(TIMEOUT_MS) { viewModel.log.first { it?.finished == true } }

        assertEquals(emptyList(), finished?.lines)
    }

    @Test
    fun aRefusedLogIsNotAskedForAgain(): Unit = runBlocking {
        fixture.route(STATUS_PATH, """{"data":null}""", code = 403)
        start()

        viewModel.select(task)
        val refused = withTimeout(TIMEOUT_MS) { viewModel.log.first { it?.finished == true } }
        delay(LOG_POLL_MS)

        assertEquals(R.string.error_permission_denied, refused?.error?.id)
        assertEquals(1, fixture.requestCount(STATUS_PATH))
    }

    @Test
    fun theSameTaskSelectedAgainKeepsItsLog(): Unit = runBlocking {
        fixture.route(STATUS_PATH, STOPPED)
        fixture.route(LOG_PATH, logLines(1, "TASK OK"))
        start()
        viewModel.select(task)
        withTimeout(TIMEOUT_MS) { viewModel.log.first { it?.finished == true } }

        viewModel.select(task.copy(status = "OK", endtime = 1_700_000_000))
        delay(200)

        assertEquals(1, fixture.requestCount(STATUS_PATH))
        assertTrue(viewModel.log.value?.finished == true)
    }

    @Test
    fun nothingIsReadWhileSignedOut(): Unit = runBlocking {
        start()
        repository.signOut()
        delay(200)
        val before = fixture.requestedPaths.size

        refresh.request()
        delay(200)

        assertEquals(TasksState.Loading, viewModel.tasks.value)
        assertEquals(before, fixture.requestedPaths.size)
    }

    private fun logLines(first: Int, vararg lines: String): String =
        lines.mapIndexed { index, text -> """{"n":${first + index},"t":"$text"}""" }
            .joinToString(",", prefix = """{"data":[""", postfix = "]}")

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val LOG_POLL_MS = 1_500L
        const val UPID = "UPID:pve1:00001234:00005678:6712F3A1:qmstart:100:root@pam:"
        const val STATUS_PATH = "/nodes/pve1/tasks/$UPID/status"
        const val LOG_PATH = "/nodes/pve1/tasks/$UPID/log"
        const val STOPPED = """{"data":{"status":"stopped","exitstatus":"OK"}}"""
    }
}
