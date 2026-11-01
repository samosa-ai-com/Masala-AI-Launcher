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
        persona = Persona("You are a GP."),
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
        assertTrue(experts.contains("doctor: Health questions"))
        // The job is not offered as an expert, and the expert is not offered as a job.
        val jobs = prompt.substringAfter("Installed skills (start one with run_skill")
        assertTrue(jobs.contains("game_maker: Builds a small game"))
        assertFalse(jobs.contains("doctor:"))
    }

    @Test
    fun thePromptSaysToCreditTheExpertAndContinueById() {
        val prompt = AgentPrompt.build("Monday", listOf(doctor))
        assertTrue(prompt.contains("consult_id"))
        assertTrue(prompt.contains("Credit an expert by name"))
        assertTrue(prompt.contains("never claim to be the expert"))
    }

    @Test
    fun aRunWithNoSkillsListsNeitherSection() {
        val prompt = AgentPrompt.build("Monday", emptyList())
        assertFalse(prompt.contains("consult_expert"))
        assertFalse(prompt.contains("run_skill"))
    }
}
