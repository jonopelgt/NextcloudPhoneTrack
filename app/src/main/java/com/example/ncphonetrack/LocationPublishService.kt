package com.example.ncphonetrack

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.CancellationTokenSource
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LocationPublishService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var prefs: SharedPreferences
    private var locationCallback: LocationCallback? = null
    private var legacyListener: LocationListener? = null
    private val legacyStarted = AtomicBoolean(false)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var nextcloudUrl: String = ""
    private var token: String = ""
    private var deviceName: String = ""
    private val bufferLock = Any()
    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val drainScheduled = AtomicBoolean(false)
    private var burstSent = 0
    private var burstWindowStart = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        prefs = getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        nextcloudUrl = intent?.getStringExtra("url")?.takeIf { it.isNotBlank() }
            ?: prefs.getString("url", "") ?: ""
        token = intent?.getStringExtra("token")?.takeIf { it.isNotBlank() }
            ?: prefs.getString("token", "") ?: ""
        deviceName = prefs.getString("nc_user", "")?.trim().orEmpty().ifEmpty {
            intent?.getStringExtra("device")?.takeIf { it.isNotBlank() }
                ?: prefs.getString("device", "Android") ?: "Android"
        }

        createNotificationChannel()
        startForeground(1, createNotification())

        if (enabledTargets().isEmpty()) {
            LogManager.add("Service stopped: no enabled session")
            prefs.edit().putString("logging_state", "stopped").apply()
            stopSelf()
            return START_NOT_STICKY
        }

        LogManager.add("Service started for " + enabledTargets().joinToString(", ") {
            it.optString("name").ifBlank { "Unnamed session" }
        })
        prefs.edit().putString("logging_state", "waiting for GPS").apply()
        startLocationUpdates()
        if (intent?.action == ACTION_PUBLISH_ONCE) publishOnce()
        watchNetwork()
        flushBufferAsync()
        return START_STICKY
    }

    private fun publishIntervalMs(): Long {
        val sec = prefs.getInt("publish_interval_sec", 60).coerceIn(5, 3600)
        return sec * 1000L
    }

    private fun startLocationUpdates() {
        if (!::fusedLocationClient.isInitialized) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        }
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        legacyListener?.let { listener ->
            try {
                getSystemService(LocationManager::class.java)?.removeUpdates(listener)
            } catch (_: Exception) {
            }
        }
        legacyListener = null
        legacyStarted.set(false)
        val mode = prefs.getString("position_mode", "move") ?: "move"
        val spoofing = prefs.getBoolean("spoof_enabled", false)
        if (!spoofing && (mode == "quiet" || mode == "manual")) {
            prefs.edit().putString("logging_state", if (mode == "quiet") "off" else "manual").apply()
            LogManager.add(if (mode == "quiet") "Position updates are off" else "Position updates are manual")
            return
        }

        val locationRequest = if (spoofing) {
            val interval = publishIntervalMs()
            LocationRequest.create().apply {
                this.interval = interval
                fastestInterval = interval
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            }
        } else when (mode) {
            "significant" -> LocationRequest.create().apply {
                interval = 5 * 60 * 1000L
                fastestInterval = 60 * 1000L
                smallestDisplacement = 100f
                priority = LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
            }
            "rapid" -> LocationRequest.create().apply {
                interval = 1000L
                fastestInterval = 1000L
                smallestDisplacement = 0f
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            }
            else -> {
                val interval = publishIntervalMs()
                LocationRequest.create().apply {
                    this.interval = interval
                    fastestInterval = interval
                    priority = LocationRequest.PRIORITY_HIGH_ACCURACY
                }
            }
        }

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { publishLocation(it) }
            }
        }
        locationCallback = callback

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())
                .addOnFailureListener { error ->
                    LogManager.add("Play location failed: ${error.message}")
                    startLegacyUpdates()
                }
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    if (location != null) publishLocation(location)
                    else LogManager.add("No last location yet")
                }
                .addOnFailureListener { error ->
                    LogManager.add("Last location failed: ${error.message}")
                }
            fusedLocationClient.getCurrentLocation(
                LocationRequest.PRIORITY_HIGH_ACCURACY,
                CancellationTokenSource().token
            ).addOnSuccessListener { location ->
                if (location != null) publishLocation(location)
            }.addOnFailureListener { error ->
                LogManager.add("Current location failed: ${error.message}")
                startLegacyUpdates()
            }
            LogManager.add(when {
                spoofing -> "Spoofed location every ${publishIntervalMs() / 1000}s"
                mode == "significant" -> "Significant location updates"
                mode == "rapid" -> "Location updates every 1s"
                else -> "Location updates every ${publishIntervalMs() / 1000}s"
            })
        } catch (e: SecurityException) {
            LogManager.add("Location permission missing")
            prefs.edit().putString("logging_state", "permission denied").apply()
        }
    }

    private fun startLegacyUpdates() {
        if (!legacyStarted.compareAndSet(false, true)) return
        val manager = getSystemService(LocationManager::class.java) ?: return
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> {
                LogManager.add("Location providers are off")
                prefs.edit().putString("logging_state", "location off").apply()
                return
            }
        }
        val listener = LocationListener { publishLocation(it) }
        legacyListener = listener
        try {
            manager.requestLocationUpdates(provider, publishIntervalMs(), 0f, listener, Looper.getMainLooper())
            LogManager.add("Location updates from $provider")
            manager.getLastKnownLocation(provider)?.let { publishLocation(it) }
        } catch (e: SecurityException) {
            LogManager.add("Location permission missing")
            prefs.edit().putString("logging_state", "permission denied").apply()
            legacyListener = null
        }
    }

    private fun publishOnce() {
        spoofedLocation()?.let {
            publishLocation(it)
            return
        }
        if (!::fusedLocationClient.isInitialized) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        }
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) publishLocation(location) else {
                    LogManager.add("No location to send")
                    prefs.edit().putString("logging_state", "no position").apply()
                }
            }
        } catch (e: SecurityException) {
            LogManager.add("Location permission missing")
        }
    }

    private fun spoofedLocation(): Location? {
        if (!prefs.getBoolean("spoof_enabled", false)) return null
        val lat = prefs.getString("spoof_lat", null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString("spoof_lon", null)?.toDoubleOrNull() ?: return null
        return Location("spoof").apply {
            latitude = lat
            longitude = lon
            accuracy = 1f
            time = System.currentTimeMillis()
        }
    }

    private fun watchNetwork() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                LogManager.add("Network available, retrying buffered points")
                flushBufferAsync()
            }
        }
        networkCallback = callback
        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            LogManager.add("Network callback failed: ${e.message}")
            networkCallback = null
        }
    }

    private fun getBatteryLevel(): Int {
        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else 0
    }

    private fun enabledTargets(): List<JSONObject> {
        val raw = prefs.getString("sessions", "[]") ?: "[]"
        val out = mutableListOf<JSONObject>()
        val arr = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return out
        }
        for (i in 0 until arr.length()) {
            val session = arr.optJSONObject(i) ?: continue
            if (!session.optBoolean("enabled", true)) continue
            if (session.optString("url").isBlank() || session.optString("token").isBlank()) continue
            out.add(session)
        }
        return out
    }

    private fun logUrl(point: JSONObject): String {
        val raw = point.optString("url").ifBlank { nextcloudUrl }
        val parsed = PhoneTrackUrl.parse(raw)
        val sessionToken = point.optString("token").ifBlank { token }
        val sessionDevice = point.optString("device").ifBlank { deviceName }
        val encToken = URLEncoder.encode(sessionToken, "UTF-8").replace("+", "%20")
        val encDevice = URLEncoder.encode(sessionDevice, "UTF-8").replace("+", "%20")
        return "${parsed.base}/index.php/apps/phonetrack/logPost/$encToken/$encDevice"
    }

    private fun pointFrom(location: Location): JSONObject {
        val now = System.currentTimeMillis()
        val millis = when {
            location.time <= 0L -> now
            location.time > now + 60_000L -> now
            else -> location.time
        }
        return JSONObject()
            .put("lat", location.latitude)
            .put("lon", location.longitude)
            .put("timestamp", millis / 1000)
            .put("acc", location.accuracy.toDouble())
            .put("alt", location.altitude)
            .put("bat", getBatteryLevel())
            .put("speed", location.speed.toDouble())
            .put("bearing", location.bearing.toDouble())
    }

    private fun publishLocation(location: Location) {
        val fix = spoofedLocation() ?: location
        prefs.edit()
            .putString("own_lat", fix.latitude.toString())
            .putString("own_lon", fix.longitude.toString())
            .apply()
        val targets = enabledTargets()
        if (targets.isEmpty()) {
            LogManager.add("Fix not sent: no enabled session")
            return
        }
        LogManager.add("Fix lat=${fix.latitude} lon=${fix.longitude}")
        prefs.edit().putString("logging_state", "tracking").apply()
        io.execute {
            targets.forEach { session ->
                val name = session.optString("name").ifBlank { "Unnamed session" }
                val point = pointFrom(fix)
                    .put("url", session.optString("url"))
                    .put("token", session.optString("token"))
                    .put("device", session.optString("device").ifBlank { deviceName })
                    .put("name", name)
                appendBuffer(point)
                LogManager.add("$name: queued position")
            }
            trySend()
        }
    }

    private fun online(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun logClient(): OkHttpClient {
        return CertHelper.buildClient(prefs)
            .newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private fun postSync(client: OkHttpClient, point: JSONObject): String {
        val url = logUrl(point)
        val body = FormBody.Builder()
            .add("lat", point.optDouble("lat").toString())
            .add("lon", point.optDouble("lon").toString())
            .add("timestamp", point.optLong("timestamp").toString())
            .add("acc", point.optDouble("acc").toString())
            .add("alt", point.optDouble("alt").toString())
            .add("bat", point.optInt("bat").toString())
            .add("speed", point.optDouble("speed").toString())
            .add("bearing", point.optDouble("bearing").toString())
            .build()
        return try {
            client.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
                val name = point.optString("name").ifBlank { "Session" }
                LogManager.add(
                    "$name: POST $url lat=${point.optDouble("lat")} lon=${point.optDouble("lon")} " +
                        "acc=${point.optDouble("acc")} alt=${point.optDouble("alt")} bat=${point.optInt("bat")} " +
                        "speed=${point.optDouble("speed")} bearing=${point.optDouble("bearing")}",
                    3
                )
                LogManager.add("$name: POST ${response.code} ${response.message}")
                if (response.code == 429) PhoneTrackApi.noteRateLimit(this, response)
                if (response.isSuccessful) "ok" else "rejected"
            }
        } catch (e: Exception) {
            val name = point.optString("name").ifBlank { "Session" }
            LogManager.add("$name: POST failed: ${e.message}")
            "network"
        }
    }

    private var bufferCursor = 0

    private fun bufferFile(): File = File(filesDir, "pending_points.json")

    private fun readQueues(): JSONObject {
        return try {
            val text = bufferFile().takeIf { it.exists() }?.readText().orEmpty().trim()
            if (text.isBlank()) return JSONObject()
            if (text.startsWith("[")) {
                val arr = JSONArray(text)
                val queues = JSONObject()
                for (i in 0 until arr.length()) {
                    val point = arr.getJSONObject(i)
                    val token = point.optString("token")
                    if (token.isBlank()) continue
                    val queue = queues.optJSONArray(token) ?: JSONArray().also { queues.put(token, it) }
                    queue.put(point)
                }
                return queues
            }
            JSONObject(text)
        } catch (e: Exception) {
            LogManager.add("Buffer unreadable, starting fresh: ${e.message}")
            JSONObject()
        }
    }

    private fun writeQueues(queues: JSONObject) {
        bufferFile().writeText(queues.toString())
        prefs.edit().putInt("buffer_count", queueTotal(queues)).apply()
    }

    private fun queueTotal(queues: JSONObject): Int {
        var total = 0
        val keys = queues.keys()
        while (keys.hasNext()) total += queues.optJSONArray(keys.next())?.length() ?: 0
        return total
    }

    private fun bufferCount(): Int = synchronized(bufferLock) { queueTotal(readQueues()) }

    private fun pointKey(point: JSONObject): String {
        return "${point.optString("token")}:${point.optLong("timestamp")}:${point.optDouble("lat")}:${point.optDouble("lon")}"
    }

    private fun peekToken(token: String): JSONObject? = synchronized(bufferLock) {
        val queue = readQueues().optJSONArray(token) ?: return null
        if (queue.length() == 0) null else JSONObject(queue.getJSONObject(0).toString())
    }

    private fun dropHead(point: JSONObject) {
        val token = point.optString("token")
        val key = pointKey(point)
        synchronized(bufferLock) {
            val queues = readQueues()
            val queue = queues.optJSONArray(token) ?: return
            for (i in 0 until queue.length()) {
                if (pointKey(queue.getJSONObject(i)) == key) {
                    queue.remove(i)
                    break
                }
            }
            if (queue.length() == 0) queues.remove(token)
            writeQueues(queues)
        }
    }

    private fun appendBuffer(point: JSONObject) {
        val token = point.optString("token")
        if (token.isBlank()) return
        synchronized(bufferLock) {
            val queues = readQueues()
            val queue = queues.optJSONArray(token) ?: JSONArray().also { queues.put(token, it) }
            queue.put(point)
            var dropped = 0
            while (queue.length() > MAX_BUFFER) {
                queue.remove(0)
                dropped++
            }
            writeQueues(queues)
            val name = point.optString("name").ifBlank { "Session" }
            val waiting = queue.length()
            if (waiting > 1 || !online()) LogManager.add("$name: buffered point ($waiting waiting)")
            if (dropped > 0) LogManager.add("$name: buffer full, dropped $dropped oldest")
        }
    }

    private fun deferHead(point: JSONObject) {
        val token = point.optString("token")
        val key = pointKey(point)
        synchronized(bufferLock) {
            val queues = readQueues()
            val queue = queues.optJSONArray(token) ?: return
            var removed: JSONObject? = null
            for (i in 0 until queue.length()) {
                if (pointKey(queue.getJSONObject(i)) == key) {
                    removed = queue.getJSONObject(i)
                    queue.remove(i)
                    break
                }
            }
            if (removed != null) queue.put(removed)
            writeQueues(queues)
        }
    }

    private fun dropDisabled(allowed: Set<String>): Int {
        synchronized(bufferLock) {
            val queues = readQueues()
            var dropped = 0
            val keys = queues.keys().asSequence().toList()
            keys.forEach { token ->
                if (token in allowed) return@forEach
                dropped += queues.optJSONArray(token)?.length() ?: 0
                queues.remove(token)
            }
            if (dropped > 0) writeQueues(queues)
            return dropped
        }
    }

    private fun nextToken(allowed: Set<String>, skipped: Set<String>): String? {
        val queues = synchronized(bufferLock) { readQueues() }
        val ready = allowed.filter { token ->
            token !in skipped && (queues.optJSONArray(token)?.length() ?: 0) > 0
        }
        if (ready.isEmpty()) return null
        bufferCursor = bufferCursor % ready.size
        val token = ready[bufferCursor]
        bufferCursor = (bufferCursor + 1) % ready.size
        return token
    }

    private fun trySend() {
        if (!online()) {
            markBuffered("network")
            return
        }
        if (PhoneTrackApi.rateLimited(this)) {
            markBuffered("buffered")
            return
        }
        val now = System.currentTimeMillis()
        if (now - burstWindowStart >= 1000L) {
            burstWindowStart = now
            burstSent = 0
        }
        if (burstSent >= BURST) {
            scheduleDrain(1000L - (now - burstWindowStart))
            return
        }
        val allowed = enabledTargets().map { it.optString("token") }.filter { it.isNotBlank() }.toSet()
        val dropped = dropDisabled(allowed)
        if (dropped > 0) LogManager.add("Dropped $dropped buffered points for sessions that are off")
        val failed = mutableSetOf<String>()
        val client = logClient()
        var guard = 0
        while (burstSent < BURST && guard < allowed.size * BURST + 1) {
            guard++
            val token = nextToken(allowed, failed) ?: break
            val head = peekToken(token) ?: continue
            when (postSync(client, head)) {
                "ok" -> {
                    dropHead(head)
                    burstSent += 1
                    recordResult(head, "ok")
                }
                "network" -> {
                    recordResult(head, "network")
                    deferHead(head)
                    failed.add(token)
                }
                else -> {
                    recordResult(head, "buffered")
                    deferHead(head)
                    failed.add(token)
                }
            }
        }
        if (bufferCount() > 0 && !PhoneTrackApi.rateLimited(this)) scheduleDrain(1000L)
    }

    private fun markBuffered(result: String) {
        val queues = synchronized(bufferLock) { readQueues() }
        val keys = queues.keys()
        while (keys.hasNext()) {
            val token = keys.next()
            val queue = queues.optJSONArray(token) ?: continue
            if (queue.length() == 0) continue
            recordResult(queue.getJSONObject(0), result)
        }
    }

    private fun scheduleDrain(delayMs: Long) {
        if (!drainScheduled.compareAndSet(false, true)) return
        mainHandler.postDelayed({
            drainScheduled.set(false)
            io.execute { trySend() }
        }, delayMs.coerceAtLeast(0L))
    }

    private fun flushBufferAsync() {
        io.execute { trySend() }
    }

    private fun recordResult(point: JSONObject, result: String) {
        val tokenKey = point.optString("token").ifBlank { token }
        val name = point.optString("name").ifBlank { "Unnamed session" }
        val state = prefs.getString("logging_state", "") ?: ""
        val noFix = state == "location off" || state == "permission denied" ||
            state == "waiting for GPS" || state == "no position"
        val all = try {
            JSONObject(prefs.getString("session_push", "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        val now = System.currentTimeMillis()
        val item = all.optJSONObject(tokenKey) ?: JSONObject()
        item.put("result", result)
        item.put("name", name)
        if (result == "ok") item.put("epoch", now)
        all.put(tokenKey, item)
        val editor = prefs.edit().putString("session_push", all.toString())
        if (result == "ok" || !noFix) editor.putString("logging_state", "tracking")
        if (tokenKey.isNotBlank() && tokenKey == prefs.getString("token", "")) {
            editor.putString("last_result", result)
            if (result == "ok") {
                editor.putLong("last_success_epoch", now)
                editor.putLong("last_publish_epoch", now)
            }
        }
        editor.apply()
        LogManager.add("$name: $result")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "location_broadcast_bar",
                "Position broadcast",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.setSound(null, null)
            channel.enableVibration(false)
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val repost = PendingIntent.getBroadcast(
            this,
            2,
            Intent(this, SharingRepost::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val built = NotificationCompat.Builder(this, "location_broadcast_bar")
            .setContentTitle("Position broadcast")
            .setContentText("Location sharing is active")
            .setSmallIcon(R.drawable.ic_stat_broadcast)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setDeleteIntent(repost)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        built.flags = built.flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
        return built
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        locationCallback?.let {
            if (::fusedLocationClient.isInitialized) fusedLocationClient.removeLocationUpdates(it)
        }
        legacyListener?.let { listener ->
            try {
                getSystemService(LocationManager::class.java)?.removeUpdates(listener)
            } catch (_: Exception) {
            }
        }
        networkCallback?.let { callback ->
            try {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_PUBLISH_ONCE = "com.example.ncphonetrack.PUBLISH_ONCE"
        private const val MAX_BUFFER = 7200
        private const val BURST = 3
    }
}
