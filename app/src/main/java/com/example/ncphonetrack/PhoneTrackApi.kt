package com.example.ncphonetrack

import android.content.Context
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

object PhoneTrackApi {
    fun rateLimited(context: Context): Boolean {
        val prefs = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        return System.currentTimeMillis() < prefs.getLong("rate_limit_until", 0L)
    }

    fun noteRateLimit(context: Context, response: okhttp3.Response) {
        val retry = response.header("Retry-After")?.toLongOrNull() ?: 300L
        val wait = retry.coerceIn(60, 3600)
        val prefs = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        prefs.edit().putLong("rate_limit_until", System.currentTimeMillis() + wait * 1000).apply()
        LogManager.add("Server rate limit, pausing ${wait}s")
    }

    fun getLastPositions(context: Context, baseUrl: String, token: String, quiet: Boolean = false): Map<String, GeoPointData>? {
        if (rateLimited(context)) {
            if (!quiet) LogManager.add("Map update skipped, server rate limit")
            return null
        }
        val prefs = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        val client = CertHelper.buildClient(prefs)
        val server = PhoneTrackUrl.parse(baseUrl).base
        val user = prefs.getString("nc_user", "")?.trim().orEmpty()
        val pass = prefs.getString("nc_pass", "").orEmpty()
        val publicToken = publicTokenFor(prefs, token)
        val tokenEnc = URLEncoder.encode(token, "UTF-8").replace("+", "%20")
        if (user.isNotBlank() && pass.isNotBlank()) {
            val url = "$server/index.php/apps/phonetrack/api/getuserlastpositions/$tokenEnc"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", okhttp3.Credentials.basic(user, pass))
                .header("OCS-APIRequest", "true")
                .build()
            if (!quiet) LogManager.add("GET user last positions")
            return executePositions(context, client, request, token, quiet)
        }
        if (!publicToken.isNullOrBlank()) {
            val pubEnc = URLEncoder.encode(publicToken, "UTF-8").replace("+", "%20")
            val url = "$server/index.php/apps/phonetrack/api/getlastpositions/$pubEnc"
            if (!quiet) LogManager.add("GET public last positions")
            return executePositions(context, client, Request.Builder().url(url).build(), publicToken, quiet)
        }
        if (!quiet) LogManager.add("Map skipped: public view token or Nextcloud login required")
        return null
    }

    private fun publicTokenFor(prefs: android.content.SharedPreferences, loggingToken: String): String? {
        val raw = prefs.getString("sessions", "[]") ?: "[]"
        val arr = org.json.JSONArray(raw)
        for (i in 0 until arr.length()) {
            val session = arr.optJSONObject(i) ?: continue
            if (session.optString("token") == loggingToken) {
                val pub = session.optString("public_token")
                if (pub.isNotBlank()) return pub
            }
        }
        return null
    }

    private fun executePositions(
        context: Context,
        client: okhttp3.OkHttpClient,
        request: Request,
        token: String,
        quiet: Boolean = false
    ): Map<String, GeoPointData>? {
        return try {
            client.newCall(request).execute().use { response ->
                if (!quiet) LogManager.add("GET ${request.url}", 3)
                if (!quiet) LogManager.add("GET response: ${response.code}")
                if (response.code == 429) {
                    noteRateLimit(context, response)
                    return null
                }
                if (response.code == 404) {
                    if (!quiet) LogManager.add("Session is not public or the public token is wrong")
                    return null
                }
                if (!response.isSuccessful) {
                    if (!quiet) LogManager.add("GET failed: HTTP ${response.code}")
                    return null
                }
                if (request.header("Authorization") != null) {
                    context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
                        .edit().putBoolean("credentials_verified", true).apply()
                }
                val body = response.body?.string().orEmpty()
                val trimmed = body.trim()
                if (trimmed.startsWith("{")) {
                    val root = JSONObject(trimmed)
                    if (root.has("error") && !root.has(token)) {
                        if (!quiet) LogManager.add("GET failed: ${root.optString("error")}")
                        return null
                    }
                }
                val devices = parseLastPositions(trimmed, token)
                if (!quiet) LogManager.add("GET body ${trimmed.take(500)}", 3)
                if (!quiet) LogManager.add("GET parsed ${devices.size} devices")
                devices
            }
        } catch (e: Exception) {
            if (!quiet) LogManager.add("GET failed: ${e.message}")
            null
        }
    }

    /**
     * PhoneTrack json-encodes an empty device map as []. That is zero devices, not a failure.
     * A normal payload is {"sessionToken":{"device":{"lat":1,"lon":2,...}}}.
     */
    fun parseLastPositions(body: String, token: String): Map<String, GeoPointData> {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || trimmed == "null" || trimmed.startsWith("[")) return emptyMap()
        val json = JSONObject(trimmed)
        val session = asDeviceMap(positionPayload(json, token))
        val devices = mutableMapOf<String, GeoPointData>()
        val keys = session.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val dev = session.optJSONObject(name) ?: continue
            if (!dev.has("lat") || !dev.has("lon")) continue
            devices[name] = GeoPointData(
                lat = dev.getDouble("lat"),
                lon = dev.getDouble("lon"),
                timestamp = dev.optLong("timestamp", 0),
                accuracy = dev.optDouble("accuracy", 0.0)
            )
        }
        return devices
    }

    private fun positionPayload(json: JSONObject, token: String): Any? {
        if (json.has(token)) return json.opt(token)
        val data = json.optJSONObject("ocs")?.opt("data")
        if (data is JSONObject) {
            if (data.has(token)) return data.opt(token)
            if (data.length() > 0) return data.opt(data.keys().next())
        }
        if (data is JSONArray) return data
        if (json.length() == 0) return null
        return json.opt(json.keys().next())
    }

    private fun asDeviceMap(value: Any?): JSONObject {
        return when (value) {
            is JSONObject -> value
            is JSONArray -> {
                val obj = JSONObject()
                for (i in 0 until value.length()) {
                    val dev = value.optJSONObject(i) ?: continue
                    val name = dev.optString("name").ifBlank {
                        dev.optString("useragent")
                    }.ifBlank { "device$i" }
                    obj.put(name, dev)
                }
                obj
            }
            else -> JSONObject()
        }
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
                    if (response.code == 429) {
                        noteRateLimit(context, response)
                        return emptyList<RemoteSession>() to "Server rate limit. Wait and try again."
                    }
                    if (response.code == 401 || response.code == 403) {
                        prefs.edit().putBoolean("credentials_verified", false).apply()
                        return emptyList<RemoteSession>() to "Nextcloud rejected the username or app password"
                    }
                    if (!response.isSuccessful) {
                        lastError = "HTTP ${response.code}"
                        return@use
                    }
                    val parsed = parseSessionList(body)
                    if (parsed != null) {
                        prefs.edit().putBoolean("credentials_verified", true).apply()
                        return parsed to null
                    }
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
                        out.add(RemoteSession(name, token, item.optString(2)))
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
        out.add(RemoteSession(item.optString("name").ifBlank { token.take(8) }, token, item.optString("publicviewtoken"), item.optString("user")))
    }

    fun createRemoteSession(
        context: Context,
        baseUrl: String,
        user: String,
        password: String,
        name: String
    ): Pair<CreatedSession?, String?> {
        val prefs = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        val client = CertHelper.buildClient(prefs)
        val server = PhoneTrackUrl.parse(baseUrl).base
        val auth = okhttp3.Credentials.basic(user, password)
        val postUrl = "$server/index.php/apps/phonetrack/session"
        try {
            val request = Request.Builder()
                .url(postUrl)
                .post(okhttp3.FormBody.Builder().add("name", name).build())
                .header("Authorization", auth)
                .header("OCS-APIRequest", "true")
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                LogManager.add("POST session ${response.code}")
                if (response.code == 401 || response.code == 403) {
                    return null to "Nextcloud rejected the username or app password"
                }
                if (text.contains("already_exists")) return null to "A session named $name already exists"
                val created = parseCreatedSession(text, name)
                if (response.isSuccessful && created != null) return created to null
            }
        } catch (e: Exception) {
            LogManager.add("Create session failed: ${e.message}")
        }
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val getUrl = "$server/index.php/apps/phonetrack/api/createsession/$encoded"
        return try {
            val request = Request.Builder()
                .url(getUrl)
                .header("Authorization", auth)
                .header("OCS-APIRequest", "true")
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                LogManager.add("GET createsession ${response.code}")
                if (response.code == 401 || response.code == 403) {
                    return null to "Nextcloud rejected the username or app password"
                }
                val obj = runCatching { JSONObject(text) }.getOrNull()
                    ?: return null to "Could not create the session"
                when (obj.optInt("done", 0)) {
                    1 -> {
                        val token = obj.optString("token")
                        if (token.isBlank()) null to "Could not create the session"
                        else CreatedSession(0, name, token, obj.optString("publicviewtoken")) to null
                    }
                    2 -> null to "A session named $name already exists"
                    else -> null to "Could not create the session"
                }
            }
        } catch (e: Exception) {
            null to (e.message ?: "Could not create the session")
        }
    }

    fun shareRemoteSession(
        context: Context,
        baseUrl: String,
        user: String,
        password: String,
        session: CreatedSession,
        shareWith: String
    ): String? {
        val prefs = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        val client = CertHelper.buildClient(prefs)
        val server = PhoneTrackUrl.parse(baseUrl).base
        val auth = okhttp3.Credentials.basic(user, password)
        if (session.id > 0) {
            val shared = postShare(
                client,
                "$server/index.php/apps/phonetrack/session/${session.id}/share",
                auth,
                okhttp3.FormBody.Builder().add("userId", shareWith).build()
            )
            if (shared == null || shared == "user_not_found") {
                return if (shared == null) null else "No Nextcloud user named $shareWith"
            }
        }
        val fallback = postShare(
            client,
            "$server/index.php/apps/phonetrack/addUserShare",
            auth,
            okhttp3.FormBody.Builder().add("token", session.token).add("userId", shareWith).build()
        )
        return when (fallback) {
            null, "already" -> null
            "user_not_found" -> "No Nextcloud user named $shareWith"
            else -> "Could not share with $shareWith"
        }
    }

    fun deleteRemoteSession(
        context: Context,
        baseUrl: String,
        user: String,
        password: String,
        token: String,
        sessionId: Int
    ): String? {
        val prefs = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        val client = CertHelper.buildClient(prefs)
        val server = PhoneTrackUrl.parse(baseUrl).base
        val auth = okhttp3.Credentials.basic(user, password)
        if (sessionId > 0 && deleteById(client, "$server/index.php/apps/phonetrack/session/$sessionId", auth) == null) {
            return null
        }
        return try {
            val request = Request.Builder()
                .url("$server/index.php/apps/phonetrack/deleteSession")
                .post(okhttp3.FormBody.Builder().add("token", token).build())
                .header("Authorization", auth)
                .header("OCS-APIRequest", "true")
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                LogManager.add("POST deleteSession ${response.code} $text")
                if (response.code == 401 || response.code == 403) {
                    return "Nextcloud rejected the username or app password"
                }
                val done = Regex(""""done"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                when {
                    response.isSuccessful && (done == null || done == 1 || done == 2) -> null
                    done == 2 -> null
                    else -> "Could not delete the session from the server"
                }
            }
        } catch (e: Exception) {
            e.message ?: "Could not delete the session from the server"
        }
    }

    private fun deleteById(client: okhttp3.OkHttpClient, url: String, auth: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .delete()
                .header("Authorization", auth)
                .header("OCS-APIRequest", "true")
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                LogManager.add("DELETE session ${response.code}")
                when {
                    response.isSuccessful -> null
                    response.code == 401 || response.code == 403 -> "auth"
                    text.contains("session_not_found") -> "not_found"
                    else -> "failed"
                }
            }
        } catch (e: Exception) {
            "failed"
        }
    }

    fun searchUsers(context: Context, baseUrl: String, user: String, password: String): Pair<List<Pair<String, String>>, String?> {
        val prefs = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        val client = CertHelper.buildClient(prefs)
        val server = PhoneTrackUrl.parse(baseUrl).base
        val auth = okhttp3.Credentials.basic(user, password)
        return try {
            val request = Request.Builder()
                .url("$server/index.php/apps/phonetrack/getUserList")
                .post(okhttp3.FormBody.Builder().build())
                .header("Authorization", auth)
                .header("OCS-APIRequest", "true")
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.code == 401 || response.code == 403) {
                    return emptyList<Pair<String, String>>() to "Nextcloud rejected the username or app password"
                }
                if (!response.isSuccessful) return emptyList<Pair<String, String>>() to "HTTP ${response.code}"
                val root = unwrap(text) ?: return emptyList<Pair<String, String>>() to "Could not read the user list"
                val users = root.optJSONObject("users") ?: root
                val out = mutableListOf<Pair<String, String>>()
                val keys = users.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    if (id == "users") continue
                    val value = users.opt(id)
                    val label = if (value is String) value else id
                    if (id.isNotBlank() && id != user) out.add(id to label)
                }
                out.sortedBy { it.second.lowercase() } to null
            }
        } catch (e: Exception) {
            emptyList<Pair<String, String>>() to (e.message ?: "Could not load users")
        }
    }

    fun fetchSessionDetail(
        context: Context,
        baseUrl: String,
        user: String,
        password: String,
        token: String
    ): SessionDetail? {
        val prefs = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        val client = CertHelper.buildClient(prefs)
        val server = PhoneTrackUrl.parse(baseUrl).base
        val auth = okhttp3.Credentials.basic(user, password)
        val request = Request.Builder()
            .url("$server/index.php/apps/phonetrack/sessions")
            .header("Authorization", auth)
            .header("OCS-APIRequest", "true")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val root = unwrap(response.body?.string().orEmpty()) ?: return null
                val keys = root.keys()
                while (keys.hasNext()) {
                    val item = root.optJSONObject(keys.next()) ?: continue
                    if (item.optString("token") != token) continue
                    val devices = mutableListOf<RemoteDevice>()
                    val rawDevices = item.optJSONObject("devices")
                    if (rawDevices != null) {
                        val deviceKeys = rawDevices.keys()
                        while (deviceKeys.hasNext()) {
                            val key = deviceKeys.next()
                            val device = rawDevices.optJSONObject(key) ?: continue
                            val name = device.optString("name")
                            if (name.isBlank()) continue
                            devices.add(RemoteDevice(device.optInt("id", key.toIntOrNull() ?: 0), name, device.optString("color")))
                        }
                    }
                    return SessionDetail(
                        id = item.optInt("id"),
                        name = item.optString("name"),
                        token = token,
                        publicToken = item.optString("publicviewtoken"),
                        isPublic = item.optInt("public", 0) == 1 || item.optBoolean("public", false),
                        serverEnabled = item.optInt("enabled", 1) == 1 || item.optBoolean("enabled", true),
                        autoPurge = item.optString("autopurge", "no").ifBlank { "no" },
                        devices = devices.sortedBy { it.name.lowercase() }
                    )
                }
                null
            }
        } catch (e: Exception) {
            LogManager.add("Session detail failed: ${e.message}")
            null
        }
    }

    fun updateRemoteSession(
        context: Context,
        baseUrl: String,
        user: String,
        password: String,
        sessionId: Int,
        isPublic: Boolean?,
        serverEnabled: Boolean?,
        autoPurge: String?
    ): String? {
        if (sessionId <= 0) return "This session has no server id yet"
        val prefs = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        val client = CertHelper.buildClient(prefs)
        val server = PhoneTrackUrl.parse(baseUrl).base
        val auth = okhttp3.Credentials.basic(user, password)
        val body = okhttp3.FormBody.Builder()
        if (isPublic != null) body.add("public", if (isPublic) "1" else "0")
        if (serverEnabled != null) body.add("enabled", if (serverEnabled) "1" else "0")
        if (autoPurge != null) body.add("autopurge", autoPurge)
        return try {
            val request = Request.Builder()
                .url("$server/index.php/apps/phonetrack/session/$sessionId")
                .put(body.build())
                .header("Authorization", auth)
                .header("OCS-APIRequest", "true")
                .build()
            client.newCall(request).execute().use { response ->
                LogManager.add("PUT session ${response.code}")
                if (response.isSuccessful) null else "Could not update the session (HTTP ${response.code})"
            }
        } catch (e: Exception) {
            e.message ?: "Could not update the session"
        }
    }

    fun updateDeviceColor(
        context: Context,
        baseUrl: String,
        user: String,
        password: String,
        sessionId: Int,
        deviceId: Int,
        color: String
    ): String? {
        if (sessionId <= 0 || deviceId <= 0) return null
        val prefs = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        val client = CertHelper.buildClient(prefs)
        val server = PhoneTrackUrl.parse(baseUrl).base
        val auth = okhttp3.Credentials.basic(user, password)
        val hex = color.removePrefix("#")
        return try {
            val request = Request.Builder()
                .url("$server/index.php/apps/phonetrack/session/$sessionId/device/$deviceId")
                .put(okhttp3.FormBody.Builder().add("color", hex).build())
                .header("Authorization", auth)
                .header("OCS-APIRequest", "true")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) null else "Could not save the color (HTTP ${response.code})"
            }
        } catch (e: Exception) {
            e.message
        }
    }

    fun getTrackPoints(
        context: Context,
        baseUrl: String,
        token: String,
        ageOutMin: Int
    ): Map<String, List<TrackPoint>> {
        val prefs = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        val user = prefs.getString("nc_user", "")?.trim().orEmpty()
        val pass = prefs.getString("nc_pass", "").orEmpty()
        if (user.isBlank() || pass.isBlank()) return emptyMap()
        val client = CertHelper.buildClient(prefs)
        val server = PhoneTrackUrl.parse(baseUrl).base
        val tokenEnc = URLEncoder.encode(token, "UTF-8").replace("+", "%20")
        val tsmin = System.currentTimeMillis() / 1000 - ageOutMin.coerceAtLeast(1) * 60
        val url = "$server/index.php/apps/phonetrack/api/getuserpositions/$tokenEnc?limit=500&tsmin=$tsmin"
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", okhttp3.Credentials.basic(user, pass))
                .header("OCS-APIRequest", "true")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    LogManager.add("Tracks HTTP ${response.code}")
                    return emptyMap()
                }
                val text = response.body?.string().orEmpty()
                val root = unwrap(text) ?: run {
                    LogManager.add("Tracks response was not a session")
                    return emptyMap()
                }
                val session = trackSession(root, token) ?: run {
                    LogManager.add("Tracks session was missing")
                    return emptyMap()
                }
                val out = mutableMapOf<String, List<TrackPoint>>()
                val names = session.keys()
                while (names.hasNext()) {
                    val name = names.next()
                    val list = when (val value = session.opt(name)) {
                        is org.json.JSONArray -> readTrackPoints(value)
                        is JSONObject -> readTrackPoints(value.optJSONArray("points"))
                        else -> emptyList()
                    }
                    if (list.isNotEmpty()) out[name] = list
                }
                LogManager.add("Tracks parsed ${out.values.sumOf { it.size }} points")
                out
            }
        } catch (e: Exception) {
            LogManager.add("Tracks failed: ${e.message}")
            emptyMap()
        }
    }

    private fun trackSession(root: JSONObject, token: String): JSONObject? {
        root.optJSONObject(token)?.let { return it }
        val keys = root.keys()
        while (keys.hasNext()) {
            val child = root.optJSONObject(keys.next()) ?: continue
            if (looksLikeTrackSession(child)) return child
        }
        return if (looksLikeTrackSession(root)) root else null
    }

    private fun looksLikeTrackSession(obj: JSONObject): Boolean {
        val keys = obj.keys()
        while (keys.hasNext()) {
            when (val value = obj.opt(keys.next())) {
                is org.json.JSONArray -> return true
                is JSONObject -> if (value.has("points")) return true
            }
        }
        return false
    }

    private fun readTrackPoints(points: org.json.JSONArray?): List<TrackPoint> {
        if (points == null) return emptyList()
        val list = mutableListOf<TrackPoint>()
        for (i in 0 until points.length()) {
            val point = points.optJSONObject(i) ?: continue
            val lat = point.optDouble("lat", Double.NaN)
            val lon = point.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            var timestamp = point.optLong("timestamp")
            if (timestamp > 10_000_000_000L) timestamp /= 1000
            list.add(TrackPoint(lat, lon, timestamp))
        }
        return list
    }

    private fun unwrap(text: String): JSONObject? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val data = if (root.has("ocs")) root.getJSONObject("ocs").opt("data") else root
        return data as? JSONObject
    }

    private fun postShare(
        client: okhttp3.OkHttpClient,
        url: String,
        auth: String,
        body: okhttp3.RequestBody
    ): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .post(body)
                .header("Authorization", auth)
                .header("OCS-APIRequest", "true")
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                LogManager.add("POST share ${response.code}")
                val obj = runCatching { JSONObject(text) }.getOrNull()
                if (obj?.optString("error") == "user_not_found" || response.code == 404) return "user_not_found"
                if (!response.isSuccessful) return "failed"
                when (obj?.optInt("done", -1)) {
                    1 -> null
                    2 -> "already"
                    4 -> "user_not_found"
                    -1 -> if (obj.has("username") || obj.has("sharetoken")) null else "failed"
                    else -> "failed"
                }
            }
        } catch (e: Exception) {
            LogManager.add("Share failed: ${e.message}")
            "failed"
        }
    }

    private fun parseCreatedSession(text: String, name: String): CreatedSession? {
        val obj = runCatching { JSONObject(text.trim()) }.getOrNull() ?: return null
        val data = if (obj.has("ocs")) obj.getJSONObject("ocs").optJSONObject("data") ?: obj else obj
        val token = data.optString("token")
        if (token.isBlank() || data.has("error")) return null
        return CreatedSession(
            data.optInt("id", 0),
            data.optString("name").ifBlank { name },
            token,
            data.optString("publicviewtoken")
        )
    }
}

data class GeoPointData(val lat: Double, val lon: Double, val timestamp: Long, val accuracy: Double)

data class TrackPoint(val lat: Double, val lon: Double, val timestamp: Long)

data class RemoteDevice(val id: Int, val name: String, val color: String)

data class SessionDetail(
    val id: Int,
    val name: String,
    val token: String,
    val publicToken: String,
    val isPublic: Boolean,
    val serverEnabled: Boolean,
    val autoPurge: String,
    val devices: List<RemoteDevice>
)

data class RemoteSession(val name: String, val token: String, val publicToken: String = "", val owner: String = "")

data class CreatedSession(val id: Int, val name: String, val token: String, val publicToken: String)
