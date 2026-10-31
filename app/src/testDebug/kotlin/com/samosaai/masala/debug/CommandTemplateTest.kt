package com.samosaai.masala.debug

import com.samosaai.masala.tools.MasalaToolDefinitions
import com.samosaai.masala.tools.commandTemplate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The debug screen's "Use" button must produce a `/tool` call that parses. */
class CommandTemplateTest {
    @Test
    fun `required parameters get typed placeholders`() {
        val setAlarm = MasalaToolDefinitions.all.single { it.name == "set_alarm" }
        val args = Json.parseToJsonElement(commandTemplate(setAlarm)).jsonObject
        assertEquals(setOf("hour", "minute"), args.keys)
        assertTrue(args["hour"].toString().toIntOrNull() != null, "hour should be a number: ${args["hour"]}")
    }

    @Test
    fun `a tool with no required parameters produces an empty object`() {
        val showAlarms = MasalaToolDefinitions.all.single { it.name == "show_alarms" }
        assertEquals("{}", commandTemplate(showAlarms))
    }

    @Test
    fun `every tool definition yields parseable arguments`() {
        for (def in MasalaToolDefinitions.all) {
            Json.parseToJsonElement(commandTemplate(def)).jsonObject // throws when malformed
        }
    }
}
