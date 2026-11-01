package com.samosaai.masala.widgets

import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.contract.WidgetSpec
import com.samosaai.masala.core.layout.LayoutChecker
import com.samosaai.masala.core.layout.LayoutEdits
import com.samosaai.masala.core.widgets.WidgetChecker
import com.samosaai.masala.core.widgets.WidgetTemplates
import com.samosaai.masala.layout.ChatCaller
import com.samosaai.masala.llm.ChatMessage
import com.samosaai.masala.llm.ResponseFormat
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Sentence → validated widget (plan `docs/plans/widget-maker.md` §7.3), the same shape as
 * `LayoutGenerator`: structured output → [WidgetChecker] → one repair turn with the error list →
 * if still failing, keep nothing. Never saves a widget that failed: transport trouble gives
 * [Result.Failed], checker trouble gives [Result.Rejected], and only a clean spec gives
 * [Result.Generated]. The caller saves it (`WidgetLibrary.save`) or leaves the library untouched.
 */
class WidgetGenerator(
    private val caller: ChatCaller,
    private val maxTokens: Int = MAX_TOKENS,
) {
    data class Request(
        val userText: String,
        val homeSummary: String,
        val templates: List<com.samosaai.masala.core.widgets.WidgetTemplate> = WidgetTemplates.all(),
        val skillIds: Set<String> = emptySet(),
        val revise: WidgetSpec? = null,
    )

    sealed interface Result {
        data class Generated(val spec: WidgetSpec) : Result
        data class Rejected(val problems: List<LayoutChecker.Problem>) : Result
        data class Failed(val message: String) : Result
    }

    suspend fun generate(
        request: Request,
        /** Extra checks on a clean spec (the caller places it in the real layout); empties pass. */
        probe: suspend (WidgetSpec) -> List<LayoutChecker.Problem> = { emptyList() },
    ): Result {
        val system = buildSystem(request)
        val user = ChatMessage(
            role = "user",
            content = JsonPrimitive(
                (request.revise?.let { "Revise this widget (return the COMPLETE spec with the same id):\n${MasalaJson.strict.encodeToString(WidgetSpec.serializer(), it)}\n\nChange: " } ?: "Build this widget:\n") + request.userText,
            ),
        )
        val messages = listOf(ChatMessage(role = "system", content = JsonPrimitive(system)), user)
        val schema = WidgetSchema.schema(request.skillIds)
        val first = try {
            caller.call(messages, ResponseFormat.jsonSchema("widget", schema), maxTokens, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.Failed(short(e))
        }
        val firstResult = checkText(first, request)
        if (firstResult is Result.Generated) return checkProbe(firstResult, probe)
        val problems = (firstResult as Result.Rejected).problems
        val repair = messages + listOf(
            ChatMessage(role = "assistant", content = JsonPrimitive(first)),
            ChatMessage(
                role = "user",
                content = JsonPrimitive(
                    "That widget has these problems:\n" + problems.joinToString("\n") { "- $it" } +
                        "\nReturn the full corrected spec.",
                ),
            ),
        )
        val second = try {
            caller.call(repair, ResponseFormat.jsonSchema("widget", schema), maxTokens, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.Failed(short(e))
        }
        return when (val checked = checkText(second, request)) {
            is Result.Generated -> checkProbe(checked, probe)
            else -> checked
        }
    }

    private fun buildSystem(request: Request): String = buildString {
        append("You build one home-screen widget as a WidgetSpec. Return ONLY the spec object. ")
        append("At most 12 components; rows and columns hold leaves only and nest at most 2 deep; ")
        append("one storage key per component (a counter, checklist, timer or list line owns its key); ")
        append("styling stays bounded: a palette tint, normal or large emphasis, start/center/end. ")
        append("Buttons only ask_masala, run_skill (an installed id, exactly as listed), start_timer, or refresh_widget (reloads this widget's web values in place; no id needed). ")
        append("A web_value reads one JSON value over http (its host is approved at placement); video and snapshot need a source id from list_stream_sources. ")
        append("Ids are short slugs; titles are short. Counters start at 0, checklists unticked.\n")
        if (request.skillIds.isNotEmpty()) {
            append("Installed skills (run_skill ids): ${request.skillIds.sorted().joinToString(", ")}.\n")
        }
        append("Starting points (id: what it is):\n")
        request.templates.forEach { append("- ${it.id}: ${it.description}\n") }
        append("Home now:\n${request.homeSummary}")
    }

    private fun checkText(raw: String, request: Request): Result {
        val parsed = WidgetChecker.parse(WidgetGenerator.extractJsonObject(raw) ?: raw)
        val spec = parsed.widget ?: return Result.Rejected(parsed.problems)
        val problems = (parsed.problems + meaningProblems(spec, request)).toMutableList()
        problems += WidgetChecker.check(spec, checkerEnv(request))
        if (problems.isEmpty()) return Result.Generated(normalize(spec, request))
        return Result.Rejected(problems)
    }

    private fun checkerEnv(request: Request): LayoutChecker.Environment = LayoutChecker.Environment(
        installedApps = emptyMap(),
        skillIds = request.skillIds,
        toolNames = emptySet(),
        modeIds = emptySet(),
    )

    /**
     * Meaning checks the schema can't say: the id stays a slug (refs and storage are keyed on
     * it), a revision keeps its id, and generated specs hold nothing the W1 renderer can't draw.
     */
    internal fun meaningProblems(spec: WidgetSpec, request: Request): List<LayoutChecker.Problem> {
        val out = mutableListOf<LayoutChecker.Problem>()
        if (!WidgetIdRule.test(spec.id)) out += LayoutChecker.Problem("$.id", "id '$spec.id' must be a short slug: lowercase letters, digits, _ and -")
        if (request.revise != null && spec.id != request.revise.id) {
            out += LayoutChecker.Problem("$.id", "A revision keeps the id '${request.revise.id}'")
        }
        return out
    }

    /**
     * The probe (plan §7.3): a clean spec must also survive inside a real layout — the caller
     * places it on the first page and checks against the phone's environment. Off-screen draw
     * overflow stays a W3 check on the device, not here.
     */
    private suspend fun checkProbe(generated: Result.Generated, probe: suspend (WidgetSpec) -> List<LayoutChecker.Problem>): Result {
        val problems = probe(generated.spec)
        return if (problems.isEmpty()) generated else Result.Rejected(problems)
    }

    private fun normalize(spec: WidgetSpec, request: Request): WidgetSpec {
        val id = spec.id.trim()
        return spec.copy(id = id, title = spec.title?.trim()?.ifEmpty { null })
    }

    companion object {
        const val MAX_TOKENS = 2000

        internal fun extractJsonObject(raw: String): String? {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            return if (start >= 0 && end > start) raw.substring(start, end + 1) else null
        }

        private fun short(e: Exception) = (e.message ?: e.javaClass.simpleName).take(200)
    }
}

/** What a widget id may hold: slugs only, so refs and storage keys stay readable. */
internal object WidgetIdRule {
    private val rule = Regex("^[a-z][a-z0-9_-]{0,63}$")
    fun test(id: String) = rule.matches(id)
}
