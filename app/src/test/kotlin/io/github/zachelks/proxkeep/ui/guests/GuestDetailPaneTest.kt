package io.github.zachelks.proxkeep.ui.guests

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.core.api.model.GuestAction
import io.github.zachelks.proxkeep.core.api.model.GuestConfig
import io.github.zachelks.proxkeep.core.api.model.RrdPoint
import io.github.zachelks.proxkeep.core.api.model.RrdTimeframe
import io.github.zachelks.proxkeep.data.LifecycleOperation
import io.github.zachelks.proxkeep.ui.lifecycle.LifecycleMenuItem
import io.github.zachelks.proxkeep.ui.theme.AppTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/** The guest screen's power actions: which are offered, and which ask first. */
@RunWith(RobolectricTestRunner::class)
class GuestDetailPaneTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val requested = mutableListOf<GuestAction>()
    private val running = ClusterResource(id = "qemu/100", type = "qemu", node = "pve1", vmid = 100, name = "web", status = "running")

    @Test
    fun aStoppedGuestStartsWithoutAsking() {
        show(running.copy(status = "stopped"))

        compose.onNodeWithText(text(R.string.action_start)).performClick()

        assertEquals(listOf(GuestAction.Start), requested)
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test
    fun shuttingDownAsksFirst() {
        show(running)

        compose.onNodeWithText(text(R.string.action_shutdown)).performClick()
        compose.onNodeWithText(text(R.string.confirm_shutdown_title, "web")).assertIsDisplayed()
        assertEquals(emptyList(), requested)

        confirmWith(R.string.action_shutdown)
        assertEquals(listOf(GuestAction.Shutdown), requested)
    }

    @Test
    fun cancellingTheQuestionDoesNothing() {
        show(running)

        compose.onNodeWithText(text(R.string.action_shutdown)).performClick()
        compose.onNodeWithText(text(R.string.action_cancel)).performClick()

        compose.onNode(isDialog()).assertDoesNotExist()
        assertEquals(emptyList(), requested)
    }

    @Test
    fun stoppingIsBehindMoreAndAsksFirst() {
        show(running)
        compose.onNodeWithText(text(R.string.action_stop)).assertDoesNotExist()

        compose.onNodeWithText(text(R.string.action_more_actions)).performClick()
        compose.onNodeWithText(text(R.string.action_stop)).performClick()
        compose.onNodeWithText(text(R.string.confirm_stop_title, "web")).assertIsDisplayed()
        confirmWith(R.string.action_stop)

        assertEquals(listOf(GuestAction.Stop), requested)
    }

    @Test
    fun noActionsWithoutPermission() {
        show(running, canPowerManage = false)

        compose.onNodeWithText(text(R.string.action_shutdown)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.action_more_actions)).assertDoesNotExist()
    }

    @Test
    fun aGuestWithAnActionInProgressSaysSoAndTakesNoOther() {
        show(running, busyAction = GuestAction.Shutdown)

        compose.onNodeWithText(text(R.string.progress_shutdown)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_shutdown)).assertIsNotEnabled()
    }

    @Test
    fun aRunningContainerOffersItsConsole() {
        show(running.copy(id = "lxc/200", type = "lxc", vmid = 200))

        compose.onNodeWithContentDescription(text(R.string.action_open_console)).assertIsDisplayed()
    }

    @Test
    fun aVirtualMachineWithASerialPortOffersItsConsole() {
        show(running, detail = GuestDetail("qemu/100", config = GuestConfig(mapOf("serial0" to "socket"))))

        compose.onNodeWithContentDescription(text(R.string.action_open_console)).assertIsDisplayed()
    }

    @Test
    fun noConsoleWithoutASerialPort() {
        show(running, detail = GuestDetail("qemu/100", config = GuestConfig(emptyMap())))

        compose.onNodeWithContentDescription(text(R.string.action_open_console)).assertDoesNotExist()
    }

    @Test
    fun noConsoleWithoutPermission() {
        show(running.copy(id = "lxc/200", type = "lxc", vmid = 200), canOpenConsole = false)

        compose.onNodeWithContentDescription(text(R.string.action_open_console)).assertDoesNotExist()
    }

    @Test
    fun aRunningVirtualMachineOffersItsDisplay() {
        show(running)

        compose.onNodeWithContentDescription(text(R.string.action_open_display)).assertIsDisplayed()
    }

    @Test
    fun aStoppedMachineHasNoDisplay() {
        show(running.copy(status = "stopped"))
        compose.onNodeWithContentDescription(text(R.string.action_open_display)).assertDoesNotExist()
    }

    @Test
    fun aContainerHasNoDisplay() {
        show(running.copy(id = "lxc/200", type = "lxc", vmid = 200))
        compose.onNodeWithContentDescription(text(R.string.action_open_display)).assertDoesNotExist()
    }

    @Test
    fun theGuestMenuKeepsDestructiveItemsApart() {
        val chosen = mutableListOf<LifecycleMenuItem>()
        show(running, lifecycleItems = listOf(LifecycleMenuItem.Snapshots, LifecycleMenuItem.Delete), onLifecycle = { chosen += it })

        compose.onNodeWithContentDescription(text(R.string.action_guest_menu, "web")).performClick()
        compose.onNodeWithText(text(R.string.action_snapshots)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_delete_guest)).performClick()

        assertEquals(listOf(LifecycleMenuItem.Delete), chosen)
    }

    @Test
    fun aGuestWithALifecycleOperationRunningSaysSoAndTakesNoPowerAction() {
        show(running, lifecycleBusy = LifecycleOperation.Backup)

        compose.onNodeWithText(text(R.string.progress_backup)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_shutdown)).assertIsNotEnabled()
    }

    @Test
    fun aGuestThatIsGoneSaysSo() {
        show(null)

        compose.onNodeWithText(text(R.string.guest_gone)).assertIsDisplayed()
    }

    @Test
    fun usageHistoryIsChartedAndItsPeriodCanBeChanged() {
        val chosen = mutableListOf<RrdTimeframe>()
        val points = listOf(
            RrdPoint(1_760_400_000, mapOf("cpu" to 0.5, "mem" to 1024.0 * 1024 * 1024, "maxmem" to 2048.0 * 1024 * 1024)),
            RrdPoint(1_760_400_060, mapOf("cpu" to 0.25, "mem" to 512.0 * 1024 * 1024, "netin" to 2048.0, "netout" to 1024.0)),
        )
        show(running, history = UsageHistory("qemu/100", RrdTimeframe.Hour, points), onTimeframe = { chosen += it })

        compose.onNode(hasContentDescription(text(R.string.chart_description, text(R.string.usage_cpu)), substring = true))
            .assertExists()
        compose.onNode(hasContentDescription(text(R.string.chart_series_description, text(R.string.usage_cpu), "25%", "50%"), substring = true))
            .assertExists()
        compose.onNode(hasContentDescription(text(R.string.chart_description, text(R.string.chart_network)), substring = true))
            .assertExists()
        compose.onNode(hasContentDescription(text(R.string.chart_description, text(R.string.chart_disk_io)), substring = true))
            .assertDoesNotExist()

        compose.onNodeWithText(text(R.string.timeframe_week)).performClick()
        assertEquals(listOf(RrdTimeframe.Week), chosen)
    }

    @Test
    fun aPeriodWithoutDataSaysSo() {
        show(running, history = UsageHistory("qemu/100", RrdTimeframe.Hour, listOf(RrdPoint(1_760_400_000, mapOf("maxcpu" to 2.0)))))

        compose.onNodeWithText(text(R.string.history_empty)).assertExists()
    }

    private fun show(
        resource: ClusterResource?,
        detail: GuestDetail? = null,
        history: UsageHistory? = null,
        onTimeframe: (RrdTimeframe) -> Unit = {},
        busyAction: GuestAction? = null,
        canPowerManage: Boolean = true,
        canOpenConsole: Boolean = true,
        lifecycleItems: List<LifecycleMenuItem> = emptyList(),
        lifecycleBusy: LifecycleOperation? = null,
        onLifecycle: (LifecycleMenuItem) -> Unit = {},
    ) {
        compose.setContent {
            AppTheme {
                GuestDetailPane(
                    resource = resource,
                    detail = detail,
                    history = history,
                    timeframe = RrdTimeframe.Hour,
                    onTimeframe = onTimeframe,
                    busyAction = busyAction,
                    canPowerManage = canPowerManage,
                    canOpenConsole = canOpenConsole,
                    showBack = false,
                    onBack = {},
                    onAction = { requested += it },
                    onOpenConsole = {},
                    lifecycleItems = lifecycleItems,
                    lifecycleBusy = lifecycleBusy,
                    onLifecycle = onLifecycle,
                )
            }
        }
    }

    private fun confirmWith(@StringRes label: Int) {
        compose.onNode(hasText(text(label)) and hasAnyAncestor(isDialog())).performClick()
    }

    private fun text(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)
}
