package com.samosaai.masala.wallpapers

import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.core.wallpaper.RoutineChecker
import com.samosaai.masala.core.wallpaper.WallpaperRoutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The two routine tools: save a routine the model
 * authored, and explain what is showing now and why.
 *
 * A routine is checked before it is stored — shape, time windows, the home-only target — and a
 * rejected one comes back as readable path-and-reason lines so the agent can repair it in place.
 */
class WallpaperRoutineTools(
    private val store: WallpaperRoutineStore,
    /** Called after a save so the new routine can apply at once. */
    private val refresh: () -> Unit = {},
    /** The engine's last decision, for `wallpaper_routine_status`. */
    private val statusOf: () -> WallpaperRoutineEngine.Status = { WallpaperRoutineEngine.Status() },
    /**
     * Why a source can't be used on this phone (unknown bundled file, no such generated
     * wallpaper), or null when it looks fine. Cheaper than saving a routine that fails at 06:00.
     */
    private val validateSource: suspend (String) -> String? = { null },
) {

    /** `set_wallpaper_routine`: replaces the routine with the same id, or adds a new one. */
    suspend fun set(args: JsonObject): ActionResult {
        val json = args.jsonObject
        val name = (json["name"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val id = (json["id"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }
            ?: slug(name)
        if (id.isBlank()) return ActionResult.error("Give the routine a name (and an id if the name has no letters).")
        val routine = runCatching {
            // The id is filled in when the model leaves it out; everything else is strict so a
            // misspelled condition is reported instead of silently dropped.
            val withId = JsonObject(json + ("id" to JsonPrimitive(id)))
            MasalaJson.strict.decodeFromJsonElement(WallpaperRoutine.serializer(), withId)
        }.getOrElse {
            return ActionResult.error("That routine isn't valid JSON: ${it.message?.lineSequence()?.firstOrNull()}")
        }
        val problems = RoutineChecker.check(routine).toMutableList()
        val sources = routine.rules.map { it.source } + listOfNotNull(routine.defaultSource)
        sources.forEachIndexed { index, source ->
            validateSource(source)?.let { reason ->
                problems += com.samosaai.masala.core.wallpaper.RoutineProblem("$.sources[$index]", reason)
            }
        }
        if (problems.isNotEmpty()) {
            return ActionResult.error(
                "The routine was not saved:\n" + problems.joinToString("\n") { "- ${it.path}: ${it.message}" },
            )
        }
        return runCatching {
            store.save(routine)
            refresh()
            ActionResult.ok(
                "Saved '${routine.name}' (${routine.id}) with ${routine.rules.size} rules" +
                    if (routine.defaultSource != null) " and a default." else ".",
            )
        }.getOrElse { ActionResult.error("Couldn't save the routine: ${it.message}") }
    }

    /** `wallpaper_routine_status`: what wins now, why, and when it next changes. */
    suspend fun status(): ActionResult {
        val routines = runCatching { store.all() }.getOrElse {
            return ActionResult.error("Couldn't read the routines: ${it.message}")
        }
        if (routines.isEmpty()) {
            return ActionResult.ok("No wallpaper routines yet. Set one with set_wallpaper_routine.")
        }
        val current = statusOf()
        val enabled = routines.count { it.enabled }
        val lines = buildString {
            append("${routines.size} routine(s), $enabled enabled.")
            if (current.error != null) {
                append("\nLast attempt failed: ${current.error}")
            } else if (current.routineName != null) {
                append("\nShowing: '${current.routineName}'")
                if (current.ruleIndex != null) append(" rule ${current.ruleIndex + 1}") else append(" default")
                append(" — ${current.source}.")
            } else {
                append("\nNothing has been applied yet.")
            }
            current.nextChange?.let { append("\nNext change: $it.") }
        }
        return ActionResult.ok(lines)
    }

    /** A filesystem-safe id from a name; "Evening" -> "evening". */
    private fun slug(name: String): String = name.lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .trim('-')
        .replace(Regex("-+"), "-")
        .take(64)
}
