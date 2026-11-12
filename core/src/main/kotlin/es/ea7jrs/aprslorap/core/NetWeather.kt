// Copyright (C) 2026 Manuel - EA7JRS
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Weather and air quality from the internet for the TELEMETRY page of the tracker: OpenWeatherMap (current weather and air pollution,
// free key) and IQAir AirVisual (nearest city: US AQI, main pollutant and weather, free Community key). The phone downloads with its
// own internet and the tracker gets the result with the "wxnet" command; it is shown as internet data, never as a sensor of the tracker,
// and it does not go into APRS weather reports.
package es.ea7jrs.aprslorap.core

import java.net.URLEncoder
import java.util.Locale

object NetWeather {
    /** OpenWeatherMap free plan: 1000 calls a day; IQAir Community: 10 000 a month. 15 min = 96 (OWM, x2 with air) and ~2900 a month. */
    const val DEFAULT_MINUTES = 15
    const val MIN_MINUTES = 10

    data class Data(
        val source: String,                 // "OWM", "IQAir", "OWM+IQAir"
        val place: String = "",
        val tempC: Double? = null, val humidity: Double? = null, val pressureHpa: Double? = null,
        val windMs: Double? = null, val windDeg: Int? = null, val gustMs: Double? = null,
        val clouds: Int? = null, val rainMmH: Double? = null, val description: String = "",
        val aqiOwm: Int? = null,            // 1 good .. 5 very poor
        val aqiUs: Int? = null,             // US AQI 0..500 (IQAir)
        val mainPollutant: String = "",     // IQAir "p2" -> "PM2.5"
        val pm25: Double? = null, val pm10: Double? = null, val o3: Double? = null, val no2: Double? = null,
        val timeS: Long = 0,                // observation (Unix UTC)
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun coord(v: Double) = String.format(Locale.US, "%.4f", v)

    fun owmWeatherUrl(lat: Double, lon: Double, key: String) =
        "https://api.openweathermap.org/data/2.5/weather?lat=${coord(lat)}&lon=${coord(lon)}&units=metric&appid=${enc(key)}"
    fun owmAirUrl(lat: Double, lon: Double, key: String) =
        "https://api.openweathermap.org/data/2.5/air_pollution?lat=${coord(lat)}&lon=${coord(lon)}&appid=${enc(key)}"
    fun iqairUrl(lat: Double, lon: Double, key: String) =
        "https://api.airvisual.com/v2/nearest_city?lat=${coord(lat)}&lon=${coord(lon)}&key=${enc(key)}"

    private fun Map<String, Any?>.d(k: String): Double? = (this[k] as? Number)?.toDouble()

    /** OpenWeatherMap current weather (units=metric). null if it is not a weather answer (error, bad key: {"cod":401,...}). */
    fun parseOwmWeather(json: String): Data? {
        val o = runCatching { Json.parse(json) }.getOrNull() as? Map<String, Any?> ?: return null
        val main = o.obj("main") ?: return null
        val wind = o.obj("wind") ?: emptyMap()
        val w0 = o.objList("weather").firstOrNull() ?: emptyMap()
        return Data(
            "OWM", o.str("name") ?: "", main.d("temp"), main.d("humidity"), main.d("sea_level") ?: main.d("pressure"),
            wind.d("speed"), wind.d("deg")?.toInt(), wind.d("gust"), o.obj("clouds")?.d("all")?.toInt(),
            o.obj("rain")?.d("1h") ?: o.obj("snow")?.d("1h"), w0.str("description") ?: "", timeS = o.long("dt") ?: 0,
        )
    }

    /** OpenWeatherMap air pollution: AQI 1..5 and the components (µg/m³). Fills [into] (or a new one). */
    fun parseOwmAir(json: String, into: Data?): Data? {
        val o = runCatching { Json.parse(json) }.getOrNull() as? Map<String, Any?> ?: return into
        val l0 = o.objList("list").firstOrNull() ?: return into
        val c = l0.obj("components") ?: emptyMap()
        val base = into ?: Data("OWM", timeS = l0.long("dt") ?: 0)
        return base.copy(aqiOwm = l0.obj("main")?.int("aqi"), pm25 = c.d("pm2_5"), pm10 = c.d("pm10"), o3 = c.d("o3"), no2 = c.d("no2"))
    }

    /** IQAir nearest_city: {"status":"success","data":{"city":..,"current":{"pollution":{"aqius",..},"weather":{"tp","pr","hu","ws","wd"}}}}.
     *  With [into] (OpenWeatherMap data) only the air quality is added; otherwise its weather is used too. */
    fun parseIqair(json: String, into: Data?): Data? {
        val o = runCatching { Json.parse(json) }.getOrNull() as? Map<String, Any?> ?: return into
        if (o.str("status") != "success") return into
        val d = o.obj("data") ?: return into
        val cur = d.obj("current") ?: return into
        val p = cur.obj("pollution") ?: emptyMap()
        val w = cur.obj("weather") ?: emptyMap()
        val pol = pollutantName(p.str("mainus") ?: "")
        if (into != null) return into.copy(source = "OWM+IQAir", aqiUs = p.int("aqius"), mainPollutant = pol)
        return Data(
            "IQAir", d.str("city") ?: "", w.d("tp"), w.d("hu"), w.d("pr"), w.d("ws"), w.d("wd")?.toInt(),
            aqiUs = p.int("aqius"), mainPollutant = pol, timeS = isoSeconds(w.str("ts") ?: p.str("ts") ?: ""),
        )
    }

    /** IQAir pollutant codes. */
    fun pollutantName(code: String) = when (code) { "p2" -> "PM2.5"; "p1" -> "PM10"; "o3" -> "O3"; "n2" -> "NO2"; "s2" -> "SO2"; "co" -> "CO"; else -> code }

    /** "2026-11-09T14:00:00.000Z" -> Unix seconds (0 if unknown). */
    fun isoSeconds(s: String): Long = try { java.time.Instant.parse(s).epochSecond } catch (_: Exception) { 0 }

    /** OpenWeatherMap AQI 1..5. */
    fun owmAqiName(a: Int?) = when (a) { 1 -> "Good"; 2 -> "Fair"; 3 -> "Moderate"; 4 -> "Poor"; 5 -> "Very poor"; else -> "" }

    /** US AQI band. */
    fun usAqiName(a: Int?) = when {
        a == null -> ""
        a <= 50 -> "Good"; a <= 100 -> "Moderate"; a <= 150 -> "Unhealthy for sensitive"; a <= 200 -> "Unhealthy"; a <= 300 -> "Very unhealthy"
        else -> "Hazardous"
    }

    private fun r1(v: Double?) = v?.let { Math.round(it * 10) / 10.0 }

    /** "wxnet" command for the tracker (only the fields there are; texts cut to what the screen shows). */
    fun command(d: Data, nowS: Long): String {
        val f = ArrayList<Pair<String, Any?>>()
        f.add("src" to d.source)
        if (d.place.isNotBlank()) f.add("pl" to d.place.take(20))
        r1(d.tempC)?.let { f.add("t" to it) }
        d.humidity?.let { f.add("h" to Math.round(it).toInt()) }
        r1(d.pressureHpa)?.let { f.add("p" to it) }
        r1(d.windMs)?.let { f.add("ws" to it) }
        d.windDeg?.let { f.add("wd" to it) }
        r1(d.gustMs)?.let { f.add("wg" to it) }
        d.clouds?.let { f.add("cl" to it) }
        r1(d.rainMmH)?.let { f.add("r" to it) }
        if (d.description.isNotBlank()) f.add("ds" to d.description.take(20))
        d.aqiOwm?.let { f.add("aq" to it) }
        d.aqiUs?.let { f.add("aqus" to it) }
        if (d.mainPollutant.isNotBlank()) f.add("mp" to d.mainPollutant.take(6))
        r1(d.pm25)?.let { f.add("pm25" to it) }
        r1(d.pm10)?.let { f.add("pm10" to it) }
        if (d.timeS > 0) f.add("age" to (nowS - d.timeS).coerceAtLeast(0))
        return Control.command("wxnet", *f.toTypedArray())
    }

    /** One line for the app. */
    fun summary(d: Data): String {
        val parts = ArrayList<String>()
        d.tempC?.let { parts.add(String.format(Locale.US, "%.1f °C", it)) }
        d.humidity?.let { parts.add(String.format(Locale.US, "%.0f %%", it)) }
        d.pressureHpa?.let { parts.add(String.format(Locale.US, "%.0f hPa", it)) }
        d.tempC?.let { t -> d.humidity?.let { h -> WxCalc.dewPointC(t, h)?.let { parts.add(String.format(Locale.US, "dew %.1f °C", it)) } } }
        d.windMs?.let { parts.add(String.format(Locale.US, "wind %.0f km/h", it * 3.6) + (d.windDeg?.let { w -> " from ${w}°" } ?: "") + " F${WxCalc.beaufortMs(it)}") }
        d.clouds?.let { parts.add("clouds $it %") }
        d.aqiUs?.let { parts.add("US AQI $it (${usAqiName(it)})") }
        d.aqiOwm?.let { parts.add("AQI $it (${owmAqiName(it)})") }
        d.pm25?.let { parts.add(String.format(Locale.US, "PM2.5 %.0f µg/m³", it)) }
        return parts.joinToString(" · ")
    }
}
