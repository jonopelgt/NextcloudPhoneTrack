package com.example.ncphonetrack

import android.content.Context
import okhttp3.Request
import org.json.JSONObject

object PhoneTrackApi {
    fun getLastPositions(context: Context, baseUrl: String, token: String): Map<String, GeoPointData>? {
        val prefs = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        val client = CertHelper.buildClient(prefs)
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
                        accuracy = dev.optDouble("accuracy", 0.0),
                        bearing = dev.optDouble("bearing", 0.0).toFloat()
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

data class GeoPointData(val lat: Double, val lon: Double, val timestamp: Long, val accuracy: Double, val bearing: Float = 0f)
