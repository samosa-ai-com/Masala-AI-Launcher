package com.samosaai.masala.core.wallpaper

import com.samosaai.masala.core.contract.WeatherKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A wallpaper that follows a rhythm (plan `docs/plans/wallpaper-routines.md`). A routine is an
 * ordered list of rules; the first rule whose conditions all hold decides the picture, and
 * [defaultWallpaperId] is what applies when none does. Home only in v1: lock-screen rotation is
 * a different promise to the user and waits.
 *
 * Pure data, no Android types, so the evaluator is unit-tested on the JVM.
 */
@Serializable
data class WallpaperRoutine(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val rules: List<RoutineRule> = emptyList(),
    /** Shown when no rule matches; same form as [RoutineRule.source]. */
    val defaultSource: String? = null,
    /** Only "home" is accepted for now; recorded so a later lock-screen mode can be added. */
    val target: String = "home",
)

/**
 * One rule: when [conditions] hold, show [source]. A source is `asset:<file>` for a bundled
 * wallpaper, `id:<n>` for one the user generated, or a readable file path / http(s) url.
 */
@Serializable
data class RoutineRule(
    @SerialName("when") val conditions: RoutineConditions = RoutineConditions(),
    val source: String,
)

/**
 * All conditions are optional and AND-ed. Times are "HH:mm" local; [days] are ISO weekdays
 * (1 = Monday); [weather] matches any of the kinds; [batteryBelow] is a percentage.
 */
@Serializable
data class RoutineConditions(
    val from: String? = null,
    val to: String? = null,
    val days: List<Int> = emptyList(),
    val weather: List<WeatherKind> = emptyList(),
    val charging: Boolean? = null,
    val batteryBelow: Int? = null,
    val minTempC: Double? = null,
    val maxTempC: Double? = null,
)

/** Why a routine was refused, with a path a repair turn can point at. */
data class RoutineProblem(val path: String, val message: String)
