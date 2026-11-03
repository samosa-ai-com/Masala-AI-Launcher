package com.samosaai.masala.core.widgets

import com.samosaai.masala.core.contract.Binding
import com.samosaai.masala.core.contract.BindingSource
import com.samosaai.masala.core.contract.ContextSnapshot

/**
 * Resolves a widget [Binding] to display values (plan `docs/plans/widget-maker.md` W1): storage reads
 * the widget's own map, the rest read the context snapshot. [BindingSource.MEDIA],
 * [BindingSource.HTTP], [BindingSource.STREAM_STATUS] and [BindingSource.SKILL_RESULT] need the app
 * stores that land in W4/W5, so they resolve to null until then and the renderer draws them as
 * "not yet" rather than as zero.
 */
object WidgetValues {

    /**
     * Display text, or null when there is nothing to show yet. [extra] carries values the app
     * supplies at render time (the Second Brain); core stays free of Android types.
     */
    fun textOf(
        binding: Binding,
        storage: Map<String, String>,
        snapshot: ContextSnapshot,
        extra: Map<String, String> = emptyMap(),
    ): String? =
        when (binding.source) {
            BindingSource.STORAGE -> storage[binding.key]
            BindingSource.NEXT_EVENT -> snapshot.nextEvent?.let { e ->
                buildString {
                    append(e.title)
                    append(" · ")
                    append(e.start.toLocalTime().toString())
                    e.location?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                }
            }
            BindingSource.BATTERY -> snapshot.batteryPercent?.let { "$it%" }
            BindingSource.MEDIA -> null
            BindingSource.SLEEP -> snapshot.sleepHours?.let { trimNumber(it) + " h" }
            BindingSource.STEPS -> snapshot.steps?.toString()
            BindingSource.WEATHER -> snapshot.weather?.let { "${trimNumber(it.tempC)}°C ${it.kind.name.lowercase()}" }
            // Zero urgent notifications is information ("nothing waiting"), not missing data.
            BindingSource.URGENT_COUNT -> snapshot.urgentCount.toString()
            BindingSource.HTTP -> null
            BindingSource.STREAM_STATUS -> null
            BindingSource.SKILL_RESULT -> null
            BindingSource.SECOND_BRAIN -> extra[binding.key]
        }

    /** A numeric value for progress and gauge, or null when there is nothing to show yet. */
    fun numberOf(
        binding: Binding,
        storage: Map<String, String>,
        snapshot: ContextSnapshot,
        extra: Map<String, String> = emptyMap(),
    ): Double? =
        when (binding.source) {
            BindingSource.STORAGE -> storage[binding.key]?.toDoubleOrNull()
            BindingSource.BATTERY -> snapshot.batteryPercent?.toDouble()
            BindingSource.STEPS -> snapshot.steps?.toDouble()
            BindingSource.SLEEP -> snapshot.sleepHours
            BindingSource.URGENT_COUNT -> snapshot.urgentCount.toDouble()
            BindingSource.WEATHER -> snapshot.weather?.tempC
            BindingSource.SECOND_BRAIN -> extra[binding.key]?.toDoubleOrNull()
            BindingSource.NEXT_EVENT,
            BindingSource.MEDIA,
            BindingSource.HTTP,
            BindingSource.STREAM_STATUS,
            BindingSource.SKILL_RESULT,
            -> null
        }

    /** Rows for a `list` component: storage holds newline-separated lines, the snapshot has one. */
    fun rowsOf(
        binding: Binding,
        storage: Map<String, String>,
        snapshot: ContextSnapshot,
        maxRows: Int,
        extra: Map<String, String> = emptyMap(),
    ): List<String> {
        val lines = when (binding.source) {
            BindingSource.STORAGE -> storage[binding.key]?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            BindingSource.NEXT_EVENT -> snapshot.nextEvents.ifEmpty { listOfNotNull(snapshot.nextEvent) }
                .map { e ->
                    buildString {
                        append(e.title).append(" · ").append(e.start.toLocalTime().toString())
                        e.location?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                    }
                }
            BindingSource.SECOND_BRAIN -> extra[binding.key]?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            else -> return emptyList()
        }
        return lines.take(maxRows.coerceAtLeast(0))
    }

    private fun trimNumber(v: Double): String {
        val asLong = v.toLong()
        return if (v == asLong.toDouble()) asLong.toString() else v.toString()
    }
}
