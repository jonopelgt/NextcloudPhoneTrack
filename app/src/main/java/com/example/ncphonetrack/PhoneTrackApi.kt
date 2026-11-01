package com.example.ncphonetrack

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

object PhoneTrackApi {
    private val client = OkHttpClient()

    fun getLastPositions(baseUrl: String, token: String): Map<String, GeoPointData>? {
        val url = "$baseUrl/apps/phonetrack/api/getlastpositions/$token"
        LogManager.add("GET $url")
        val request = Request.Builder().url(url).build()
        return try {
            client.newCall(request).execute().use { response ->
                LogManager.add("GET response: ${response.code}")
                if (!response.isSuccessful) return null
                val json = JSONObject(response.body?.string() ?: return null)
                val session = json.getJSONObject(token)
                val devices = mutableMapOf<String, GeoPointData>()
                val keys = session.keys()
                while (keys.hasNext()) {
                    val name = keys.next()
                    val dev = session.getJSONObject(name)
                    devices[name] = GeoPointData(
                        lat = dev.getDouble("lat"),
                        lon = dev.getDouble("lon"),
                        timestamp = dev.optLong("timestamp", 0),
                        accuracy = dev.optDouble("accuracy", 0.0)
                    )
                }
                LogManager.add("GET parsed ${devices.size} devices")
                devices
            }
        } catch (e: Exception) {
            LogManager.add("GET failed: ${e.message}")
            null
        }
    }
}

data class GeoPointData(val lat: Double, val lon: Double, val timestamp: Long, val accuracy: Double)
