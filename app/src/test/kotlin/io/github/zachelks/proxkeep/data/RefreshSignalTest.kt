package io.github.zachelks.proxkeep.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshSignalTest {

    private val signal = RefreshSignal()
    private val ticks = mutableListOf<Long>()

    @Test
    fun ticksAtOnceAndThenOnTheInterval() = runTest {
        val watcher = launch { signal.ticks(intervalMs = 1_000).collect { ticks += currentTime } }

        advanceTimeBy(2_500)

        assertEquals(listOf(0L, 1_000L, 2_000L), ticks)
        watcher.cancel()
    }

    @Test
    fun aRequestTicksAtOnce() = runTest {
        val watcher = launch { signal.ticks(intervalMs = 10_000).collect { ticks += currentTime } }
        advanceTimeBy(3_000)

        signal.request()
        runCurrent()

        assertEquals(listOf(0L, 3_000L), ticks)
        watcher.cancel()
    }

    @Test
    fun aRequestWhileBusyIsKeptForLater() = runTest {
        val watcher = launch {
            signal.ticks(intervalMs = 60_000).collect {
                ticks += currentTime
                delay(5_000)
            }
        }
        advanceTimeBy(1_000)

        signal.request()
        signal.request()
        advanceTimeBy(10_000)

        assertEquals(listOf(0L, 5_000L), ticks, "two requests during one load make one more load")
        watcher.cancel()
    }
}
