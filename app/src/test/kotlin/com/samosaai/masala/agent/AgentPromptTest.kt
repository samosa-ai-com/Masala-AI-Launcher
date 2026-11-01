package com.samosaai.masala.agent

import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.contract.Persona
import kotlin.test.Test
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
    fun aRunWithNoSkillsListsNeitherSection() {
        val prompt = AgentPrompt.build("Monday", emptyList())
        assertFalse(prompt.contains("consult_expert"))
        assertFalse(prompt.contains("run_skill"))
    }
}
