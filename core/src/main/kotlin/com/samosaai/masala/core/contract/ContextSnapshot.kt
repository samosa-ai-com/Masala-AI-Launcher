package com.samosaai.masala.core.contract

import kotlinx.serialization.Serializable
import java.time.LocalDateTime

/**
 * Every signal rules can read, at one moment (arch §3.4). The context service builds it; the debug
 * panel can override any field. Null means the signal isn't available (no permission, no data).
 */
@Serializable
data class ContextSnapshot(
    @Serializable(with = LocalDateTimeSerializer::class) val now: LocalDateTime,
    val nextEvent: NextEvent? = null,
    /** The next few events, soonest first; [nextEvent] is its first for rules. */
    val nextEvents: List<NextEvent> = emptyList(),
    val charging: Boolean? = null,
    val batteryPercent: Int? = null,
    val bluetoothDevices: List<String> = emptyList(),
    val activeModes: List<String> = emptyList(),
    val urgentCount: Int = 0,
    val sleepHours: Double? = null,
    val steps: Int? = null,
    val weather: WeatherNow? = null,
)

@Serializable
data class NextEvent(
    val title: String,
    @Serializable(with = LocalDateTimeSerializer::class) val start: LocalDateTime,
    val location: String? = null,
)

@Serializable
data class WeatherNow(val kind: WeatherKind, val tempC: Double)
