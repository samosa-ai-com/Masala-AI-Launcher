package com.samosaai.masala.agent

import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.contract.Persona
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The prompt tells one agent where to consult and where to start a job. */
class AgentPromptTest {
    private val doctor = MasalaSkill(
        id = "doctor",
        title = "Doctor",
        description = "Health questions talked through like a GP.",
        instructions = "Talk as a doctor.",
        persona = Persona("You are a GP.", whenToUse = "Symptoms, pain, medication or test results."),
    )
    private val gameMaker = MasalaSkill(
        id = "game_maker",
        title = "Game maker",
        description = "Builds a small game as an app.",
        instructions = "Build the game.",
    )

    @Test
    fun expertsAndJobsAreListedSeparately() {
        val prompt = AgentPrompt.build("Monday", listOf(doctor, gameMaker))

        val experts = prompt.substringAfter("Experts you can consult")
        // The line is the persona's own "when to use", not a hard-coded description.
        assertTrue(experts.contains("doctor: Symptoms, pain, medication or test results."))
        // The job is not offered as an expert, and the expert is not offered as a job.
        val jobs = prompt.substringAfter("Installed skills (start one with run_skill")
        assertTrue(jobs.contains("game_maker: Builds a small game"))
        assertFalse(jobs.contains("doctor:"))
    }

    @Test
    fun thePromptMakesTheAgentTheOneAnswering() {
        val prompt = AgentPrompt.build("Monday", listOf(doctor))
        // The agent answers; the expert is one input, and need not be named every time.
        for (needle in listOf(
            "consult_id",
            "You are the one talking to the user",
            "answer in your own voice",
            "don't have to name every expert",
            "As short as the question allows",
            "Keep word for word any instruction not to do",
            "Add your own part",
        )) {
            assertTrue(prompt.contains(needle), "missing: $needle")
        }
    }

    @Test
    fun aPersonaWithoutAWhenLineFallsBackToItsDescription() {
        val quiet = MasalaSkill(
            id = "quiet",
            title = "Quiet",
            description = "Quiet expert for quiet things.",
            instructions = "i",
            persona = Persona("You are quiet."),
        )
        val prompt = AgentPrompt.build("Monday", listOf(quiet))
        assertTrue(prompt.contains("quiet: Quiet expert for quiet things."))
    }

    @Test
    fun thePromptSaysHowAppsAreMadeAndOpened() {
        val prompt = AgentPrompt.build("Monday", listOf(doctor))
        assertTrue(prompt.contains("open_mini_app"))
        assertTrue(prompt.contains("There is no separate builder to open"))
        assertTrue(prompt.contains("consult the software_engineer")
            || prompt.contains("consult the software_engineer expert"))
        assertTrue(prompt.contains("Never open a screen just because a word matched"))
    }

    @Test
    fun aRunWithNoSkillsListsNeitherSection() {
        val prompt = AgentPrompt.build("Monday", emptyList())
        assertFalse(prompt.contains("consult_expert"))
        assertFalse(prompt.contains("run_skill"))
    }

    @Test
    fun wildcardSkillsGateOnTheLastUserMessage() {
        val wild = MasalaSkill(
            id = "flight",
            title = "Flight",
            description = "Watches flight prices.",
            instructions = "i",
            keywords = listOf("flight"),
            targetPackageNames = listOf("*"),
        )
        val matching = AgentPrompt.build(
            "Monday", listOf(wild),
            context = AgentPrompt.PromptContext(lastUserText = "book my flight to Delhi"),
        )
        assertTrue(matching.contains("- flight: Watches flight prices."))
        val missing = AgentPrompt.build(
            "Monday", listOf(wild),
            context = AgentPrompt.PromptContext(lastUserText = "make dinner"),
        )
        assertFalse(missing.contains("- flight:"))
        // A blank last message lists everything (first-turn shape).
        val blank = AgentPrompt.build(
            "Monday", listOf(wild),
            context = AgentPrompt.PromptContext(lastUserText = ""),
        )
        assertTrue(blank.contains("- flight:"))
    }

    @Test
    fun fetchedSkillsAreSkipped() {
        val jobPrompt = AgentPrompt.build(
            "Monday", listOf(gameMaker),
            context = AgentPrompt.PromptContext(fetchedSkillIds = setOf("game_maker")),
        )
        assertFalse(jobPrompt.contains("game_maker:"))
        val expertPrompt = AgentPrompt.build(
            "Monday", listOf(doctor),
            context = AgentPrompt.PromptContext(fetchedSkillIds = setOf("doctor")),
        )
        assertFalse(expertPrompt.contains("doctor:"))
    }

    @Test
    fun jobsSectionPointsAtSearchSkills() {
        val prompt = AgentPrompt.build("Monday", listOf(gameMaker))
        assertTrue(prompt.contains("search_skills"))
    }

    @Test
    fun volatileTailMovesButStaticHeadStaysPut() {
        val listed = AgentPrompt.build(
            "Monday", listOf(gameMaker),
            context = AgentPrompt.PromptContext(lastUserText = "build a game"),
        )
        val fetched = AgentPrompt.build(
            "Monday", listOf(gameMaker),
            context = AgentPrompt.PromptContext(lastUserText = "build a game", fetchedSkillIds = setOf("game_maker")),
        )
        // Everything before the skills index is identical across turns; only the tail moves.
        assertEquals(listed.substringBefore("\nInstalled skills"), fetched)
        assertTrue(listed.contains("game_maker: Builds a small game"))
        assertTrue(fetched != listed)
    }
}
