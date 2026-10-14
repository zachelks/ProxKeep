package io.github.zachelks.proxkeep.ui.schema

import io.github.zachelks.proxkeep.core.api.schema.ApiMethod
import io.github.zachelks.proxkeep.core.api.schema.ParamSchema
import io.github.zachelks.proxkeep.core.api.schema.ParamType
import io.github.zachelks.proxkeep.core.api.schema.ProblemKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchemaFormTest {

    private val method = ApiMethod(
        method = "PUT",
        parameters = listOf(
            ParamSchema("node", ParamType.String, optional = false),
            ParamSchema("vmid", ParamType.Integer, optional = false),
            ParamSchema("digest", ParamType.String),
            ParamSchema("delete", ParamType.String),
            ParamSchema("onboot", ParamType.Boolean, default = "0"),
            ParamSchema("cores", ParamType.Integer, minimum = 1.0),
            ParamSchema("name", ParamType.String),
            ParamSchema("serial[n]", ParamType.String),
            ParamSchema("net[n]", ParamType.String),
            ParamSchema("e1000", ParamType.Other, alias = "macaddr"),
        ),
    )
    private val config = mapOf("onboot" to "1", "name" to "web", "serial1" to "socket", "serial0" to "socket", "cores" to "2", "net0" to "virtio")

    @Test
    fun fieldsLeaveOutCallParametersAndWhatScreensEditByHand() {
        val fields = SchemaForm.fieldsFor(method, config, exclude = setOf("cores", "net"))
        assertEquals(listOf("onboot", "name", "serial0", "serial1"), fields.map { it.key })
    }

    @Test
    fun changesSetEditedValuesAndRemoveEmptiedOnes() {
        val form = SchemaForm(SchemaForm.fieldsFor(method, config), config)
        assertTrue(form.changes.isEmpty)

        val edited = form.with("onboot", "0").with("name", "").with("cores", "4")
        assertTrue(edited.isChanged("onboot"))
        assertEquals(mapOf("onboot" to "0", "cores" to "4"), edited.changes.set)
        assertEquals(listOf("name"), edited.changes.delete)

        // Back to the saved value is no change.
        assertTrue(edited.with("onboot", "1").with("name", "web").with("cores", "2").changes.isEmpty)
        assertTrue(edited.saved().changes.isEmpty)
    }

    @Test
    fun valuesAreChecked() {
        val form = SchemaForm(SchemaForm.fieldsFor(method, config), config)
        assertTrue(form.isValid)
        val wrong = form.with("cores", "0")
        assertEquals(ProblemKind.BelowMinimum, wrong.problem("cores")?.kind)
        assertFalse(wrong.isValid)
        assertNull(wrong.problem("onboot"))
    }

    @Test
    fun serverErrorsClearWhenTheValueChanges() {
        val form = SchemaForm(SchemaForm.fieldsFor(method, config), config, serverErrors = mapOf("name" to "invalid format"))
        assertEquals("invalid format", form.serverErrors["name"])
        assertNull(form.with("name", "web-1").serverErrors["name"])
    }

    @Test
    fun numberedSettingsTakeTheFirstFreeIndex() {
        val serial = method.parameter("serial0")!!
        assertEquals("serial2", SchemaForm.nextKey(serial, config.keys))
        assertEquals("serial0", SchemaForm.nextKey(serial, emptyList()))
    }
}
