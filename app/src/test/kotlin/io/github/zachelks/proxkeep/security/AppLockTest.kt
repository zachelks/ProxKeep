package io.github.zachelks.proxkeep.security

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLockTest {

    private var enabled = true
    private var timeoutMs = 60_000L
    private var clock = 1_000_000L

    private fun newLock() = AppLock(isEnabled = { enabled }, timeoutMs = { timeoutMs }, now = { clock })

    private fun AppLock.background(forMs: Long) {
        onBackground()
        clock += forMs
        onForeground()
    }

    @Test
    fun startsLockedOnlyWhenSwitchedOn() {
        assertTrue(newLock().locked.value)
        enabled = false
        assertFalse(newLock().locked.value)
    }

    @Test
    fun staysUnlockedForAShortAbsence() {
        val lock = newLock().apply { unlock() }
        lock.background(forMs = 59_999)
        assertFalse(lock.locked.value)
    }

    @Test
    fun locksAfterTheTimeout() {
        val lock = newLock().apply { unlock() }
        lock.background(forMs = 60_000)
        assertTrue(lock.locked.value)
    }

    @Test
    fun immediateTimeoutLocksOnAnyAbsence() {
        timeoutMs = 0
        val lock = newLock().apply { unlock() }
        lock.background(forMs = 0)
        assertTrue(lock.locked.value)
    }

    @Test
    fun neverLocksWhenSwitchedOff() {
        enabled = false
        val lock = newLock()
        lock.background(forMs = 3_600_000)
        assertFalse(lock.locked.value)
    }

    @Test
    fun switchingOnWhileOpenLocksAtTheNextAbsence() {
        enabled = false
        val lock = newLock()
        enabled = true
        assertFalse(lock.locked.value)
        lock.background(forMs = 60_000)
        assertTrue(lock.locked.value)
    }

    @Test
    fun staysLockedUntilUnlockIsCalled() {
        val lock = newLock()
        lock.background(forMs = 5)
        lock.onForeground()
        assertTrue(lock.locked.value)
        lock.unlock()
        assertFalse(lock.locked.value)
    }

    @Test
    fun theSystemPromptCoveringTheAppIsNotAnAbsence() {
        timeoutMs = 0
        val lock = newLock().apply { unlock() }
        lock.onAuthenticationStarted()
        lock.background(forMs = 30_000)
        lock.onAuthenticationFinished()
        assertFalse(lock.locked.value)

        // Afterwards absences count again.
        lock.background(forMs = 1)
        assertTrue(lock.locked.value)
    }

    @Test
    fun aPromptThatNeverReportsBackExcusesOnlyOneAbsence() {
        timeoutMs = 0
        val lock = newLock().apply { unlock() }
        lock.onAuthenticationStarted()
        lock.background(forMs = 1)
        assertFalse(lock.locked.value)

        lock.background(forMs = 1)
        assertTrue(lock.locked.value)
    }

    @Test
    fun unlockingForgetsAnAbsenceThatStartedBeforeIt() {
        timeoutMs = 0
        val lock = newLock().apply { unlock() }
        lock.onBackground()
        clock += 10_000
        lock.unlock()
        lock.onForeground()
        assertFalse(lock.locked.value)
    }

    @Test
    fun eachAbsenceIsTimedSeparately() {
        val lock = newLock().apply { unlock() }
        lock.background(forMs = 40_000)
        lock.background(forMs = 40_000)
        assertFalse(lock.locked.value)
    }
}
