package com.samosaai.masala.core.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalTime

/**
 * One condition language for modes, auto-adjusting widgets and calendar cards (arch §3.3). The model
 * writes conditions; [com.samosaai.masala.core.rules.RuleEvaluator] runs them on the phone.
 *
 * A condition on a signal the phone doesn't have (no calendar access, no Health Connect) is false.
 */
@Serializable
sealed interface Condition {
    /**
     * From [start] to [end] local time, `"HH:mm"`. A window that ends before it starts runs past
     * midnight (22:30–07:00). [days] are the days the window *starts* on; empty means every day.
     */
    @Serializable
    @SerialName("time_window")
    data class TimeWindow(
        @Serializable(with = LocalTimeSerializer::class) val start: LocalTime,
        @Serializable(with = LocalTimeSerializer::class) val end: LocalTime,
        val days: List<Weekday> = emptyList(),
    ) : Condition

    /** The next calendar event starts in [minMinutes]..[maxMinutes] minutes. */
    @Serializable
    @SerialName("minutes_to_next_event")
    data class MinutesToNextEvent(val maxMinutes: Int, val minMinutes: Int = 0) : Condition

    @Serializable
    @SerialName("charging")
    data class Charging(val charging: Boolean = true) : Condition

    /** A Bluetooth device is connected; [deviceName] null means any device. */
    @Serializable
    @SerialName("bluetooth_connected")
    data class BluetoothConnected(val deviceName: String? = null) : Condition

    @Serializable
    @SerialName("mode_active")
    data class ModeActive(val modeId: String) : Condition

    @Serializable
    @SerialName("urgent_count")
    data class UrgentCount(val atLeast: Int = 1) : Condition

    /** Last night's sleep, in hours. */
    @Serializable
    @SerialName("sleep")
    data class Sleep(val minHours: Double? = null, val maxHours: Double? = null) : Condition

    /** Today's steps. */
    @Serializable
    @SerialName("steps")
    data class Steps(val min: Int? = null, val max: Int? = null) : Condition

    @Serializable
    @SerialName("weather")
    data class Weather(
        val kinds: List<WeatherKind> = emptyList(),
        val minTempC: Double? = null,
        val maxTempC: Double? = null,
    ) : Condition

    @Serializable
    @SerialName("all")
    data class AllOf(val conditions: List<Condition>) : Condition

    @Serializable
    @SerialName("any")
    data class AnyOf(val conditions: List<Condition>) : Condition

    @Serializable
    @SerialName("not")
    data class Not(val condition: Condition) : Condition
}

@Serializable
enum class Weekday {
    @SerialName("mon") MON,
    @SerialName("tue") TUE,
    @SerialName("wed") WED,
    @SerialName("thu") THU,
    @SerialName("fri") FRI,
    @SerialName("sat") SAT,
    @SerialName("sun") SUN,
}

@Serializable
enum class WeatherKind {
    @SerialName("clear") CLEAR,
    @SerialName("cloudy") CLOUDY,
    @SerialName("rain") RAIN,
    @SerialName("snow") SNOW,
    @SerialName("storm") STORM,
    @SerialName("fog") FOG,
}

/** What a rule does when its condition holds. */
@Serializable
data class Rule(val condition: Condition, val effect: Effect)

@Serializable
sealed interface Effect {
    @Serializable
    @SerialName("show")
    data object Show : Effect

    @Serializable
    @SerialName("hide")
    data object Hide : Effect

    /** Widget height in rows, clamped to the widget's [SizeRange]. */
    @Serializable
    @SerialName("size")
    data class Size(val rows: Int) : Effect

    /** Higher comes first in a stack. */
    @Serializable
    @SerialName("priority")
    data class Priority(val value: Int) : Effect
}
