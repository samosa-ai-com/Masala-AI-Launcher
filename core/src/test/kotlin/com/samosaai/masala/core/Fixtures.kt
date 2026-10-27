package com.samosaai.masala.core

import com.samosaai.masala.core.contract.ContextSnapshot
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.contract.ModePatch
import java.time.LocalDateTime

object Fixtures {
    val layoutExamples = listOf("classic", "workday")
    val modeExamples = listOf("mode-focus", "mode-quiet-nights")
    val skillExamples = listOf("meeting_notes", "chef", "daily_brief")

    fun text(path: String): String =
        checkNotNull(javaClass.getResource("/masala/$path")) { "Missing resource $path" }.readText()

    fun layout(name: String): LayoutDoc = MasalaJson.strict.decodeFromString(text("examples/$name.json"))
    fun mode(name: String): ModePatch = MasalaJson.strict.decodeFromString(text("examples/$name.json"))
    fun skill(name: String): MasalaSkill = MasalaJson.lenient.decodeFromString(text("skills/$name.json"))

    /** Tuesday 27 October 2026 at [time]. */
    fun at(time: String, day: Int = 27) = ContextSnapshot(now = LocalDateTime.parse("2026-10-${day}T$time"))
}
