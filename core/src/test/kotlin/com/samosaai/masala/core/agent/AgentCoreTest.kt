package com.samosaai.masala.core.agent

import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.tool.ToolSensitivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolScopeTest {
    private val all = listOf(
        ToolInfo("web_search", ToolSensitivity.READ_ONLY),
        ToolInfo("read_notifications", ToolSensitivity.READ_ONLY, readsPrivateData = true),
        ToolInfo("memory_read", ToolSensitivity.READ_ONLY, readsPrivateData = true),
        ToolInfo("set_alarm", ToolSensitivity.CHANGES_SOMETHING),
        ToolInfo("delete_file", ToolSensitivity.DESTRUCTIVE),
        ToolInfo("create_mini_app", ToolSensitivity.CHANGES_SOMETHING),
    )
    private fun skill(vararg tools: String) = MasalaSkill(id = "s", instructions = "i", requiresTools = tools.toList())

    @Test fun commandSeesEverything() {
        val scope = ToolScope.command(all)
        assertTrue(all.all { scope.allows(it.name) })
    }

    @Test fun skillGetsBasicsMemoryAndRequiredOnly() {
        val scope = ToolScope.skill(skill("create_mini_app"), all)
        assertEquals(setOf("web_search", "memory_read", "create_mini_app"), scope.names)
    }

    @Test fun skillNeverGainsAChangingToolItDidNotRequire() {
        val scope = ToolScope.skill(skill(), all)
        assertFalse(scope.allows("set_alarm"))
        assertFalse(scope.allows("delete_file"))
        assertFalse(scope.allows("read_notifications"))
    }

    @Test fun unknownRequiredToolIsIgnored() {
        assertFalse(ToolScope.skill(skill("nope"), all).allows("nope"))
    }

    @Test fun unattendedKeepsReadOnlyOnly() {
        val scope = ToolScope.unattended(ToolScope.skill(skill("set_alarm"), all), all)
        assertEquals(setOf("web_search", "memory_read"), scope.names)
    }

    @Test fun unattendedLeavesOutToolsThatOpenAScreen() {
        val withScreens = all + ToolInfo("open_app", ToolSensitivity.READ_ONLY, opensScreen = true)
        val scope = ToolScope.unattended(ToolScope.skill(skill("set_alarm"), withScreens), withScreens)
        assertFalse(scope.allows("open_app"))
        assertTrue(scope.allows("web_search"))
    }
}

class CommandFastPathTest {
    private val chrome = MasalaAction.LaunchApp("com.chrome")
    private fun match(text: String) = CommandFastPath.match(text) { if (it == "chrome") chrome else null }

    @Test fun timers() {
        val cases = mapOf(
            "timer 5 min" to 300,
            "set a timer for 90 seconds" to 90,
            "Set a timer for 2 minutes." to 120,
            "start a 10 minute timer" to 600,
            "timer for 1 hour" to 3600,
            "set a timer for 1 hour 30 minutes" to 5400,
            "timer 45s" to 45,
            "set timer 2 mins please" to 120,
            "hey masala set a timer for 3 minutes" to 180,
            "timer 1.5 hours" to 5400,
        )
        for ((text, seconds) in cases) assertEquals(MasalaAction.StartTimer(seconds), match(text), text)
    }

    @Test fun alarms() {
        val cases = mapOf(
            "alarm 7:30" to (7 to 30),
            "set an alarm for 7 am" to (7 to 0),
            "set an alarm at 7:45 pm" to (19 to 45),
            "alarm 12 am" to (0 to 0),
            "alarm 12 pm" to (12 to 0),
            "wake me up at 6:45 am" to (6 to 45),
            "set alarm 18:05" to (18 to 5),
            "alarm at 7 p.m." to (19 to 0),
        )
        for ((text, time) in cases) assertEquals(MasalaAction.SetAlarm(time.first, time.second), match(text), text)
    }

    @Test fun openApp() {
        assertEquals(chrome, match("open chrome"))
        assertEquals(chrome, match("Launch Chrome!"))
    }

    @Test fun nonMatchesFallThroughToTheModel() {
        for (text in listOf(
            "open the window later", "open nothing", "timer", "timer for the pasta 5 min", "set a timer",
            "alarm 25:00", "alarm 13 pm", "alarm 7:75", "what is the weather", "", "remind me about the timer 5 min ago later",
            "alarm 7 and 8", "timer 0 minutes",
        )) assertNull(match(text), text)
    }
}

class NameCorrectorTest {
    private val corrector = NameCorrector(listOf("Spotify", "Google Maps", "Deep Work", "Calculator", "Camera"))

    @Test fun correctsMisheardNames() {
        assertEquals("open Spotify", corrector.correct("open spotfy"))
        assertEquals("open Spotify.", corrector.correct("open spotifi."))
        assertEquals("open Google Maps", corrector.correct("open gogle maps"))
        assertEquals("turn on Deep Work", corrector.correct("turn on deep werk"))
    }

    @Test fun leavesOrdinaryWordsAlone() {
        val text = "set a timer for the camping trip and open settings"
        assertEquals(text, corrector.correct(text))
    }

    @Test fun exactNamesUntouchedAndAmbiguityRefused() {
        assertEquals("open spotify", corrector.correct("open spotify"))
        val ambiguous = NameCorrector(listOf("Camera", "Camers"))
        assertEquals("open camerz", ambiguous.correct("open camerz"))
    }

    @Test fun emptyNames() {
        assertEquals("anything", NameCorrector(emptyList()).correct("anything"))
    }
}

class SkillRouterTest {
    private val persona = com.samosaai.masala.core.contract.Persona("You are a chef.")
    private val chef = MasalaSkill(id = "chef", instructions = "i", keywords = listOf("cook", "recipe"), persona = persona)
    private val noKeywords = MasalaSkill(id = "any", instructions = "i", persona = persona)
    private val noPersona = MasalaSkill(id = "tool", instructions = "i", keywords = listOf("cook"))

    @Test fun picksThePersonaWhoseKeywordMatches() {
        assertEquals(chef, SkillRouter.route("what can I cook with eggs", listOf(noKeywords, noPersona, chef)))
    }

    @Test fun keywordsMatchWholeWordsOnly() {
        assertNull(SkillRouter.route("I need a cookie", listOf(chef)))
    }

    @Test fun skillsWithoutKeywordsOrPersonaNeverRoute() {
        assertNull(SkillRouter.route("anything", listOf(noKeywords, noPersona)))
    }

    @Test fun gameRequestsHandOffToTheGameMaker() {
        val maker = MasalaSkill(
            id = "game_maker", instructions = "i", keywords = listOf("game", "snake"),
            widget = com.samosaai.masala.core.contract.SkillWidget("sports_esports", output = com.samosaai.masala.core.contract.SkillOutput.MINI_APP),
        )
        assertEquals(maker, SkillRouter.handoff("make me a snake game", listOf(chef, maker)))
        // A persona or a skill that doesn't make an app never takes a request this way.
        assertNull(SkillRouter.handoff("what can I cook with eggs", listOf(chef, maker)))
        assertNull(SkillRouter.handoff("make me a game", listOf(noPersona.copy(keywords = listOf("game")))))
    }

    @Test fun requestsToActAreCommandsAdviceIsNot() {
        listOf(
            "add dinner with Anna to my calendar", "remind me about the doctor", "run my morning brief",
            "Can you set an alarm for 7?", "text Mum I'm cooking",
        ).forEach { assertTrue(SkillRouter.isCommand(it), it) }
        listOf("what can I cook with eggs", "explain this", "I need a recipe for dal").forEach { assertFalse(SkillRouter.isCommand(it), it) }
    }
}
