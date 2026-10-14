package io.github.zachelks.proxkeep.ui.schema

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.schema.ParamSchema
import io.github.zachelks.proxkeep.core.api.schema.ParamType
import io.github.zachelks.proxkeep.core.api.schema.check
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class SchemaFieldsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun aYesOrNoSettingIsASwitchThatShowsTheDefault() {
        var value by mutableStateOf("")
        compose.setContent {
            SchemaFieldEditor("onboot", ParamSchema("onboot", ParamType.Boolean, default = "0"), value, { value = it })
        }
        val label = context.getString(R.string.setting_onboot)
        compose.onNodeWithContentDescription(label).assertIsOff().performClick()
        assertEquals("1", value)
        compose.onNodeWithContentDescription(label).assertIsOn()
    }

    @Test
    fun aChoiceOffersTheDefaultAndEachValue() {
        var value by mutableStateOf("l26")
        val schema = ParamSchema("ostype", ParamType.String, enum = listOf("l26", "win11"))
        compose.setContent { SchemaFieldEditor("ostype", schema, value, { value = it }) }

        compose.onNodeWithText("l26").performClick()
        compose.onNodeWithText("win11").performClick()
        assertEquals("win11", value)
        compose.onNodeWithText("win11").performClick()
        compose.onNodeWithText(context.getString(R.string.setting_not_set)).performClick()
        assertEquals("", value)
    }

    @Test
    fun aNumberShowsWhatIsWrongWithIt() {
        var value by mutableStateOf("2")
        val schema = ParamSchema("cores", ParamType.Integer, minimum = 1.0)
        compose.setContent { SchemaFieldEditor("cores", schema, value, { value = it }, problem = schema.check(value)) }

        compose.onNode(hasSetTextAction()).performTextReplacement("0")
        assertEquals("0", value)
        compose.onNodeWithText(context.getString(R.string.problem_below_minimum, "1")).assertExists()
    }

    @Test
    fun aPropertyStringIsEditedOneKeyAtATime() {
        var value by mutableStateOf("virtio=BC:24:11:2A:3B:4C,bridge=vmbr0")
        val schema = ParamSchema(
            "net[n]", ParamType.String,
            properties = listOf(
                ParamSchema("model", ParamType.String, enum = listOf("virtio", "e1000"), defaultKey = true, optional = false),
                ParamSchema("virtio", ParamType.Other, alias = "macaddr", keyAlias = "model"),
                ParamSchema("macaddr", ParamType.String),
                ParamSchema("bridge", ParamType.String),
                ParamSchema("firewall", ParamType.Boolean),
            ),
        )
        compose.setContent { Column { SchemaFieldEditor("net0", schema, value, { value = it }) } }

        compose.onNode(hasText("vmbr0") and hasSetTextAction()).performTextReplacement("vmbr1")
        assertEquals("virtio=BC:24:11:2A:3B:4C,bridge=vmbr1", value)
        compose.onNodeWithContentDescription("firewall").performClick()
        assertEquals("virtio=BC:24:11:2A:3B:4C,bridge=vmbr1,firewall=1", value)
    }
}
