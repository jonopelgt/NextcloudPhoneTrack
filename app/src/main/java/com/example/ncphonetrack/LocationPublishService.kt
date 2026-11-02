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
import android.net.ConnectivityManager
import android.net.Network
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

class LocationPublishService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var prefs: SharedPreferences
    private var locationCallback: LocationCallback? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var nextcloudUrl: String = ""
    private var token: String = ""
    private var deviceName: String = ""
    private val bufferLock = Any()
    private val flushing = AtomicBoolean(false)

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
        prefs.edit().putString("logging_state", "tracking").apply()
        if (intent?.action == ACTION_PUBLISH_ONCE) {
            publishOnce()
        } else {
            startLocationUpdates()
        }
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
        val mode = prefs.getString("position_mode", "move") ?: "move"
        if (mode == "quiet" || mode == "manual") {
            prefs.edit().putString("logging_state", if (mode == "quiet") "off" else "manual").apply()
            LogManager.add(if (mode == "quiet") "Position updates are off" else "Position updates are manual")
            return
        }

        val locationRequest = when (mode) {
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
            LogManager.add(when (mode) {
                "significant" -> "Significant location updates"
                "rapid" -> "Location updates every 1s"
                else -> "Location updates every ${publishIntervalMs() / 1000}s"
            })
        } catch (e: SecurityException) {
            LogManager.add("Location permission missing")
            prefs.edit().putString("logging_state", "permission denied").apply()
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
                if (location != null) publishLocation(location) else LogManager.add("No location to send")
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
        return JSONObject()
            .put("lat", location.latitude)
            .put("lon", location.longitude)
            .put("timestamp", System.currentTimeMillis() / 1000)
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
        if (PhoneTrackApi.rateLimited(this)) {
            appendBuffer(point)
            recordResult("buffered")
            return
        }
        Thread {
            val client = CertHelper.buildClient(prefs)
            if (postSync(client, point)) {
                recordResult("ok")
                flushLocked(client)
            } else {
                appendBuffer(point)
                recordResult("buffered")
            }
        }.start()
    }

    private fun postSync(client: OkHttpClient, point: JSONObject): Boolean {
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
                response.isSuccessful
            }
        } catch (e: Exception) {
            LogManager.add("POST failed: ${e.message}")
            false
        }
    }

    private fun bufferFile(): File = File(filesDir, "pending_points.json")

    private fun readBuffer(): JSONArray {
        return try {
            val text = bufferFile().takeIf { it.exists() }?.readText().orEmpty()
            if (text.isBlank()) JSONArray() else JSONArray(text)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun writeBuffer(arr: JSONArray) {
        bufferFile().writeText(arr.toString())
        prefs.edit().putInt("buffer_count", arr.length()).apply()
    }

    private fun appendBuffer(point: JSONObject) {
        synchronized(bufferLock) {
            val arr = readBuffer()
            arr.put(point)
            while (arr.length() > 30) arr.remove(0)
            writeBuffer(arr)
            LogManager.add("Buffered point (${arr.length()} waiting)")
        }
    }

    private fun flushBufferAsync() {
        Thread {
            val client = CertHelper.buildClient(prefs)
            flushLocked(client)
        }.start()
    }

    private fun flushLocked(client: OkHttpClient) {
        if (!flushing.compareAndSet(false, true)) return
        try {
            val snapshot: JSONArray = synchronized(bufferLock) { readBuffer() }
            if (snapshot.length() == 0) return
            if (PhoneTrackApi.rateLimited(this)) return
            val remaining = JSONArray()
            var failed = false
            var sent = 0
            for (i in 0 until snapshot.length()) {
                val point = snapshot.getJSONObject(i)
                if (failed || sent >= 3) {
                    remaining.put(point)
                    continue
                }
                if (postSync(client, point)) sent += 1 else {
                    failed = true
                    remaining.put(point)
                }
            }
            synchronized(bufferLock) { writeBuffer(remaining) }
            LogManager.add("Buffer retry left ${remaining.length()}")
            if (remaining.length() == 0) recordResult("buffer cleared")
        } finally {
            flushing.set(false)
        }
    }

    private fun recordResult(result: String) {
        val editor = prefs.edit()
            .putString("last_result", result)
            .putString("logging_state", "tracking")
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
        locationCallback?.let {
            if (::fusedLocationClient.isInitialized) fusedLocationClient.removeLocationUpdates(it)
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
    }
}
