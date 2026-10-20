package io.github.zachelks.proxkeep.core.api

import io.github.zachelks.proxkeep.core.api.model.CephStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class CephStatusTest {

    @Test
    fun aReefStatusIsRead() {
        val json = Json.parseToJsonElement(
            """{"health":{"status":"HEALTH_WARN","checks":{"OSD_DOWN":{"severity":"HEALTH_WARN","summary":{"message":"1 osds down"}}}},
               "monmap":{"mons":[{"name":"a"},{"name":"b"},{"name":"c"}]},"quorum_names":["a","b"],
               "osdmap":{"num_osds":6,"num_up_osds":5,"num_in_osds":6},
               "pgmap":{"pgs_by_state":[{"state_name":"active+clean","count":120},{"state_name":"active+degraded","count":8}],"bytes_used":"3000","bytes_total":12000}}""",
        ) as JsonObject
        val status = CephStatus.fromJson(json)
        assertEquals("HEALTH_WARN", status.health)
        assertFalse(status.isHealthy)
        assertEquals(listOf("OSD_DOWN" to "1 osds down"), status.checks)
        assertEquals(3, status.monitors)
        assertEquals(listOf("a", "b"), status.quorum)
        assertEquals(6, status.osds)
        assertEquals(5, status.osdsUp)
        assertEquals(listOf("active+clean" to 120L, "active+degraded" to 8L), status.placementGroups)
        assertEquals(3000, status.bytesUsed)
    }

    @Test
    fun olderAndPartialStatusesStillRead() {
        val nested = Json.parseToJsonElement("""{"health":{"status":"HEALTH_OK"},"osdmap":{"osdmap":{"num_osds":3,"num_up_osds":3,"num_in_osds":3}}}""") as JsonObject
        val status = CephStatus.fromJson(nested)
        assertEquals(3, status.osds)
        assertNull(status.monitors)
        assertEquals(emptyList(), status.placementGroups)
    }
}
