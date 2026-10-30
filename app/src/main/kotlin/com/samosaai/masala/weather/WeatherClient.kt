package com.samosaai.masala.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.samosaai.masala.core.weather.DailyWeather
import com.samosaai.masala.core.weather.OpenMeteo
import com.samosaai.masala.core.weather.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Today's weather from Open-Meteo (no key), for the daily brief and the `weather` tool. Each place's
 * answer is kept for [CACHE_MS], so a brief and a follow-up question a minute later make one call.
 */
class WeatherClient(private val clock: () -> Long = System::currentTimeMillis) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private class Cached(val at: Long, val weather: DailyWeather)

    private val cache = ConcurrentHashMap<String, Cached>()

    suspend fun today(place: Place): Result<DailyWeather> {
        val key = "${place.latitude},${place.longitude}"
        cache[key]?.takeIf { clock() - it.at < CACHE_MS }?.let { return Result.success(it.weather) }
        return get(OpenMeteo.forecastUrl(place)).mapCatching { body ->
            OpenMeteo.parseForecast(body, place.name) ?: error("The weather service sent no forecast.")
        }.onSuccess { cache[key] = Cached(clock(), it) }
    }

    /** Places matching a typed name, best first. */
    suspend fun search(name: String): Result<List<Place>> =
        get(OpenMeteo.searchUrl(name, Locale.getDefault().language)).map(OpenMeteo::parsePlaces)

    private suspend fun get(url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) error("The weather service answered HTTP ${response.code}.")
                response.body?.string() ?: error("The weather service sent an empty answer.")
            }
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 15L
        const val CACHE_MS = 30 * 60 * 1000L

        /**
         * The phone's last known coarse location as a [Place], named by the phone's geocoder when it can.
         * Null without the permission or a recent fix. Only called while the user is in the brief's settings.
         */
        @SuppressLint("MissingPermission")
        suspend fun currentPlace(context: Context, fallbackName: String): Place? = withContext(Dispatchers.IO) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return@withContext null
            }
            val manager = context.getSystemService(LocationManager::class.java) ?: return@withContext null
            val fix = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
                .maxByOrNull { it.time } ?: return@withContext null
            // Rounded to about a kilometre: the forecast needs no more, and less leaves the phone.
            val lat = Math.round(fix.latitude * 100) / 100.0
            val lon = Math.round(fix.longitude * 100) / 100.0
            @Suppress("DEPRECATION")
            val name = runCatching { Geocoder(context).getFromLocation(lat, lon, 1)?.firstOrNull()?.locality }.getOrNull()
            Place(name ?: fallbackName, lat, lon)
        }
    }
}
