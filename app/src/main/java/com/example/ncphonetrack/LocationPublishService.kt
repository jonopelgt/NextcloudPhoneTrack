package com.example.ncphonetrack

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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

        if (nextcloudUrl.isBlank() || token.isBlank()) {
            LogManager.add("Service stopped: missing URL or token")
            prefs.edit().putString("logging_state", "stopped").apply()
            stopSelf()
            return START_NOT_STICKY
        }

        LogManager.add("Service started for $deviceName")
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

    private fun logUrl(): String {
        val parsed = PhoneTrackUrl.parse(nextcloudUrl)
        nextcloudUrl = parsed.base
        val fromUrl = parsed.token
        if (token.isBlank() && !fromUrl.isNullOrBlank()) token = fromUrl
        val encToken = URLEncoder.encode(token, "UTF-8").replace("+", "%20")
        val encDevice = URLEncoder.encode(deviceName, "UTF-8").replace("+", "%20")
        return "$nextcloudUrl/index.php/apps/phonetrack/logPost/$encToken/$encDevice"
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
        val point = pointFrom(fix)
        LogManager.add("Fix lat=${fix.latitude} lon=${fix.longitude}")
        prefs.edit().putString("logging_state", "tracking").apply()
        io.execute {
            appendBuffer(point)
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
        val url = logUrl()
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
                LogManager.add("POST ${response.code} ${response.message}")
                if (response.code == 429) PhoneTrackApi.noteRateLimit(this, response)
                if (response.isSuccessful) "ok" else "rejected"
            }
        } catch (e: Exception) {
            LogManager.add("POST failed: ${e.message}")
            "network"
        }
    }

    private fun bufferFile(): File = File(filesDir, "pending_points.json")

    private fun readBuffer(): JSONArray {
        return try {
            val text = bufferFile().takeIf { it.exists() }?.readText().orEmpty()
            if (text.isBlank()) JSONArray() else JSONArray(text)
        } catch (e: Exception) {
            LogManager.add("Buffer unreadable, starting fresh: ${e.message}")
            JSONArray()
        }
    }

    private fun writeBuffer(arr: JSONArray) {
        bufferFile().writeText(arr.toString())
        prefs.edit().putInt("buffer_count", arr.length()).apply()
    }

    private fun bufferCount(): Int = synchronized(bufferLock) { readBuffer().length() }

    private fun pointKey(point: JSONObject): String {
        return "${point.optLong("timestamp")}:${point.optDouble("lat")}:${point.optDouble("lon")}"
    }

    private fun peekHead(): JSONObject? = synchronized(bufferLock) {
        val arr = readBuffer()
        if (arr.length() == 0) null else JSONObject(arr.getJSONObject(0).toString())
    }

    private fun dropHead(point: JSONObject) {
        val key = pointKey(point)
        synchronized(bufferLock) {
            val arr = readBuffer()
            if (arr.length() == 0) return
            if (pointKey(arr.getJSONObject(0)) == key) {
                arr.remove(0)
            } else {
                for (i in 0 until arr.length()) {
                    if (pointKey(arr.getJSONObject(i)) == key) {
                        arr.remove(i)
                        break
                    }
                }
            }
            writeBuffer(arr)
        }
    }

    private fun appendBuffer(point: JSONObject) {
        synchronized(bufferLock) {
            val arr = readBuffer()
            arr.put(point)
            var dropped = 0
            while (arr.length() > MAX_BUFFER) {
                arr.remove(0)
                dropped++
            }
            writeBuffer(arr)
            val waiting = arr.length()
            if (waiting > 1 || !online()) {
                LogManager.add("Buffered point ($waiting waiting)")
            }
            if (dropped > 0) LogManager.add("Buffer full, dropped $dropped oldest")
        }
    }

    private fun trySend() {
        if (!online()) {
            if (bufferCount() > 0) recordResult("network")
            return
        }
        if (PhoneTrackApi.rateLimited(this)) {
            if (bufferCount() > 0) recordResult("buffered")
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
        val client = logClient()
        while (burstSent < BURST) {
            val head = peekHead() ?: run {
                if (burstSent > 0) recordResult("buffer cleared")
                return
            }
            when (postSync(client, head)) {
                "ok" -> {
                    dropHead(head)
                    burstSent += 1
                }
                "network" -> {
                    recordResult("network")
                    return
                }
                else -> {
                    recordResult("buffered")
                    return
                }
            }
        }
        val left = bufferCount()
        recordResult(if (left == 0) "buffer cleared" else "ok")
        if (left > 0 && !PhoneTrackApi.rateLimited(this)) scheduleDrain(1000L)
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

    private fun recordResult(result: String) {
        val state = prefs.getString("logging_state", "") ?: ""
        val noFix = state == "location off" || state == "permission denied" ||
            state == "waiting for GPS" || state == "no position"
        val editor = prefs.edit().putString("last_result", result)
        if (result == "ok" || result == "buffer cleared" || !noFix) {
            editor.putString("logging_state", "tracking")
        }
        if (result == "ok" || result == "buffer cleared") {
            val now = System.currentTimeMillis()
            editor.putLong("last_success_epoch", now)
            editor.putLong("last_publish_epoch", now)
        }
        editor.apply()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("location", "Location Tracking", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "location")
            .setContentTitle("PhoneTrack Sharing")
            .setContentText("Publishing location to Nextcloud")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
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
