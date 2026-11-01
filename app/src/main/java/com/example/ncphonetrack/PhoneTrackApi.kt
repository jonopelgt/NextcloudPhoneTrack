package com.example.ncphonetrack

import android.content.Context
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

object PhoneTrackApi {
    fun getLastPositions(context: Context, baseUrl: String, token: String): Map<String, GeoPointData>? {
        val prefs = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        val client = CertHelper.buildClient(prefs)
        val server = PhoneTrackUrl.parse(baseUrl).base
        val tokenEnc = URLEncoder.encode(token, "UTF-8").replace("+", "%20")
        val urls = listOf(
            "$server/index.php/apps/phonetrack/api/getlastpositions/$tokenEnc",
            "$server/apps/phonetrack/api/getlastpositions/$tokenEnc"
        )
        var lastError = "no response"
        for (url in urls) {
            LogManager.add("GET $url")
            val request = Request.Builder().url(url).build()
            try {
                client.newCall(request).execute().use { response ->
                    LogManager.add("GET response: ${response.code}")
                    if (!response.isSuccessful) {
                        lastError = "HTTP ${response.code}"
                        return@use
                    }
                    val json = JSONObject(response.body?.string() ?: return null)
                    val session = when {
                        json.has(token) -> json.getJSONObject(token)
                        json.length() > 0 -> json.getJSONObject(json.keys().next())
                        else -> return null
                    }
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
                    return devices
                }
            } catch (e: Exception) {
                lastError = e.message ?: "error"
            }
        }
        LogManager.add("GET failed: $lastError")
        return null
    }
}

data class GeoPointData(val lat: Double, val lon: Double, val timestamp: Long, val accuracy: Double)
