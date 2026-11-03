package com.samosaai.masala.layout

import com.samosaai.masala.core.apps.AppEntry
import com.samosaai.masala.core.apps.AppKey
import com.samosaai.masala.core.apps.ProfileKind
import com.samosaai.masala.core.apps.Visibility
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.WidgetRef
import com.samosaai.masala.core.contract.WidgetSection
import com.samosaai.masala.core.layout.LayoutChecker
import com.samosaai.masala.llm.ChatMessage
import com.samosaai.masala.llm.ResponseFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

class LayoutGeneratorTest {

    private val phone = AppEntry(
        AppKey("com.example.phone", "com.example.phone.Main", 0),
        "Phone", "Phone", ProfileKind.PERSONAL, Visibility.DEFAULT, false
    )

    private fun input() = PromptContextInput(
        apps = listOf(phone),
        skills = listOf(PromptSkill("meeting_notes", "Meeting notes")),
    )

    private fun request(userText: String = "morning screen") =
        LayoutGenerator.Request(userText = userText, context = input())

    private class FakeCaller(val script: ArrayDeque<Result<String>>) : ChatCaller {
        data class Call(
            val messages: List<ChatMessage>,
            val responseFormat: ResponseFormat?,
            val maxTokens: Int,
            val temperature: Float?,
        )
        val seen = mutableListOf<Call>()
        override suspend fun call(
            messages: List<ChatMessage>,
            responseFormat: ResponseFormat?,
            maxTokens: Int,
            temperature: Float?,
        ): String {
            seen += Call(messages, responseFormat, maxTokens, temperature)
            return script.removeFirst().getOrThrow()
        }
    }

    private fun validLayout(itemsJson: String = ITEMS_JSON) = """
        {"schemaVersion":1,"pages":[{"id":"home","title":"Home","sections":[
        {"type":"header","id":"h","showClock":true,"showDate":true},
        {"type":"grid","id":"g","columns":4,"title":"Apps","items":[$itemsJson]}
        ]}]}
    """.trimIndent()

    @Test
    fun `valid first try is generated`() = runBlocking<Unit> {
        val caller = FakeCaller(ArrayDeque(listOf(Result.success(validLayout()))))
        val result = LayoutGenerator(caller).generate(request())
        val generated = assertIs<GenerateResult.Generated>(result)
        assertEquals("home", generated.layout.pages.single().id)
        assertEquals(1, caller.seen.size)
        assertEquals(null, caller.seen.single().temperature)
        val format = caller.seen.single().responseFormat
        assertEquals("json_schema", format?.type)
        val schema = format?.jsonSchema?.schema
        assertTrue(schema != null)
        assertEquals(emptyList(), LayoutSchema.strictViolations(schema), "schema sent to the model is not strict-valid")
        assertEquals(LayoutGenerator.MAX_TOKENS, caller.seen.single().maxTokens)
    }

    @Test
    fun `unknown icon is repaired on the second turn`() = runBlocking<Unit> {
        val bad = validLayout(BAD_ICON_ITEM_JSON)
        val caller = FakeCaller(ArrayDeque(listOf(Result.success(bad), Result.success(validLayout()))))
        val result = LayoutGenerator(caller).generate(request())
        assertIs<GenerateResult.Generated>(result)
        assertEquals(2, caller.seen.size)
        val repairMessages = caller.seen[1].messages
        assertEquals(listOf("system", "user", "assistant", "user"), repairMessages.map { it.role })
        assertTrue(repairMessages[1].textContent.contains("morning screen"))
        val repairText = repairMessages.last().textContent
        assertTrue(repairText.contains("Unknown icon"), repairText)
    }

    @Test
    fun `temperature is forwarded to every turn`() = runBlocking<Unit> {
        val bad = validLayout(BAD_ICON_ITEM_JSON)
        val caller = FakeCaller(ArrayDeque(listOf(Result.success(bad), Result.success(validLayout()))))
        val hot = request().copy(temperature = 0.2f)
        assertIs<GenerateResult.Generated>(LayoutGenerator(caller).generate(hot))
        assertEquals(listOf(0.2f, 0.2f), caller.seen.map { it.temperature })
    }

    @Test
    fun `twice invalid keeps the current layout`() = runBlocking<Unit> {
        val bad = validLayout(BAD_ICON_ITEM_JSON)
        val caller = FakeCaller(ArrayDeque(listOf(Result.success(bad), Result.success(bad))))
        val result = LayoutGenerator(caller).generate(request())
        val rejected = assertIs<GenerateResult.Rejected>(result)
        assertTrue(rejected.problems.isNotEmpty())
    }

    @Test
    fun `transport failure keeps the current layout`() = runBlocking<Unit> {
        val caller = FakeCaller(ArrayDeque(listOf(Result.failure(RuntimeException("timeout")))))
        assertIs<GenerateResult.Failed>(LayoutGenerator(caller).generate(request()))
    }

    @Test
    fun `schema rejection falls back to json object`() = runBlocking<Unit> {
        val caller = FakeCaller(
            ArrayDeque(
                listOf(
                    Result.failure(RuntimeException("invalid request: schema not supported")),
                    Result.success("```json\n${validLayout()}\n```"),
                )
            )
        )
        val result = LayoutGenerator(caller).generate(request())
        assertIs<GenerateResult.Generated>(result)
        assertEquals("json_object", caller.seen[1].responseFormat?.type)
        val fallbackSystem = caller.seen[1].messages.first().textContent
        assertTrue(fallbackSystem.contains("Target JSON schema"), fallbackSystem.take(200))
        assertTrue(fallbackSystem.contains("pages"), fallbackSystem.take(400))
    }

    @Test
    fun `jpeg format reaches the vision message`() = runBlocking<Unit> {
        val bad = validLayout(BAD_ICON_ITEM_JSON)
        val caller = FakeCaller(ArrayDeque(listOf(Result.success(bad), Result.success(validLayout()))))
        val vision = LayoutGenerator.Request(
            userText = "copy this",
            context = input(),
            imageBase64 = "aGVsbG8=",
            imageFormat = "jpeg",
        )
        assertIs<GenerateResult.Generated>(LayoutGenerator(caller).generate(vision))
        val url = caller.seen[0].messages[1].imageUrl()
        assertTrue(url != null && url.startsWith("data:image/jpeg;base64,aGVsbG8="), url ?: "no image")
    }

    @Test
    fun `photo request sends vision and keeps it through repair`() = runBlocking<Unit> {
        val bad = validLayout(BAD_ICON_ITEM_JSON)
        val caller = FakeCaller(ArrayDeque(listOf(Result.success(bad), Result.success(validLayout()))))
        val visionRequest = LayoutGenerator.Request(
            userText = "copy this layout",
            context = input(),
            imageBase64 = "aGVsbG8=",
        )
        val result = LayoutGenerator(caller).generate(visionRequest)
        assertIs<GenerateResult.Generated>(result)
        assertTrue(caller.seen[0].messages[1].hasImage)
        val repairMessages = caller.seen[1].messages
        assertEquals(listOf("system", "user", "assistant", "user"), repairMessages.map { it.role })
        assertTrue(repairMessages[1].hasImage)
    }

    @Test
    fun `symbol outside the request catalog is rejected`() = runBlocking<Unit> {
        val item = """
            {"id":"i1","label":"House","icon":{"type":"symbol","name":"home"},"action":{"type":"open_drawer"}}
        """.trimIndent()
        val restricted = input().copy(symbols = setOf("call"))
        val caller = FakeCaller(ArrayDeque(listOf(Result.success(validLayout(item)), Result.success(validLayout(item)))))
        val result = LayoutGenerator(caller).generate(LayoutGenerator.Request(userText = "x", context = restricted))
        val rejected = assertIs<GenerateResult.Rejected>(result)
        assertTrue(rejected.problems.any { it.message.contains("Unknown icon") }, rejected.problems.toString())
    }

    @Test
    fun `a widget ref section passes against the library`() = runBlocking<Unit> {
        val withWidget = """
            {"schemaVersion":3,"pages":[{"id":"home","title":"Home","sections":[
            {"type":"widget","id":"w","ref":{"widgetId":"water"}}
            ]}]}
        """.trimIndent()
        val water = com.samosaai.masala.core.contract.WidgetSpec("water", components = listOf(com.samosaai.masala.core.contract.WidgetComponent.Text("Hi")))
        val caller = FakeCaller(ArrayDeque(listOf(Result.success(withWidget))))
        val result = LayoutGenerator(caller).generate(request().copy(library = mapOf("water" to water)))
        val generated = assertIs<GenerateResult.Generated>(result)
        assertEquals(1, generated.layout.pages.single().sections.size)
    }

    @Test
    fun `a revise that drops every widget is rejected`() = runBlocking<Unit> {
        val revise = LayoutDoc(
            pages = listOf(
                com.samosaai.masala.core.contract.Page(
                    "home",
                    sections = listOf(WidgetSection("w", ref = com.samosaai.masala.core.contract.WidgetRef("water"))),
                ),
            ),
        )
        val dropped = """
            {"schemaVersion":3,"pages":[{"id":"home","title":"Home","sections":[
            {"type":"header","id":"h"}
            ]}]}
        """.trimIndent()
        val water = com.samosaai.masala.core.contract.WidgetSpec("water", components = listOf(com.samosaai.masala.core.contract.WidgetComponent.Text("Hi")))
        val caller = FakeCaller(ArrayDeque(listOf(Result.success(dropped), Result.success(dropped))))
        val result = LayoutGenerator(caller).generate(request().copy(revise = revise, library = mapOf("water" to water)))
        val rejected = assertIs<GenerateResult.Rejected>(result)
        assertTrue(rejected.problems.any { it.message.contains("dropped every widget") }, rejected.problems.toString())
    }

    @Test
    fun `empty grid is rejected`() = runBlocking<Unit> {
        val empty = """
            {"schemaVersion":1,"pages":[{"id":"home","title":"Home","sections":[
            {"type":"grid","id":"g","columns":4,"title":"Apps","items":[]}
            ]}]}
        """.trimIndent()
        val caller = FakeCaller(ArrayDeque(listOf(Result.success(empty), Result.success(empty))))
        val result = LayoutGenerator(caller).generate(request())
        assertIs<GenerateResult.Rejected>(result)
    }

    @Test
    fun `http 400 naming the schema counts as schema rejection`() {
        val body = """{"error":{"message":"response_format json_schema is not supported"}}"""
            .toResponseBody("application/json".toMediaType())
        val e = retrofit2.HttpException(retrofit2.Response.error<String>(400, body))
        assertTrue(LayoutGenerator.isSchemaRejection(e))
    }

    @Test
    fun `plain errors are not schema rejections`() {
        assertTrue(!LayoutGenerator.isSchemaRejection(RuntimeException("timeout")))
    }

    @Test
    fun `cut-off reply names the cutoff and recovers`() = runBlocking<Unit> {
        val full = validLayout()
        val cut = full.take(full.length / 2)
        assertTrue(!cut.trimEnd().endsWith("}"))
        val caller = FakeCaller(ArrayDeque(listOf(Result.success(cut), Result.success(full))))
        val result = LayoutGenerator(caller).generate(request())
        assertIs<GenerateResult.Generated>(result)
        val repairText = caller.seen[1].messages.last().textContent
        assertTrue(repairText.contains("cut off"), repairText.take(300))
    }

    @Test
    fun `blank first reply is repaired, not failed`() = runBlocking<Unit> {
        val caller = FakeCaller(ArrayDeque(listOf(Result.success(""), Result.success(validLayout()))))
        val result = LayoutGenerator(caller).generate(request())
        assertIs<GenerateResult.Generated>(result)
        assertEquals(2, caller.seen.size)
    }

    @Test
    fun `revise carries the full current layout`() = runBlocking<Unit> {
        val revised = validLayout()
        val caller = FakeCaller(ArrayDeque(listOf(Result.success(revised))))
        val base = LayoutChecker.parse(validLayout()).layout!!
        val req = request().copy(userText = "add a dock", revise = base)
        assertIs<GenerateResult.Generated>(LayoutGenerator(caller).generate(req))
        val system = caller.seen.single().messages.first().textContent
        assertTrue(system.contains("revise THIS document"), system.take(300))
        assertTrue(system.contains("\"id\": \"home\""), system.take(2000))
        val user = caller.seen.single().messages[1].textContent
        assertTrue(user.contains("Change request"), user.take(200))
        assertTrue(user.contains("add a dock"), user.take(300))
    }

    @Test
    fun `fresh generation sends the bare user text`() {
        val messages = LayoutGenerator.buildMessages(request("morning screen"))
        assertEquals("morning screen", messages[1].textContent)
    }

    @Test
    fun `blank skill prompt normalizes to absent`() = runBlocking<Unit> {
        val item = """
            {"id":"i9","label":"Notes","icon":{"type":"symbol","name":"mic"},
             "action":{"type":"run_skill","skillId":"meeting_notes","prompt":""}}
        """.trimIndent()
        val caller = FakeCaller(ArrayDeque(listOf(Result.success(validLayout(item)))))
        val result = LayoutGenerator(caller).generate(request())
        val generated = assertIs<GenerateResult.Generated>(result)
        val action = generated.layout.pages.single().sections
            .filterIsInstance<com.samosaai.masala.core.contract.ItemSection>()
            .flatMap { it.items }
            .single { it.id == "i9" }.action
        assertEquals(null, (action as MasalaAction.RunSkill).prompt)
    }

    @Test
    fun `system prompt explains the photo`() {
        val system = LayoutGenerator.buildMessages(request()).first().textContent
        assertTrue(system.contains("photo", ignoreCase = true), system.take(200))
    }

    companion object {
        private const val ITEMS_JSON = """
            {"id":"i1","label":"Phone","icon":{"type":"app","packageName":"com.example.phone","activity":"com.example.phone.Main","user":0},"action":{"type":"launch_app","packageName":"com.example.phone","activity":"com.example.phone.Main","user":0}},
            {"id":"i2","label":"Alarm","icon":{"type":"symbol","name":"alarm"},"action":{"type":"set_alarm","hour":7,"minute":30,"label":"Standup"}}
        """

        private const val BAD_ICON_ITEM_JSON = """
            {"id":"i1","label":"Phone","icon":{"type":"symbol","name":"nope"},"action":{"type":"open_drawer"}}
        """
    }
}
