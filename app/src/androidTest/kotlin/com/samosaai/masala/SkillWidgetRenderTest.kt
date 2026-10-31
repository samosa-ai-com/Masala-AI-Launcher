package com.samosaai.masala

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samosaai.masala.core.contract.ContextSnapshot
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.contract.SkillInput
import com.samosaai.masala.core.contract.SkillWidget
import com.samosaai.masala.core.layout.LayoutResolver
import com.samosaai.masala.data.LauncherSettings
import com.samosaai.masala.data.SkillRunEntity
import com.samosaai.masala.llm.ChatMessage
import com.samosaai.masala.ui.home.LayoutRenderer
import com.samosaai.masala.ui.home.RenderContext
import com.samosaai.masala.ui.home.SkillWidgetSource
import com.samosaai.masala.ui.theme.MasalaTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/** A skill widget on home: title, the latest result line, and the quick input reaching the dispatcher as `RunSkill`. */
@RunWith(AndroidJUnit4::class)
class SkillWidgetRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<MasalaApp>()

    private val chef = MasalaSkill(
        id = "chef",
        title = "Chef",
        instructions = "Cook.",
        widget = SkillWidget(icon = "restaurant", inputs = listOf(SkillInput.TEXT)),
    )

    private val transcript = MasalaJson.strict.encodeToString(
        ListSerializer(ChatMessage.serializer()),
        listOf(ChatMessage("user", JsonPrimitive("eggs")), ChatMessage("assistant", JsonPrimitive("Make an omelette"))),
    )

    @Test
    fun widgetShowsLatestResultAndSendsPromptAsRunSkill() {
        val layout: LayoutDoc = MasalaJson.strict.decodeFromString(
            """{"schemaVersion":1,"pages":[{"id":"p","sections":[{"type":"skill_widget","id":"w","skillId":"chef"}]}]}""",
        )
        val now = LocalDateTime.parse("2026-10-27T07:30")
        val resolved = LayoutResolver.resolve(layout, emptyList(), ContextSnapshot(now = now))
        val actions = mutableListOf<MasalaAction>()
        val source = object : SkillWidgetSource {
            override fun skill(id: String) = chef.takeIf { it.id == id }
            override fun latest(skillId: String): Flow<SkillRunEntity?> =
                flowOf(SkillRunEntity(id = 1, skillId = skillId, prompt = "eggs", status = "done", transcriptJson = transcript, startedAt = 1))
        }

        compose.setContent {
            MasalaTheme(LauncherSettings()) {
                LayoutRenderer(
                    resolved,
                    RenderContext(app.icons, emptyList(), now, locked = false, onAction = { actions += it }, onAppMenu = {}, onClockClick = {}, skillWidgets = source),
                )
            }
        }

        compose.onNodeWithText("Chef").assertIsDisplayed()
        compose.onNodeWithText("Make an omelette").assertIsDisplayed()
        compose.onNodeWithText("Ask Chef").performTextInput("pasta")
        compose.onNodeWithText("Send").performClick()
        assertEquals(listOf<MasalaAction>(MasalaAction.RunSkill("chef", "pasta")), actions)
    }

    @Test
    fun scheduledBriefShowsMarkdownAgeAndReadAloud() {
        val brief = MasalaSkill(
            id = "daily_brief",
            title = "Daily brief",
            instructions = "Brief.",
            widget = SkillWidget(icon = "wb_sunny"),
            schedule = com.samosaai.masala.core.contract.SkillSchedule("daily 07:30"),
        )
        val answer = "**Today**\n- 09:30 Standup\n\n**Weather**\nLight rain"
        val json = MasalaJson.strict.encodeToString(ListSerializer(ChatMessage.serializer()), listOf(ChatMessage("assistant", JsonPrimitive(answer))))
        val yesterday = System.currentTimeMillis() - 24 * 3_600_000L
        val spoken = mutableListOf<String>()
        val ranNow = mutableListOf<String>()
        val source = object : SkillWidgetSource {
            override fun skill(id: String) = brief.takeIf { it.id == id }
            override fun latest(skillId: String): Flow<SkillRunEntity?> =
                flowOf(SkillRunEntity(id = 3, skillId = skillId, prompt = "run", status = "done", transcriptJson = json, startedAt = yesterday, finishedAt = yesterday))
            override suspend fun speak(text: String): String? { spoken += text; return null }
            override suspend fun runNow(skillId: String): String? { ranNow += skillId; return null }
        }
        val layout: LayoutDoc = MasalaJson.strict.decodeFromString(
            """{"schemaVersion":1,"pages":[{"id":"p","sections":[{"type":"skill_widget","id":"w","skillId":"daily_brief","size":"large"}]}]}""",
        )
        val now = LocalDateTime.now()
        val resolved = LayoutResolver.resolve(layout, emptyList(), ContextSnapshot(now = now))
        compose.setContent {
            MasalaTheme(LauncherSettings()) {
                LayoutRenderer(resolved, RenderContext(app.icons, emptyList(), now, locked = false, onAction = {}, onAppMenu = {}, onClockClick = {}, skillWidgets = source))
            }
        }
        compose.onNodeWithText("Daily brief").assertIsDisplayed()
        compose.onNodeWithText("Yesterday", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Light rain", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Read aloud").performClick()
        compose.waitUntil(5_000) { spoken.isNotEmpty() }
        assertEquals("Today.\n09:30 Standup.\nWeather.\nLight rain.", spoken.single())
        compose.onNodeWithText("Run now").performClick()
        compose.waitUntil(5_000) { ranNow.isNotEmpty() }
        assertEquals(listOf("daily_brief"), ranNow)
    }
}
