package org.chronoscompanion.app.weather

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.util.Log
import org.chronoscompanion.app.ble.ChronosProtocol
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Fetches current weather + a 7-day forecast from Open-Meteo (free, no API key) for the
 * phone's last known location, and maps it to the fields the watch's existing weather
 * screen expects (see ChronosProtocol's weather builders).
 */
object WeatherFetcher {
    private const val TAG = "WeatherFetcher"

    data class Result(
        val city: String,
        val days: List<ChronosProtocol.WeatherDay>, // index 0 = today
        val uvIndex: Int,
        val pressureHpa: Int,
        val hourly: List<ChronosProtocol.HourlyData> // today's 24 hours, index = hour of day
    )

    @SuppressLint("MissingPermission")
    fun getLastLocation(context: Context): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                if (lm.isProviderEnabled(provider)) {
                    lm.getLastKnownLocation(provider)?.let { return it }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "No permission to read location from $provider")
            } catch (e: IllegalArgumentException) {
                // provider not supported on this device
            }
        }
        return null
    }

    private fun cityName(context: Context, location: Location): String {
        return try {
            @Suppress("DEPRECATION")
            val addresses = Geocoder(context, Locale.getDefault()).getFromLocation(location.latitude, location.longitude, 1)
            addresses?.firstOrNull()?.locality
                ?: addresses?.firstOrNull()?.subAdminArea
                ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "Reverse geocoding failed: ${e.message}")
            ""
        }
    }

    /** Open-Meteo weather_code (WMO) -> firmware icon 0-7, see ChronosProtocol.buildWeatherDaily. */
    private fun iconForWmoCode(code: Int): Int = when (code) {
        0, 1 -> 1
        2 -> 0
        3 -> 4
        45, 48 -> 6
        51, 53, 55, 56, 57 -> 7
        61, 63, 65, 66, 67 -> 3
        71, 73, 75, 77 -> 2
        80, 81, 82 -> 7
        85, 86 -> 2
        95, 96, 99 -> 5
        else -> 4
    }

    /** Runs a blocking network call - callers must invoke this off the main thread. */
    fun fetch(context: Context, location: Location): Result? {
        return try {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast?latitude=${location.latitude}&longitude=${location.longitude}" +
                    "&current=temperature_2m,weather_code,pressure_msl" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,uv_index_max" +
                    "&hourly=temperature_2m,weather_code,wind_speed_10m,relative_humidity_2m,uv_index" +
                    "&forecast_days=7&forecast_hours=24&timezone=auto"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.requestMethod = "GET"
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(body)
            val current = json.getJSONObject("current")
            val currentTemp = current.getDouble("temperature_2m").roundToInt()
            val currentIcon = iconForWmoCode(current.getInt("weather_code"))
            val pressure = current.optDouble("pressure_msl", 1013.0).roundToInt()

            val daily = json.getJSONObject("daily")
            val codes = daily.getJSONArray("weather_code")
            val highs = daily.getJSONArray("temperature_2m_max")
            val lows = daily.getJSONArray("temperature_2m_min")
            val uvArr = daily.getJSONArray("uv_index_max")
            val dayCount = minOf(7, codes.length())

            val days = (0 until dayCount).map { i ->
                val high = highs.getDouble(i).roundToInt()
                val low = lows.getDouble(i).roundToInt()
                if (i == 0) {
                    // today: show the actual current temp/condition, not the daily summary
                    ChronosProtocol.WeatherDay(icon = currentIcon, temp = currentTemp, high = high, low = low)
                } else {
                    ChronosProtocol.WeatherDay(icon = iconForWmoCode(codes.getInt(i)), temp = (high + low) / 2, high = high, low = low)
                }
            }
            val uvToday = uvArr.optDouble(0, 0.0).roundToInt()

            val hourly = json.optJSONObject("hourly")?.let { h ->
                val times = h.getJSONArray("time")
                val temps = h.getJSONArray("temperature_2m")
                val hcodes = h.getJSONArray("weather_code")
                val winds = h.getJSONArray("wind_speed_10m")
                val humidity = h.getJSONArray("relative_humidity_2m")
                val uv = h.getJSONArray("uv_index")
                (0 until times.length()).map { i ->
                    // "time" is e.g. "2026-08-05T14:00" - hour-of-day is characters 11-12.
                    val hourOfDay = times.getString(i).substring(11, 13).toInt()
                    ChronosProtocol.HourlyData(
                        hour = hourOfDay,
                        icon = iconForWmoCode(hcodes.getInt(i)),
                        temp = temps.getDouble(i).roundToInt(),
                        windKmh = winds.getDouble(i).roundToInt(),
                        humidity = humidity.getInt(i),
                        uv = uv.optDouble(i, 0.0).roundToInt()
                    )
                }
            } ?: emptyList()

            Result(city = cityName(context, location), days = days, uvIndex = uvToday, pressureHpa = pressure, hourly = hourly)
        } catch (e: Exception) {
            Log.e(TAG, "Weather fetch failed: ${e.message}")
            null
        }
    }
}
