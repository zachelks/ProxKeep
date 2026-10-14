package io.github.zachelks.proxkeep.ui.lifecycle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.data.LifecycleEvent
import io.github.zachelks.proxkeep.data.LifecycleOperation
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class LifecycleTextTest {

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun everyOperationHasWordsThatNameTheGuest() {
        LifecycleOperation.entries.forEach { operation ->
            assertTrue(resources.getString(operation.progressLabel).isNotBlank(), "$operation progress")
            assertTrue("web" in LifecycleEvent(operation, "web", succeeded = true).message(resources), "$operation done")
            assertTrue("web" in LifecycleEvent(operation, "web", succeeded = false).message(resources), "$operation failed")
        }
    }

    @Test
    fun aFailureAddsTheServersReason() {
        val message = LifecycleEvent(LifecycleOperation.Backup, "web", succeeded = false, detail = " storage full ").message(resources)

        assertEquals(resources.getString(R.string.failed_backup, "web") + "\nstorage full", message)
    }
}
