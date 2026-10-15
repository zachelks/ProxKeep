package io.github.zachelks.proxkeep.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class ExtraKeysEditingTest {

    @Test
    fun keysMoveOnePlaceAtATime() {
        val row = listOf("a", "b", "c")

        assertEquals(listOf("b", "a", "c"), row.moved(1, 0))
        assertEquals(listOf("a", "c", "b"), row.moved(1, 2))
        assertEquals(row, row.moved(0, -1))
        assertEquals(row, row.moved(2, 3))
    }
}
