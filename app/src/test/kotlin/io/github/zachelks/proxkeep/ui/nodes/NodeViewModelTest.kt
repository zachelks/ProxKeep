package io.github.zachelks.proxkeep.ui.nodes

import io.github.zachelks.proxkeep.core.api.model.NodeCertificate
import io.github.zachelks.proxkeep.core.api.model.RrdPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodeViewModelTest {

    @Test
    fun aNodesHistoryIsReadLikeAGuests() {
        val point = NodeViewModel.asGuestPoint(RrdPoint(100, mapOf("cpu" to 0.2, "memused" to 1024.0, "memtotal" to 4096.0)))
        assertEquals(1024.0, point["mem"])
        assertEquals(4096.0, point["maxmem"])
        assertEquals(0.2, point["cpu"])
    }

    @Test
    fun certificatesThatExpireWithinAMonthAreFlagged() {
        val now = 1_760_000_000L
        assertTrue(NodeViewModel.expiresSoon(NodeCertificate("a.pem", notafter = now + 10 * 86_400L), now))
        assertTrue(NodeViewModel.expiresSoon(NodeCertificate("b.pem", notafter = now - 1), now))
        assertFalse(NodeViewModel.expiresSoon(NodeCertificate("c.pem", notafter = now + 90 * 86_400L), now))
        assertFalse(NodeViewModel.expiresSoon(NodeCertificate("d.pem"), now))
    }
}
