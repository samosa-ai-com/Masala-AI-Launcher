package com.samosaai.masala.core.weather

import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.contract.WeatherKind
import com.samosaai.masala.core.contract.WeatherNow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URLEncoder

/** A place the weather is for: typed by the user and geocoded, or the phone's coarse location. */
@Serializable
data class Place(val name: String, val latitude: Double, val longitude: Double, val country: String? = null) {
    /** "Bengaluru, India" for the settings row and the brief. */
    val label: String get() = listOfNotNull(name, country).joinToString(", ")
}

/** Today's weather at one place, as the brief and (later) the `Weather` condition read it. */
data class DailyWeather(
    val place: String,
    val kind: WeatherKind,
    val description: String,
    val tempC: Double?,
    val highC: Double?,
    val lowC: Double?,
    val rainChance: Int?,
    /** The coming hours, oldest first (empty when the reply had no hourly block). */
    val hourly: List<HourEntry> = emptyList(),
) {
    val now: WeatherNow? get() = tempC?.let { WeatherNow(kind, it) }

    /** One line for the brief's data block: "Bengaluru: light rain, 21°C now, 27/18°C, rain 60%". */
    fun line(): String = buildString {
        append(place).append(": ").append(description)
        tempC?.let { append(", ").append(it.roundToText()).append("°C now") }
        if (highC != null && lowC != null) append(", ").append(highC.roundToText()).append('/').append(lowC.roundToText()).append("°C")
        rainChance?.let { append(", rain ").append(it).append('%') }
    }

    /** One line for the `weather` tool: [line] plus the coming hours, e.g. "14:00 clear 22°C rain 5%". */
    fun hourLine(maxHours: Int = 8): String {
        val line = line()
        val hours = hourly.take(maxHours)
        if (hours.isEmpty()) return line
        return line + "\nNext hours: " + hours.joinToString(", ") { h ->
            buildString {
                append(h.time).append(' ').append(h.description)
                h.tempC?.let { append(' ').append(it.roundToText()).append("°C") }
                h.rainChance?.let { append(" rain ").append(it).append('%') }
            }
        }
    }
}

/** One coming hour: local "HH:MM", conditions and rain chance. */
data class HourEntry(
    val time: String,
    val tempC: Double?,
    val rainChance: Int?,
    val description: String,
)

/**
 * The Open-Meteo forecast and geocoding APIs (no key): URLs and parsing only, so the app's client stays a
 * thin HTTP call and everything here is tested on the JVM.
 */
object OpenMeteo {
    private const val FORECAST = "https://api.open-meteo.com/v1/forecast"
    private const val GEOCODING = "https://geocoding-api.open-meteo.com/v1/search"

    /** Today's forecast at [place], in the place's own time zone. */
    fun forecastUrl(place: Place): String =
        "$FORECAST?latitude=${place.latitude}&longitude=${place.longitude}" +
            "&current=temperature_2m,weather_code" +
            "&hourly=temperature_2m,precipitation_probability,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            // Two days: late in the evening the next 8 hours cross midnight.
            "&timezone=auto&forecast_days=2"

    fun searchUrl(name: String, language: String = "en", count: Int = 5): String =
        "$GEOCODING?name=${URLEncoder.encode(name.trim(), "UTF-8")}&count=$count&language=$language&format=json"

    /** Null when the reply has neither current nor daily weather. */
    fun parseForecast(json: String, place: String, nowEpochMs: Long = System.currentTimeMillis()): DailyWeather? {
        val root = runCatching { MasalaJson.lenient.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
        val current = root["current"] as? JsonObject
        val daily = root["daily"] as? JsonObject
        if (current == null && daily == null) return null
        val code = current?.int("weather_code") ?: daily?.firstInt("weather_code") ?: return null
        return DailyWeather(
            place = place,
            kind = kindOf(code),
            description = describe(code),
            tempC = current?.double("temperature_2m"),
            highC = daily?.firstDouble("temperature_2m_max"),
            lowC = daily?.firstDouble("temperature_2m_min"),
            rainChance = daily?.firstInt("precipitation_probability_max"),
            hourly = parseHourly(root["hourly"] as? JsonObject, utcOffset(root), nowEpochMs),
        )
    }

    /** The coming hours from the hourly block, oldest first, capped at 12. */
    private fun parseHourly(hourly: JsonObject?, offsetSeconds: Long, nowEpochMs: Long): List<HourEntry> {
        if (hourly == null) return emptyList()
        val times = (hourly["time"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: return emptyList()
        if (times.isEmpty()) return emptyList()
        val offset = offsetSeconds
        val temps = (hourly["temperature_2m"] as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull }
        val rains = (hourly["precipitation_probability"] as? JsonArray)?.map { (it as? JsonPrimitive)?.let { p -> p.intOrNull ?: p.doubleOrNull?.toInt() } }
        val codes = (hourly["weather_code"] as? JsonArray)?.map { (it as? JsonPrimitive)?.let { p -> p.intOrNull ?: p.doubleOrNull?.toInt() } }
        // Times are local ISO ("2026-11-01T14:00"); keep everything from the current hour on.
        val nowLocal = (nowEpochMs / 1000 + offset) / 3600 * 3600
        return times.indices.mapNotNull { i ->
            val epoch = localIsoToEpoch(times[i], offset) ?: return@mapNotNull null
            if (epoch + offset < nowLocal) return@mapNotNull null
            HourEntry(
                time = times[i].substringAfter("T").take(5),
                tempC = temps?.getOrNull(i),
                rainChance = rains?.getOrNull(i),
                description = codes?.getOrNull(i)?.let(::describe) ?: "—",
            )
        }.take(12)
    }

    private fun utcOffset(root: JsonObject): Long =
        ((root["utc_offset_seconds"] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() } ?: 0).toLong()

    private fun localIsoToEpoch(iso: String, offsetSeconds: Long): Long? = runCatching {
        val (date, time) = iso.split("T")
        val (y, m, d) = date.split("-").map(String::toInt)
        val (hh, mm) = time.split(":").take(2).map(String::toInt)
        java.time.LocalDateTime.of(y, m, d, hh, mm).toEpochSecond(java.time.ZoneOffset.UTC) - offsetSeconds
    }.getOrNull()

    /** Places for a typed name, best match first; empty when nothing matches. */
    fun parsePlaces(json: String): List<Place> {
        val root = runCatching { MasalaJson.lenient.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return emptyList()
        val results = root["results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val lat = o.double("latitude") ?: return@mapNotNull null
            val lon = o.double("longitude") ?: return@mapNotNull null
            Place(name, lat, lon, (o["country"] as? JsonPrimitive)?.content)
        }
    }

    /** WMO weather interpretation codes, as Open-Meteo returns them, onto the rule engine's kinds. */
    fun kindOf(code: Int): WeatherKind = when (code) {
        0, 1 -> WeatherKind.CLEAR
        2, 3 -> WeatherKind.CLOUDY
        45, 48 -> WeatherKind.FOG
        in 51..67, in 80..82 -> WeatherKind.RAIN
        in 71..77, 85, 86 -> WeatherKind.SNOW
        in 95..99 -> WeatherKind.STORM
        else -> WeatherKind.CLOUDY
    }

    fun describe(code: Int): String = when (code) {
        0 -> "clear sky"
        1 -> "mainly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "fog"
        51, 53, 55 -> "drizzle"
        56, 57 -> "freezing drizzle"
        61 -> "light rain"
        63 -> "rain"
        65 -> "heavy rain"
        66, 67 -> "freezing rain"
        71 -> "light snow"
        73 -> "snow"
        75 -> "heavy snow"
        77 -> "snow grains"
        80, 81 -> "rain showers"
        82 -> "violent rain showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorm"
        96, 99 -> "thunderstorm with hail"
        else -> "cloudy"
    }

    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
    private fun JsonObject.first(key: String): JsonElement? = (this[key] as? JsonArray)?.firstOrNull()
    private fun JsonObject.firstDouble(key: String): Double? = (first(key) as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.firstInt(key: String): Int? = (first(key) as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
}

private fun Double.roundToText(): String = Math.round(this).toString()
