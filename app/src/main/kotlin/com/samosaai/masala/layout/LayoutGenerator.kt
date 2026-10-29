package com.samosaai.masala.layout

import com.samosaai.masala.core.contract.ItemSection
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.StackSection
import com.samosaai.masala.core.contract.WidgetSection
import com.samosaai.masala.core.layout.LayoutChecker
import com.samosaai.masala.llm.ChatMessage
import com.samosaai.masala.llm.ResponseFormat
import com.samosaai.masala.llm.visionUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Sentence or photo → validated layout (plan Phase 3, lane C).
 *
 * Request → structured output → [LayoutChecker] → one repair turn with the error list → if still
 * failing, keep the current layout. Never applies a layout that failed: transport trouble gives
 * [GenerateResult.Failed], checker trouble gives [GenerateResult.Rejected], and only a clean
 * document gives [GenerateResult.Generated]. The caller applies it with a prompt-labeled version
 * (`LayoutRepository.apply`) or leaves the current layout untouched.
 *
 * The model endpoint is behind [ChatCaller], so every path is testable without a server. The real
 * wiring (GenerateSheet milestone) calls `LLMClient.chat` with the schema and [MAX_TOKENS].
 */
class LayoutGenerator(
    private val caller: ChatCaller,
    private val maxTokens: Int = MAX_TOKENS,
) {
    /**
     * What to generate from: the request text, the grounded context, and optionally a photo
     * (photo picker → base64, no data-URI prefix; JPEG or PNG via [imageFormat]).
     */
    data class Request(
        val userText: String,
        val context: PromptContextInput,
        val imageBase64: String? = null,
        val imageFormat: String = "png",
        /** Non-zero skips the response cache (Regenerate); null reuses identical replies. */
        val temperature: Float? = null,
        /**
         * Revise this layout instead of starting fresh: the full document rides in the system
         * prompt and the model returns the complete revised layout. Null generates from scratch.
         */
        val revise: LayoutDoc? = null,
    )

    suspend fun generate(request: Request): GenerateResult {
        val symbols = request.context.symbols
        val schema = LayoutSchema.schema(symbols)
        val messages = buildMessages(request)
        val system = (messages[0].content as JsonPrimitive).content
        val user = messages[1]
        val first = try {
            caller.call(messages, ResponseFormat.jsonSchema("layout", schema), maxTokens, request.temperature)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isSchemaRejection(e)) return generateJsonObject(request, system, user, schema)
            return GenerateResult.Failed(short(e))
        }
        val firstResult = checkText(first, request)
        if (firstResult is GenerateResult.Generated) return firstResult
        val problems = (firstResult as GenerateResult.Rejected).problems

        // Repair keeps the full history: the model still sees the request (and photo). A reply
        // cut off mid-JSON (length stop) says so explicitly, or the model truncates again.
        val repairNote = if (extractJsonObject(first) == null && '{' in first) {
            "Your previous reply was cut off mid-JSON. Return the COMPLETE layout object; " +
                "keep it compact so it fits."
        } else {
            "Return the full corrected layout."
        }
        val repair = messages + listOf(
            ChatMessage(role = "assistant", content = JsonPrimitive(first)),
            ChatMessage(
                role = "user",
                content = JsonPrimitive(
                    "That layout has these problems:\n" +
                        problems.joinToString("\n") { "- $it" } +
                        "\n$repairNote"
                )
            ),
        )
        val second = try {
            caller.call(repair, ResponseFormat.jsonSchema("layout", schema), maxTokens, request.temperature)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return GenerateResult.Failed(short(e))
        }
        return checkText(second, request)
    }

    /** `json_object` fallback (plan §Phase 0.1): schema in the prompt, same checker, no repair. */
    private suspend fun generateJsonObject(
        request: Request,
        system: String,
        user: ChatMessage,
        schema: JsonObject
    ): GenerateResult {
        // The schema rides in the prompt because there is no other way to send it.
        val promptWithSchema = "$system\n\nTarget JSON schema:\n$schema\n\nReturn ONLY the layout object. No prose."
        val second = try {
            caller.call(
                listOf(
                    ChatMessage(role = "system", content = JsonPrimitive(promptWithSchema)),
                    user,
                ),
                ResponseFormat.JSON_OBJECT,
                maxTokens,
                request.temperature,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return GenerateResult.Failed(short(e))
        }
        val json = extractJsonObject(second)
            ?: return GenerateResult.Rejected(
                listOf(LayoutChecker.Problem("$", "The reply held no layout object"))
            )
        return checkText(json, request)
    }

    private fun checkText(raw: String, request: Request): GenerateResult {
        // Fences and filler never reach the checker on any turn.
        val parsed = LayoutChecker.parse(extractJsonObject(raw) ?: raw)
        val layout = parsed.layout ?: return GenerateResult.Rejected(parsed.problems)
        val problems = (parsed.problems + meaningProblems(layout, request)).toMutableList()
        problems += LayoutChecker.check(layout, checkerEnv(request))
        if (problems.isEmpty()) return GenerateResult.Generated(normalize(layout))
        return GenerateResult.Rejected(problems)
    }

    private fun checkerEnv(request: PromptContextInput): LayoutChecker.Environment {
        val installed = mutableMapOf<String, MutableSet<Long>>()
        request.apps.forEach { installed.getOrPut(it.key.packageName) { mutableSetOf() } += it.key.user }
        // Phase 3 generates no tools and no modes: unknown names fail instead of slipping through.
        return LayoutChecker.Environment(
            installedApps = installed,
            skillIds = request.skills.map { it.id }.toSet(),
            symbols = request.symbols,
            toolNames = emptySet(),
            modeIds = emptySet(),
        )
    }

    private fun checkerEnv(request: Request): LayoutChecker.Environment = checkerEnv(request.context)

    /**
     * Meaning checks the schema can't say: no generated-widget sections (Phase 6), no override
     * icons (later), and no empty item sections — the model emits those when it is vague, so they
     * trigger the repair turn instead of shipping a hollow home screen.
     */
    internal fun meaningProblems(layout: LayoutDoc, request: Request): List<LayoutChecker.Problem> {
        val out = mutableListOf<LayoutChecker.Problem>()
        layout.pages.forEachIndexed { p, page ->
            page.sections.forEachIndexed { s, section ->
                val path = "$.pages[$p].sections[$s]"
                if (section is WidgetSection || section is StackSection) {
                    out += LayoutChecker.Problem(path, "Generated widgets arrive in a later build; use grid or list sections")
                }
                if (section is ItemSection && section.items.isEmpty()) {
                    out += LayoutChecker.Problem("$path.items", "Empty section: add items or drop the section")
                }
                if (section is ItemSection) {
                    section.items.forEachIndexed { i, item ->
                        if (item.icon is com.samosaai.masala.core.contract.IconRef.Override) {
                            out += LayoutChecker.Problem("$path.items[$i].icon", "Generated icons arrive in a later build; use an app or symbol icon")
                        }
                    }
                }
            }
        }
        return out
    }

    /** Blank skill prompts become absent, so Phase 4 treats "no prompt" and "empty prompt" the same. */
    internal fun normalize(layout: LayoutDoc): LayoutDoc = layout.copy(
        pages = layout.pages.map { page ->
            page.copy(
                sections = page.sections.map { section ->
                    if (section is ItemSection) {
                        section.withItems(section.items.map { item ->
                            val action = item.action
                            if (action is MasalaAction.RunSkill && (action.prompt ?: "").isBlank()) {
                                item.copy(action = action.copy(prompt = null))
                            } else item
                        })
                    } else section
                }
            )
        }
    )

    /** The exact messages a turn sends: system (rules + grounded context), then the user turn. */
    internal fun buildMessages(request: Request): List<ChatMessage> = Companion.buildMessages(request)

    companion object {
        /**
         * Reasoning-heavy model: the model check needed 3000+, and real phone layouts run larger
         * than the test fixtures, so truncations ("unexpected end of input") get headroom.
         */
        const val MAX_TOKENS = 8000

        /** The exact messages a turn sends: system (rules + grounded context), then the user turn. */
        internal fun buildMessages(request: Request): List<ChatMessage> {
            val contextText = PromptContext.build(request.context)
            val reviseBlock = request.revise?.let {
                "\n\nYou are revising an existing layout — revise THIS document, do not start fresh. " +
                    "Apply ONLY the change the user asks for and return the COMPLETE revised layout, " +
                    "preserving every id, section and item the request doesn't touch.\nCurrent layout JSON:\n" +
                    MasalaJson.strict.encodeToString(LayoutDoc.serializer(), it)
            }.orEmpty()
            val system = SYSTEM_PROMPT + "\n\n" + contextText + reviseBlock
            val userText = if (request.revise == null) {
                request.userText
            } else {
                "Change request for the current layout in the system prompt — " +
                    "return the complete revised layout object:\n" + request.userText
            }
            val user = if (request.imageBase64 == null) {
                ChatMessage(role = "user", content = JsonPrimitive(userText))
            } else {
                visionUserMessage(userText, request.imageBase64, request.imageFormat)
            }
            return listOf(
                ChatMessage(role = "system", content = JsonPrimitive(system)),
                user,
            )
        }

        internal fun short(e: Exception): String {
            val detail = (e.message ?: "").take(300)
            return if (detail.isBlank()) e::class.simpleName ?: "error" else "${e::class.simpleName}: $detail"
        }

        internal val SYSTEM_PROMPT = """
            You design an Android home-screen layout. Reply with ONLY the layout object, no prose.
            Rules: every page, section and item needs a unique id. Use only the installed apps listed below (copy package, activity and user exactly). Use only the listed symbol names. Every grid, list, dock or group section needs at least one item. Put a clock header first. Never invent packages, icons or skills. A photo may be attached: sketches and screenshots define the layout structure, scenes set the theme. The text request overrides the photo where they conflict.
        """.trimIndent()

        internal fun isSchemaRejection(e: Exception): Boolean {
            if ((e.message ?: "").contains("schema", ignoreCase = true)) return true
            // Retrofit reports "HTTP 400 Bad Request"; the server's reason is in the error body.
            if (e is retrofit2.HttpException && e.code() == 400) {
                val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull() ?: ""
                return body.contains("schema", ignoreCase = true) ||
                    body.contains("response_format", ignoreCase = true)
            }
            return false
        }

        /** First balanced `{…}` object in the text (fences and prose tolerated). Null if none. */
        internal fun extractJsonObject(text: String): String? {
            var start = -1
            var depth = 0
            var inString = false
            var escaped = false
            text.forEachIndexed { i, c ->
                if (start < 0) {
                    if (c == '{') {
                        start = i
                        depth = 1
                    }
                    return@forEachIndexed
                }
                if (inString) {
                    if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
                } else {
                    when (c) {
                        '"' -> inString = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) return text.substring(start, i + 1)
                        }
                    }
                }
            }
            return null
        }
    }
}

/** The model endpoint behind the generator: assistant text out, transport trouble as an exception. */
fun interface ChatCaller {
    suspend fun call(
        messages: List<ChatMessage>,
        responseFormat: ResponseFormat?,
        maxTokens: Int,
        /** Null/0 hits the client's response cache; non-zero always reaches the model. */
        temperature: Float?,
    ): String
}

sealed interface GenerateResult {
    /** A clean document. The caller applies it with a prompt-labeled version. */
    data class Generated(val layout: LayoutDoc) : GenerateResult

    /** At least one problem survived the repair turn. Keep the current layout. */
    data class Rejected(val problems: List<LayoutChecker.Problem>) : GenerateResult

    /** Transport trouble (no usable reply). Keep the current layout. */
    data class Failed(val message: String) : GenerateResult
}
