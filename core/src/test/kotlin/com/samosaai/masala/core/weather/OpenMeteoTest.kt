package com.samosaai.masala.core.weather

import com.samosaai.masala.core.contract.WeatherKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenMeteoTest {
    private val forecast = """
        {"latitude":12.97,"longitude":77.59,"timezone":"Asia/Kolkata",
         "current":{"time":"2026-10-31T07:30","interval":900,"temperature_2m":21.4,"weather_code":61},
         "daily":{"time":["2026-10-31"],"weather_code":[63],"temperature_2m_max":[27.1],
                  "temperature_2m_min":[18.0],"precipitation_probability_max":[60]}}
    """.trimIndent()

    @Test
    fun `a forecast becomes today's weather with the current conditions first`() {
        val w = OpenMeteo.parseForecast(forecast, "Bengaluru")!!
        assertEquals(WeatherKind.RAIN, w.kind)
        assertEquals("light rain", w.description)
        assertEquals(21.4, w.tempC)
        assertEquals(27.1, w.highC)
        assertEquals(18.0, w.lowC)
        assertEquals(60, w.rainChance)
        assertEquals("Bengaluru: light rain, 21°C now, 27/18°C, rain 60%", w.line())
        assertEquals(WeatherKind.RAIN, w.now?.kind)
    }

    @Test
    fun `missing parts are left out of the line`() {
        val w = OpenMeteo.parseForecast("""{"daily":{"weather_code":[0],"temperature_2m_max":[30]}}""", "Pune")!!
        assertEquals(WeatherKind.CLEAR, w.kind)
        assertNull(w.tempC)
        assertNull(w.now)
        assertEquals("Pune: clear sky", w.line())
    }

    @Test
    fun `a reply without weather or not JSON is null`() {
        assertNull(OpenMeteo.parseForecast("""{"error":true,"reason":"bad latitude"}""", "x"))
        assertNull(OpenMeteo.parseForecast("<html>busy</html>", "x"))
    }

    @Test
    fun `places come back best match first and empty when nothing matches`() {
        val json = """
            {"results":[{"id":1,"name":"Bengaluru","latitude":12.97,"longitude":77.59,"country":"India"},
                        {"id":2,"name":"Bangalore","latitude":40.1,"longitude":-80.2},
                        {"id":3,"latitude":1.0,"longitude":2.0}]}
        """.trimIndent()
        val places = OpenMeteo.parsePlaces(json)
        assertEquals(listOf("Bengaluru, India", "Bangalore"), places.map { it.label })
        assertEquals(emptyList(), OpenMeteo.parsePlaces("""{"generationtime_ms":0.3}"""))
        assertEquals(emptyList(), OpenMeteo.parsePlaces("nope"))
    }

    @Test
    fun `every WMO group maps onto a kind`() {
        val expected = mapOf(
            0 to WeatherKind.CLEAR, 1 to WeatherKind.CLEAR, 2 to WeatherKind.CLOUDY, 3 to WeatherKind.CLOUDY,
            45 to WeatherKind.FOG, 48 to WeatherKind.FOG, 51 to WeatherKind.RAIN, 57 to WeatherKind.RAIN,
            61 to WeatherKind.RAIN, 67 to WeatherKind.RAIN, 71 to WeatherKind.SNOW, 77 to WeatherKind.SNOW,
            80 to WeatherKind.RAIN, 82 to WeatherKind.RAIN, 85 to WeatherKind.SNOW, 86 to WeatherKind.SNOW,
            95 to WeatherKind.STORM, 99 to WeatherKind.STORM, 42 to WeatherKind.CLOUDY,
        )
        expected.forEach { (code, kind) -> assertEquals(kind, OpenMeteo.kindOf(code), "code $code") }
    }

    @Test
    fun `urls ask for today in the place's zone and encode the name`() {
        val url = OpenMeteo.forecastUrl(Place("Bengaluru", 12.97, 77.59))
        assertTrue("latitude=12.97&longitude=77.59" in url, url)
        assertTrue("timezone=auto" in url && "forecast_days=1" in url, url)
        assertTrue("name=New+Delhi" in OpenMeteo.searchUrl(" New Delhi "))
    }
}
