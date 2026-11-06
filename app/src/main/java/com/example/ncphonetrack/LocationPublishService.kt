package com.example.ncphonetrack

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.concurrent.RejectedExecutionException
import okhttp3.ConnectionPool
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
import java.util.concurrent.atomic.AtomicInteger

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
    private val lanes = Array(10) { Executors.newSingleThreadExecutor() }
    private val laneClients = arrayOfNulls<OkHttpClient>(10)
    private val laneCursor = AtomicInteger(0)
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val drainScheduled = AtomicBoolean(false)
    private val sentKeys = LinkedHashSet<String>()
    private var motionListener: TriggerEventListener? = null
    private var lowPowerGeneration = 0
    private var forceNext = false
    private var autoWatching = false
    private var pendingAuto: String? = null
    private var activityIntent: PendingIntent? = null
    private val applyAuto = Runnable {
        val next = pendingAuto ?: return@Runnable
        pendingAuto = null
        if (storedMode() != "auto" || batteryForcesLowPower()) return@Runnable
        if (commitAutoMode(next)) startLocationUpdates()
    }
    private val activityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val received = intent ?: return
            if (!ActivityRecognitionResult.hasResult(received)) return
            val activity = ActivityRecognitionResult.extractResult(received)?.mostProbableActivity ?: return
            DiagLog.noteActivity(this@LocationPublishService, DiagLog.activityName(activity.type))
            val candidate = when (activity.type) {
                DetectedActivity.STILL -> "significant"
                DetectedActivity.ON_FOOT, DetectedActivity.WALKING, DetectedActivity.RUNNING -> "walk"
                DetectedActivity.IN_VEHICLE, DetectedActivity.ON_BICYCLE -> "move"
                else -> return
            }
            noteAutoCandidate(candidate)
        }
    }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (storedMode() != "auto") return
            if (!batteryForcesLowPower()) return
            pendingAuto = null
            mainHandler.removeCallbacks(applyAuto)
            if (commitAutoMode("significant", "battery")) startLocationUpdates()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        prefs = getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        SharingControl.ensureFirstShare(this)
        nextcloudUrl = intent?.getStringExtra("url")?.takeIf { it.isNotBlank() }
            ?: prefs.getString("url", "") ?: ""
        token = intent?.getStringExtra("token")?.takeIf { it.isNotBlank() }
            ?: prefs.getString("token", "") ?: ""
        deviceName = defaultPostedName(this)

        createNotificationChannel()
        startForeground(1, createNotification())
        if (SharingControl.expireIfDue(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

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
        ensureSeeded(this)
        startLocationUpdates()
        if (intent?.action == ACTION_PUBLISH_ONCE) publishOnce()
        watchNetwork()
        flushBufferAsync()
        armShareTimer()
        return START_STICKY
    }

    private val shareTick = Runnable { onShareTick() }

    private fun armShareTimer() {
        mainHandler.removeCallbacks(shareTick)
        onShareTick()
    }

    private fun onShareTick() {
        if (SharingControl.expireIfDue(this)) {
            stopSelf()
            return
        }
        if (SharingControl.remaining(this) <= 0L) return
        getSystemService(NotificationManager::class.java).notify(1, createNotification())
        SharingControl.tick(this)
        mainHandler.postDelayed(shareTick, 60_000L)
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
        stopLowPower()
        if (storedMode() == "auto") {
            ensureAutoWatch()
            if (batteryForcesLowPower()) commitAutoMode("significant", "battery")
        } else {
            stopAutoWatch()
        }
        val mode = activeMode()
        DiagLog.noteMode(this)
        val spoofing = prefs.getBoolean("spoof_enabled", false)
        if (!spoofing && (mode == "quiet" || mode == "manual")) {
            prefs.edit().putString("logging_state", if (mode == "quiet") "off" else "manual").apply()
            LogManager.add(if (mode == "quiet") "Position updates are off" else "Position updates are manual")
            return
        }
        if (!spoofing && mode == "significant") {
            forceNext = true
            try {
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    if (location != null) publishLocation(location)
                }
                fusedLocationClient.getCurrentLocation(
                    LocationRequest.PRIORITY_HIGH_ACCURACY,
                    CancellationTokenSource().token
                ).addOnSuccessListener { location ->
                    if (location != null) publishLocation(location)
                }
            } catch (e: SecurityException) {
                LogManager.add("Location permission missing")
                prefs.edit().putString("logging_state", "permission denied").apply()
                return
            }
            armLowPower()
            LogManager.add("Low power: send once, then after about 300 meters")
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
            "walk" -> LocationRequest.create().apply {
                interval = 10_000L
                fastestInterval = 5_000L
                smallestDisplacement = 30f
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            }
            "rapid" -> LocationRequest.create().apply {
                interval = 1000L
                fastestInterval = 1000L
                smallestDisplacement = 0f
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            }
            else -> LocationRequest.create().apply {
                interval = 5_000L
                fastestInterval = 5_000L
                smallestDisplacement = 0f
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
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
                mode == "walk" -> "Walk: send after about 30 meters"
                mode == "rapid" -> "Location updates every 1s"
                else -> "Move: send every 5s"
            })
        } catch (e: SecurityException) {
            LogManager.add("Location permission missing")
            prefs.edit().putString("logging_state", "permission denied").apply()
        }
    }

    private fun disarmMotion() {
        motionListener?.let { listener ->
            val sensors = getSystemService(SensorManager::class.java)
            val sensor = sensors?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
            if (sensor != null) sensors.cancelTriggerSensor(listener, sensor)
        }
        motionListener = null
    }

    private fun stopLowPower() {
        lowPowerGeneration++
        disarmMotion()
    }

    private fun armLowPower() {
        val generation = lowPowerGeneration
        disarmMotion()
        val sensors = getSystemService(SensorManager::class.java)
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        if (sensors == null || sensor == null) {
            mainHandler.postDelayed({
                if (generation == lowPowerGeneration) checkLowPower(generation, 1, rearm = true)
            }, 120_000L)
            return
        }
        val listener = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                if (generation != lowPowerGeneration) return
                motionListener = null
                checkLowPower(generation, 12, rearm = true)
            }
        }
        motionListener = listener
        if (!sensors.requestTriggerSensor(listener, sensor)) {
            motionListener = null
            mainHandler.postDelayed({
                if (generation == lowPowerGeneration) checkLowPower(generation, 1, rearm = true)
            }, 120_000L)
            return
        }
        mainHandler.postDelayed({
            if (generation == lowPowerGeneration) checkLowPower(generation, 1, rearm = false)
        }, 180_000L)
    }

    private fun checkLowPower(generation: Int, attemptsLeft: Int, rearm: Boolean) {
        if (generation != lowPowerGeneration) return
        if ((activeMode()) != "significant") return
        if (!::fusedLocationClient.isInitialized) return
        try {
            fusedLocationClient.getCurrentLocation(
                LocationRequest.PRIORITY_HIGH_ACCURACY,
                CancellationTokenSource().token
            ).addOnSuccessListener { location ->
                if (generation != lowPowerGeneration) return@addOnSuccessListener
                if (location == null) {
                    if (rearm) armLowPower()
                    return@addOnSuccessListener
                }
                if (movedFar(location, 300f)) {
                    publishLocation(location)
                    if (rearm) armLowPower()
                    return@addOnSuccessListener
                }
                val moving = location.hasSpeed() && location.speed >= 0.5f
                if (!moving || attemptsLeft <= 1) {
                    if (rearm) armLowPower()
                    return@addOnSuccessListener
                }
                mainHandler.postDelayed({ checkLowPower(generation, attemptsLeft - 1, rearm) }, 60_000L)
            }.addOnFailureListener {
                if (generation == lowPowerGeneration && rearm) armLowPower()
            }
        } catch (_: SecurityException) {
            LogManager.add("Location permission missing")
        }
    }

    private fun movedFar(location: Location, meters: Float): Boolean {
        val lat = prefs.getString("sent_lat", null)?.toDoubleOrNull() ?: return true
        val lon = prefs.getString("sent_lon", null)?.toDoubleOrNull() ?: return true
        val out = FloatArray(1)
        Location.distanceBetween(lat, lon, location.latitude, location.longitude, out)
        return out[0] >= meters
    }

    private fun markSent(location: Location) {
        prefs.edit()
            .putString("sent_lat", location.latitude.toString())
            .putString("sent_lon", location.longitude.toString())
            .apply()
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
        val mode = activeMode()
        val period = when (mode) {
            "walk" -> 10_000L
            "rapid" -> 1_000L
            else -> 5_000L
        }
        val distance = if (mode == "walk") 30f else 0f
        try {
            manager.requestLocationUpdates(provider, period, distance, listener, Looper.getMainLooper())
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
                if (location != null) publishLocation(location) else requestCurrentFix()
            }
        } catch (e: SecurityException) {
            LogManager.add("Location permission missing")
        }
    }

    private fun requestCurrentFix() {
        try {
            fusedLocationClient.getCurrentLocation(
                LocationRequest.PRIORITY_HIGH_ACCURACY,
                CancellationTokenSource().token
            ).addOnSuccessListener { location ->
                if (location != null) publishLocation(location) else {
                    LogManager.add("No location to send")
                    prefs.edit().putString("logging_state", "no position").apply()
                }
            }.addOnFailureListener { error ->
                LogManager.add("Current location failed: ${error.message}")
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
        val mode = activeMode()
        val spoofing = prefs.getBoolean("spoof_enabled", false)
        val forced = forceNext
        forceNext = false
        if (!spoofing && !forced) {
            if (mode == "significant" && !movedFar(fix, 300f)) return
            if (mode == "walk" && !movedFar(fix, 30f)) return
        }
        val snapshot = pointFrom(fix)
        prefs.edit()
            .putString("own_lat", snapshot.optDouble("lat").toString())
            .putString("own_lon", snapshot.optDouble("lon").toString())
            .putLong("own_fix_sec", snapshot.optLong("timestamp"))
            .apply()
        val targets = enabledTargets()
        if (targets.isEmpty()) {
            LogManager.add("Fix not sent: no enabled session")
            return
        }
        LogManager.add("Fix lat=${snapshot.optDouble("lat")} lon=${snapshot.optDouble("lon")}")
        logFixClocks(fix)
        prefs.edit().putString("logging_state", "tracking").apply()
        targets.forEach { noteAccepted(this, it.optString("token")) }
        val queued = submit {
            targets.forEach { session ->
                val name = session.optString("name").ifBlank { "Unnamed session" }
                val point = JSONObject(snapshot.toString())
                    .put("url", session.optString("url"))
                    .put("token", session.optString("token"))
                    .put("device", session.optString("device").ifBlank { deviceName })
                    .put("name", name)
                appendBuffer(point)
                LogManager.add("$name: queued position")
            }
            markSent(fix)
            trySend()
        }
        if (!queued) targets.forEach { noteSettled(it.optString("token"), 1) }
    }

    private fun logFixClocks(location: Location) {
        val now = System.currentTimeMillis()
        val elapsedAge = if (location.elapsedRealtimeNanos > 0L) {
            (android.os.SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        } else {
            -1L
        }
        LogManager.add(
            "Fix clocks phone ${now / 1000} fix ${location.time / 1000} elapsed ${elapsedAge}ms provider ${location.provider ?: "unknown"}",
            3
        )
    }

    private fun submit(work: () -> Unit): Boolean {
        if (io.isShutdown) return false
        return try {
            io.execute(work)
            true
        } catch (_: RejectedExecutionException) {
            false
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

    private fun clientFor(index: Int): OkHttpClient {
        synchronized(laneClients) {
            val existing = laneClients[index]
            if (existing != null) return existing
            val client = logClient().newBuilder()
                .connectionPool(ConnectionPool(1, 5, TimeUnit.MINUTES))
                .build()
            client.dispatcher.maxRequests = 1
            client.dispatcher.maxRequestsPerHost = 1
            laneClients[index] = client
            return client
        }
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
                        "ts=${point.optLong("timestamp")} buffer ${bufferCount()} " +
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
        synchronized(sentKeys) {
            sentKeys.add(key)
            while (sentKeys.size > 300) {
                val first = sentKeys.iterator().next()
                sentKeys.remove(first)
            }
        }
        synchronized(bufferLock) {
            val queues = readQueues()
            val queue = queues.optJSONArray(token) ?: return
            var index = 0
            while (index < queue.length()) {
                if (pointKey(queue.getJSONObject(index)) == key) queue.remove(index) else index++
            }
            if (queue.length() == 0) queues.remove(token)
            writeQueues(queues)
        }
    }

    private fun appendBuffer(point: JSONObject) {
        val token = point.optString("token")
        if (token.isBlank()) return
        val key = pointKey(point)
        synchronized(sentKeys) { if (key in sentKeys) return }
        synchronized(bufferLock) {
            val queues = readQueues()
            val queue = queues.optJSONArray(token) ?: JSONArray().also { queues.put(token, it) }
            for (i in 0 until queue.length()) {
                if (pointKey(queue.getJSONObject(i)) == key) return
            }
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
            if (dropped > 0) {
                noteSettled(token, dropped)
                LogManager.add("$name: buffer full, dropped $dropped oldest")
            }
        }
    }

    private fun dropDisabled(allowed: Set<String>): Int {
        synchronized(bufferLock) {
            val queues = readQueues()
            var dropped = 0
            val keys = queues.keys().asSequence().toList()
            keys.forEach { token ->
                if (token in allowed) return@forEach
                val n = queues.optJSONArray(token)?.length() ?: 0
                dropped += n
                if (n > 0) noteSettled(token, n)
                queues.remove(token)
            }
            if (dropped > 0) writeQueues(queues)
            return dropped
        }
    }

    private fun trySend() {
        if (!online()) {
            markBuffered("network")
            scheduleDrain(1000L)
            return
        }
        if (PhoneTrackApi.rateLimited(this)) {
            markBuffered("buffered")
            scheduleDrain(1000L)
            return
        }
        val allowed = enabledTargets().map { it.optString("token") }.filter { it.isNotBlank() }.toSet()
        val dropped = dropDisabled(allowed)
        if (dropped > 0) LogManager.add("Dropped $dropped buffered points for sessions that are off")
        val queued = synchronized(bufferLock) { readQueues() }
        val keys = queued.keys()
        while (keys.hasNext()) {
            val token = keys.next()
            if (token !in allowed) continue
            val queue = queued.optJSONArray(token) ?: continue
            for (i in 0 until queue.length()) {
                dispatch(JSONObject(queue.getJSONObject(i).toString()))
            }
        }
    }

    private fun dispatch(point: JSONObject) {
        val key = pointKey(point)
        if (!inFlight.add(key)) return
        val index = Math.floorMod(laneCursor.getAndIncrement(), lanes.size)
        try {
            lanes[index].execute {
                try {
                    sendOne(point, index)
                } finally {
                    inFlight.remove(key)
                }
            }
        } catch (_: RejectedExecutionException) {
            inFlight.remove(key)
        }
    }

    private fun sendOne(point: JSONObject, lane: Int) {
        if (!online() || PhoneTrackApi.rateLimited(this)) {
            scheduleDrain(1000L)
            return
        }
        when (postSync(clientFor(lane), point)) {
            "ok" -> {
                dropHead(point)
                noteSettled(point.optString("token"), 1)
            }
            else -> scheduleDrain(1000L)
        }
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
            submit { trySend() }
        }, delayMs.coerceAtLeast(0L))
    }

    private fun flushBufferAsync() {
        submit { trySend() }
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
            val manager = getSystemService(NotificationManager::class.java)
            manager.deleteNotificationChannel("location_broadcast_bar")
            val channel = NotificationChannel(
                "location_broadcast_silent",
                "Position broadcast",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setSound(null, null)
            channel.enableVibration(false)
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
            val auto = NotificationChannel(
                "auto_mode_silent",
                "Auto mode",
                NotificationManager.IMPORTANCE_LOW
            )
            auto.setSound(null, null)
            auto.enableVibration(false)
            auto.setShowBadge(false)
            manager.createNotificationChannel(auto)
        }
    }

    private fun createNotification(): Notification {
        val repost = PendingIntent.getBroadcast(
            this,
            2,
            Intent(this, SharingRepost::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val left = SharingControl.remaining(this)
        val text = if (left > 0L) "Sharing for ${SharingControl.remainingLabel(left)}" else "Location sharing is active"
        val built = NotificationCompat.Builder(this, "location_broadcast_silent")
            .setContentTitle("Position broadcast")
            .setContentText(text)
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

    private fun storedMode(): String = prefs.getString("position_mode", "move") ?: "move"

    private fun activeMode(): String {
        val stored = storedMode()
        if (stored != "auto") return stored
        return prefs.getString("auto_effective", "move") ?: "move"
    }

    private fun autoLabel(mode: String): String = when (mode) {
        "significant" -> "low power"
        "walk" -> "walk"
        else -> "move"
    }

    private fun commitAutoMode(next: String, because: String = ""): Boolean {
        if (storedMode() != "auto" || activeMode() == next) return false
        val from = autoModeName(activeMode())
        prefs.edit().putString("auto_effective", next).apply()
        val to = if (because.isBlank()) autoModeName(next) else "${autoModeName(next)}, $because"
        LogManager.modeChange("Auto", from, to)
        notifyAutoSwitch(next)
        DiagLog.noteMode(this)
        return true
    }

    private fun autoModeName(mode: String): String = when (mode) {
        "significant" -> "Low power"
        "walk" -> "Walk"
        else -> "Move"
    }

    private fun notifyAutoSwitch(mode: String) {
        val text = when (mode) {
            "significant" -> "Switched to Low power"
            "walk" -> "Switched to Walk"
            else -> "Switched to Move"
        }
        val open = PendingIntent.getActivity(
            this,
            8,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notice = NotificationCompat.Builder(this, "auto_mode_silent")
            .setContentTitle("Auto")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_broadcast)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        getSystemService(NotificationManager::class.java).notify(8, notice)
    }

    private fun batteryForcesLowPower(): Boolean {
        val saver = getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        if (saver) return true
        val pct = getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: return false
        return pct in 0..4
    }

    private fun noteAutoCandidate(candidate: String) {
        if (storedMode() != "auto") return
        if (batteryForcesLowPower()) {
            pendingAuto = null
            mainHandler.removeCallbacks(applyAuto)
            if (commitAutoMode("significant", "battery")) startLocationUpdates()
            return
        }
        if (candidate == activeMode()) {
            pendingAuto = null
            mainHandler.removeCallbacks(applyAuto)
            return
        }
        if (autoWaitMs(activeMode(), candidate) == 0L) {
            pendingAuto = null
            mainHandler.removeCallbacks(applyAuto)
            if (commitAutoMode(candidate)) startLocationUpdates()
            return
        }
        if (pendingAuto == candidate) return
        pendingAuto = candidate
        mainHandler.removeCallbacks(applyAuto)
        mainHandler.postDelayed(applyAuto, 60_000L)
    }

    private fun autoWaitMs(from: String, to: String): Long {
        if (from == "significant" && to == "walk") return 0L
        if (from == "walk" && to == "move") return 0L
        return 60_000L
    }

    private fun ensureAutoWatch() {
        if (autoWatching) return
        autoWatching = true
        val filter = IntentFilter(ACTION_ACTIVITY)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(activityReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(activityReceiver, filter)
        }
        val battery = IntentFilter().apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_BATTERY_LOW)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(batteryReceiver, battery, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(batteryReceiver, battery)
        }
        mainHandler.postDelayed(batteryPoll, 60_000L)
        if (Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) {
            LogManager.add("Auto: activity permission not granted, staying on ${autoLabel(activeMode())}")
            syncAutoPermissionNotice(this)
            return
        }
        syncAutoPermissionNotice(this)
        val pending = PendingIntent.getBroadcast(
            this,
            7,
            Intent(ACTION_ACTIVITY).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        activityIntent = pending
        ActivityRecognition.getClient(this).requestActivityUpdates(30_000L, pending)
    }

    private val batteryPoll: Runnable = object : Runnable {
        override fun run() {
            if (!autoWatching || storedMode() != "auto") return
            if (batteryForcesLowPower()) {
                pendingAuto = null
                mainHandler.removeCallbacks(applyAuto)
                if (commitAutoMode("significant", "battery")) startLocationUpdates()
            }
            mainHandler.postDelayed(this, 60_000L)
        }
    }

    private fun stopAutoWatch() {
        if (!autoWatching) return
        autoWatching = false
        pendingAuto = null
        mainHandler.removeCallbacks(applyAuto)
        mainHandler.removeCallbacks(batteryPoll)
        try {
            unregisterReceiver(activityReceiver)
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {
        }
        activityIntent?.let { pending ->
            ActivityRecognition.getClient(this).removeActivityUpdates(pending)
            pending.cancel()
        }
        activityIntent = null
        syncAutoPermissionNotice(this)
    }

    override fun onDestroy() {
        stopAutoWatch()
        stopLowPower()
        mainHandler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        lanes.forEach { it.shutdownNow() }
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
        const val ACTION_ACTIVITY_PERMISSION = "com.example.ncphonetrack.ACTIVITY_PERMISSION"
        private const val ACTION_ACTIVITY = "com.example.ncphonetrack.ACTIVITY"
        private const val AUTO_PERMISSION_NOTICE = 9

        fun syncAutoPermissionNotice(context: Context) {
            val prefs = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
            val auto = (prefs.getString("position_mode", "move") ?: "move") == "auto"
            val missing = Build.VERSION.SDK_INT >= 29 &&
                context.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!auto || !missing) {
                manager.cancel(AUTO_PERMISSION_NOTICE)
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    "auto_permission",
                    "Auto permission",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                channel.setShowBadge(false)
                manager.createNotificationChannel(channel)
            }
            val open = PendingIntent.getActivity(
                context,
                9,
                Intent(context, MainActivity::class.java).apply {
                    action = ACTION_ACTIVITY_PERMISSION
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notice = NotificationCompat.Builder(context, "auto_permission")
                .setSmallIcon(R.drawable.ic_stat_broadcast)
                .setContentTitle("Auto will not work")
                .setContentText("Physical activity permission is not available")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "Physical activity permission is not available. Auto will not work until it is granted in Settings."
                    )
                )
                .setContentIntent(open)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .build()
            manager.notify(AUTO_PERMISSION_NOTICE, notice)
        }
        private const val MAX_BUFFER = 7200

        fun defaultPostedName(context: Context): String {
            val prefs = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
            val user = prefs.getString("nc_user", "")?.trim().orEmpty()
            val model = android.os.Build.MODEL?.trim().orEmpty().ifBlank { "Android" }
            return if (user.isBlank()) model else "$user-$model"
        }

        private val unsent = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
        private val unsentSeeded = java.util.concurrent.atomic.AtomicBoolean(false)

        fun ensureSeeded(context: Context) {
            if (unsentSeeded.get()) return
            synchronized(unsentSeeded) {
                if (unsentSeeded.get()) return
                pendingCounts(context).forEach { (token, count) ->
                    unsent[token] = java.util.concurrent.atomic.AtomicInteger(count)
                }
                unsentSeeded.set(true)
            }
        }

        fun noteAccepted(context: Context, token: String) {
            if (token.isBlank()) return
            ensureSeeded(context)
            unsent.computeIfAbsent(token) { java.util.concurrent.atomic.AtomicInteger(0) }.incrementAndGet()
        }

        fun noteSettled(token: String, count: Int) {
            if (token.isBlank() || count <= 0) return
            unsent[token]?.updateAndGet { (it - count).coerceAtLeast(0) }
        }

        fun unsent(context: Context, token: String): Int {
            if (token.isBlank()) return 0
            ensureSeeded(context)
            return unsent[token]?.get() ?: 0
        }

        private fun pendingCounts(context: Context): Map<String, Int> {
            val file = File(context.filesDir, "pending_points.json")
            val text = file.takeIf { it.exists() }?.readText().orEmpty().trim()
            if (text.isBlank()) return emptyMap()
            return try {
                val counts = mutableMapOf<String, Int>()
                if (text.startsWith("[")) {
                    val arr = org.json.JSONArray(text)
                    for (i in 0 until arr.length()) {
                        val token = arr.getJSONObject(i).optString("token")
                        if (token.isNotBlank()) counts[token] = (counts[token] ?: 0) + 1
                    }
                } else {
                    val queues = org.json.JSONObject(text)
                    val keys = queues.keys()
                    while (keys.hasNext()) {
                        val token = keys.next()
                        counts[token] = queues.optJSONArray(token)?.length() ?: 0
                    }
                }
                counts
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }
}
