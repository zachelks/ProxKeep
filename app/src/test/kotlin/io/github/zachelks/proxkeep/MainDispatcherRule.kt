package io.github.zachelks.proxkeep

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Runs work meant for the main thread, such as a view model's, on background
 * threads. Unit tests have no Android main thread to run it on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(Dispatchers.Default)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
