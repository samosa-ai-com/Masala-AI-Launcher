package com.samosaai.masala.core.contract

import kotlinx.serialization.Serializable
import java.time.LocalDateTime

/**
 * What the debug panel changes in a [ContextSnapshot] (plan Phase 2): any field it sets replaces the
 * real one, a null field leaves it alone. Pure so the rules around it are tested on the JVM; the
 * service applies it to every snapshot it builds.
 *
 * Fields where null is itself a value (`charging`, `nextEvent`, `sleepHours`…) are wrapped: `Set(null)`
 * means "pretend the signal isn't there".
 */
@Serializable
data class ContextOverride(
    @Serializable(with = LocalDateTimeSerializer::class) val now: LocalDateTime? = null,
    val charging: Override<Boolean>? = null,
    val batteryPercent: Override<Int>? = null,
    val bluetoothDevices: List<String>? = null,
    val activeModes: List<String>? = null,
    val urgentCount: Int? = null,
    val sleepHours: Override<Double>? = null,
    val steps: Override<Int>? = null,
    val weather: Override<WeatherNow>? = null,
    /** Minutes from [now] until a made-up next event; `Set(null)` removes the real one. */
    val minutesToNextEvent: Override<Int>? = null,
) {
    @Serializable
    data class Override<T>(val value: T?)

    val isEmpty: Boolean get() = this == ContextOverride()

    fun apply(real: ContextSnapshot): ContextSnapshot {
        val time = now ?: real.now
        return real.copy(
            now = time,
            charging = if (charging != null) charging.value else real.charging,
            batteryPercent = if (batteryPercent != null) batteryPercent.value else real.batteryPercent,
            bluetoothDevices = bluetoothDevices ?: real.bluetoothDevices,
            activeModes = activeModes ?: real.activeModes,
            urgentCount = urgentCount ?: real.urgentCount,
            sleepHours = if (sleepHours != null) sleepHours.value else real.sleepHours,
            steps = if (steps != null) steps.value else real.steps,
            weather = if (weather != null) weather.value else real.weather,
            nextEvent = if (minutesToNextEvent != null) {
                minutesToNextEvent.value?.let { NextEvent("Debug event", time.plusMinutes(it.toLong())) }
            } else {
                real.nextEvent
            },
        )
    }
}
