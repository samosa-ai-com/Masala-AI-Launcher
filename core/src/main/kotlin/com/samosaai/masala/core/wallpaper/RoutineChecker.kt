package com.samosaai.masala.core.wallpaper

/**
 * Checks a routine before it is saved: shape, sane
 * windows, existing wallpaper ids, a bounded rule list. Problems carry JSON paths so a repair
 * turn knows what to fix, the same way [com.samosaai.masala.core.layout.LayoutChecker] does for
 * layouts.
 */
object RoutineChecker {

    const val MAX_RULES = 8
    const val MAX_NAME = 60
    const val MAX_ID = 64

    fun check(routine: WallpaperRoutine): List<RoutineProblem> {
        val problems = mutableListOf<RoutineProblem>()
        if (routine.id.isBlank()) problems += RoutineProblem("$.id", "id is empty")
        if (routine.id.length > MAX_ID) problems += RoutineProblem("$.id", "id has at most $MAX_ID characters")
        if (routine.name.isBlank()) problems += RoutineProblem("$.name", "name is empty")
        if (routine.name.length > MAX_NAME) problems += RoutineProblem("$.name", "name has at most $MAX_NAME characters")
        if (routine.target != "home") {
            problems += RoutineProblem("$.target", "only 'home' is supported; lock-screen routines are not available")
        }
        if (routine.rules.size > MAX_RULES) {
            problems += RoutineProblem("$.rules", "at most $MAX_RULES rules (has ${routine.rules.size})")
        }
        if (routine.rules.isEmpty() && routine.defaultSource == null) {
            problems += RoutineProblem("$.rules", "a routine needs at least one rule or a default wallpaper")
        }
        routine.defaultSource?.let { sourceProblem(it, "$.defaultSource", problems) }
        routine.rules.forEachIndexed { index, rule ->
            val path = "$.rules[$index]"
            sourceProblem(rule.source, "$path.source", problems)
            val conditions = rule.conditions
            val from = conditions.from
            val to = conditions.to
            if ((from == null) != (to == null)) {
                problems += RoutineProblem("$path.when", "give both from and to, or neither")
            }
            if (from != null && to != null) {
                val fromMinute = RoutineEvaluator.minutesOf(from)
                val toMinute = RoutineEvaluator.minutesOf(to)
                if (fromMinute == null) problems += RoutineProblem("$path.when.from", "'$from' is not a HH:mm time")
                if (toMinute == null) problems += RoutineProblem("$path.when.to", "'$to' is not a HH:mm time")
                if (fromMinute != null && fromMinute == toMinute) {
                    problems += RoutineProblem("$path.when", "from and to are the same; that window is empty")
                }
            }
            conditions.days.forEachIndexed { d, day ->
                if (day !in 1..7) problems += RoutineProblem("$path.when.days[$d]", "days are ISO weekdays 1..7")
            }
            conditions.batteryBelow?.let {
                if (it !in 0..100) problems += RoutineProblem("$path.when.batteryBelow", "batteryBelow is a percentage 0..100")
            }
            val min = conditions.minTempC
            val max = conditions.maxTempC
            if (min != null && max != null && min > max) {
                problems += RoutineProblem("$path.when", "minTempC is above maxTempC")
            }
        }
        return problems
    }

    /** A source is an asset, a generated wallpaper id, or a readable path/url. */
    private fun sourceProblem(source: String, path: String, problems: MutableList<RoutineProblem>) {
        when {
            source.isBlank() -> problems += RoutineProblem(path, "source is empty")
            source.length > 2048 -> problems += RoutineProblem(path, "source has at most 2048 characters")
            source.startsWith("asset:") -> if (source.removePrefix("asset:").isBlank()) {
                problems += RoutineProblem(path, "asset: needs a file name")
            }
            source.startsWith("id:") -> {
                val id = source.removePrefix("id:").toLongOrNull()
                if (id == null || id <= 0) problems += RoutineProblem(path, "id: needs a positive wallpaper id")
            }
            !source.startsWith("http://") && !source.startsWith("https://") && !source.startsWith("/") ->
                problems += RoutineProblem(path, "use asset:<file>, id:<n>, a /path or an http(s) url")
        }
    }
}
