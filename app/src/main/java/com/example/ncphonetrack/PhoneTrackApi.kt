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

    fun fetchSessionList(context: Context, baseUrl: String, user: String, password: String): Pair<List<RemoteSession>, String?> {
        val prefs = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        val client = CertHelper.buildClient(prefs)
        val server = PhoneTrackUrl.parse(baseUrl).base
        val auth = okhttp3.Credentials.basic(user, password)
        val urls = listOf(
            "$server/index.php/apps/phonetrack/api/getsessions",
            "$server/index.php/apps/phonetrack/sessions",
            "$server/apps/phonetrack/api/getsessions",
            "$server/apps/phonetrack/sessions"
        )
        var lastError = "PhoneTrack did not return a session list"
        for (url in urls) {
            LogManager.add("GET $url")
            val request = Request.Builder()
                .url(url)
                .header("Authorization", auth)
                .header("OCS-APIRequest", "true")
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    LogManager.add("GET response: ${response.code}")
                    if (response.code == 401 || response.code == 403) {
                        return emptyList<RemoteSession>() to "Nextcloud rejected the username or app password"
                    }
                    if (!response.isSuccessful) {
                        lastError = "HTTP ${response.code}"
                        return@use
                    }
                    val parsed = parseSessionList(body)
                    if (parsed != null) return parsed to null
                    lastError = "Response was not a session list"
                }
            } catch (e: Exception) {
                lastError = e.message ?: "error"
            }
        }
        LogManager.add("Session list failed: $lastError")
        return emptyList<RemoteSession>() to lastError
    }

    private fun parseSessionList(body: String): List<RemoteSession>? {
        val trimmed = body.trim()
        return when {
            trimmed.startsWith("[") -> collectFromArray(org.json.JSONArray(trimmed))
            trimmed.startsWith("{") -> {
                val obj = JSONObject(trimmed)
                val data = if (obj.has("ocs")) obj.getJSONObject("ocs").opt("data") else obj
                when (data) {
                    is org.json.JSONArray -> collectFromArray(data)
                    is JSONObject -> collectFromObject(data)
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun collectFromArray(arr: org.json.JSONArray): List<RemoteSession> {
        val out = mutableListOf<RemoteSession>()
        for (i in 0 until arr.length()) {
            when (val item = arr.get(i)) {
                is org.json.JSONArray -> {
                    val token = item.optString(1)
                    if (token.isNotBlank()) {
                        val name = item.optString(0).ifBlank { token.take(8) }
                        out.add(RemoteSession(name, token))
                    }
                }
                is JSONObject -> addObject(out, item)
            }
        }
        return out
    }

    private fun collectFromObject(obj: JSONObject): List<RemoteSession>? {
        if (obj.length() == 0) return emptyList()
        val out = mutableListOf<RemoteSession>()
        val keys = obj.keys()
        var recognized = false
        while (keys.hasNext()) {
            val value = obj.opt(keys.next())
            if (value is JSONObject && (value.has("token") || value.has("name"))) {
                recognized = true
                addObject(out, value)
            }
        }
        return if (recognized) out else null
    }

    private fun addObject(out: MutableList<RemoteSession>, item: JSONObject) {
        val token = item.optString("token")
        if (token.isBlank()) return
        out.add(RemoteSession(item.optString("name").ifBlank { token.take(8) }, token))
    }
}

data class GeoPointData(val lat: Double, val lon: Double, val timestamp: Long, val accuracy: Double)

data class RemoteSession(val name: String, val token: String)
