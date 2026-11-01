package com.example.ncphonetrack

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.util.Timer
import java.util.TimerTask

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var etUrl: EditText
    private lateinit var etToken: EditText
    private lateinit var etDevice: EditText
    private lateinit var etPublish: EditText
    private lateinit var etFetch: EditText
    private lateinit var tvStatus: TextView
    private lateinit var prefs: SharedPreferences
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var formPanel: LinearLayout
    private lateinit var statusPanel: LinearLayout
    private lateinit var tvStatusUrl: TextView
    private lateinit var tvStatusToken: TextView
    private lateinit var tvLoggingState: TextView
    private lateinit var tvLastUpdate: TextView
    private lateinit var tvBuffer: TextView
    private lateinit var tvLog: TextView
    private val LOCATION_PERMISSION_REQUEST = 1001
    private val BACKGROUND_PERMISSION_REQUEST = 1002
    private var logTimer: Timer? = null
    private var fetchTimer: Timer? = null
    private var autoCenter = true
    private var fitAll = false
    private var ownMarkerIcon: BitmapDrawable? = null
    private var friendMarkerIcon: BitmapDrawable? = null

    private fun readVersion(): String = try {
        assets.open("VERSION").bufferedReader().use { it.readText().trim() }
    } catch (e: Exception) { "unknown" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rev = readVersion()
        Configuration.getInstance().userAgentValue = "NextcloudPhoneTrack/$rev (cyberchilli LLC)"
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        drawerLayout = findViewById(R.id.drawer_layout)
        formPanel = findViewById(R.id.form_panel)
        statusPanel = findViewById(R.id.status_panel)
        etUrl = findViewById(R.id.etUrl)
        etToken = findViewById(R.id.etToken)
        etDevice = findViewById(R.id.etDevice)
        etPublish = findViewById(R.id.etPublishInterval)
        etFetch = findViewById(R.id.etFetchInterval)
        tvStatus = findViewById(R.id.tvStatus)
        tvStatusUrl = findViewById(R.id.tvStatusUrl)
        tvStatusToken = findViewById(R.id.tvStatusToken)
        tvLoggingState = findViewById(R.id.tvLoggingState)
        tvLastUpdate = findViewById(R.id.tvLastUpdate)
        tvBuffer = findViewById(R.id.tvBuffer)
        tvLog = findViewById(R.id.tvLog)
        val tvCertStatus = findViewById<TextView>(R.id.tvCertStatus)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnCert = findViewById<Button>(R.id.btnCert)
        val btnForgetCert = findViewById<Button>(R.id.btnForgetCert)
        val btnMenu = findViewById<Button>(R.id.btnMenu)
        val navView: NavigationView = findViewById(R.id.nav_view)

        etUrl.setText(prefs.getString("url", "https://"))
        etToken.setText(prefs.getString("token", ""))
        etDevice.setText(prefs.getString("device", android.os.Build.MODEL))
        etPublish.setText(prefs.getInt("publish_interval_sec", 60).toString())
        etFetch.setText(prefs.getInt("fetch_interval_sec", 30).toString())
        refreshCertStatus(tvCertStatus)

        btnCert.setOnClickListener {
            val url = etUrl.text.toString().trim().removeSuffix("/")
            if (!isValidBaseUrl(url)) {
                Toast.makeText(this, "Enter the Nextcloud URL first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            tvCertStatus.text = "Checking certificate…"
            CertHelper.inspect(url) { probe ->
                runOnUiThread { showCertProbe(probe, tvCertStatus) }
            }
        }
        btnForgetCert.setOnClickListener {
            CertHelper.clearAccepted(prefs)
            refreshCertStatus(tvCertStatus)
            Toast.makeText(this, "Forgot the accepted certificate", Toast.LENGTH_SHORT).show()
        }

        map = findViewById(R.id.map)
        val httpsMapnik = object : OnlineTileSourceBase("MapnikHTTPS", 0, 19, 256, ".png", arrayOf("https://tile.openstreetmap.org/")) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                return baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex) + "/" + MapTileIndex.getY(pMapTileIndex) + imageFilenameEnding()
            }
        }
        map.setTileSource(httpsMapnik)
        map.setMultiTouchControls(true)
        map.setBuiltInZoomControls(true)
        map.controller.setZoom(12.0)
        map.controller.setCenter(GeoPoint(42.7654, -71.4676))

        map.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_DOWN) {
                autoCenter = false
                fitAll = false
            }
            false
        }

        btnMenu.setOnClickListener {
            drawerLayout.openDrawer(navView)
        }

        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_configure -> {
                    formPanel.visibility = if (formPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    statusPanel.visibility = View.GONE
                    drawerLayout.closeDrawers()
                }
                R.id.nav_status -> {
                    showStatusPanel()
                    drawerLayout.closeDrawers()
                }
                R.id.nav_sessions -> {
                    showSessions()
                    drawerLayout.closeDrawers()
                }
                R.id.nav_about -> {
                    val version = readVersion()
                    AlertDialog.Builder(this)
                        .setTitle("About Nextcloud PhoneTrack")
                        .setMessage("cyberchilli LLC\nRevision $version\nLocation sharing via Nextcloud PhoneTrack")
                        .setPositiveButton("OK", null)
                        .show()
                    drawerLayout.closeDrawers()
                }
                R.id.nav_map -> {
                    formPanel.visibility = View.GONE
                    statusPanel.visibility = View.GONE
                    stopLogTimer()
                    drawerLayout.closeDrawers()
                }
            }
            true
        }

        btnSave.setOnClickListener {
            val url = etUrl.text.toString().trim().removeSuffix("/")
            val token = etToken.text.toString().trim()
            val device = etDevice.text.toString().trim().ifEmpty { "Android" }
            if (!isValidBaseUrl(url)) {
                Toast.makeText(this, "Enter a full URL starting with http:// or https://", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (token.isBlank()) {
                Toast.makeText(this, "Session token is required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val publishSec = parseInterval(etPublish.text.toString(), 60)
            val fetchSec = parseInterval(etFetch.text.toString(), 30)
            if (publishSec == null || fetchSec == null) {
                Toast.makeText(this, "Intervals must be numbers from 5 to 3600 seconds", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            prefs.edit()
                .putString("url", url)
                .putString("token", token)
                .putString("device", device)
                .putInt("publish_interval_sec", publishSec)
                .putInt("fetch_interval_sec", fetchSec)
                .apply()
            saveSession(url, token, device)
            formPanel.visibility = View.GONE
            LogManager.add("Configured URL=$url token=$token device=$device publish=${publishSec}s fetch=${fetchSec}s")
            startIfPermittedOrAsk()
        }

        val savedUrl = prefs.getString("url", null)
        val savedToken = prefs.getString("token", null)
        if (!savedUrl.isNullOrBlank() && !savedToken.isNullOrBlank() && isValidBaseUrl(savedUrl) && checkPermissions()) {
            startTrackingFromPrefs()
            updateStatusText("Auto-started tracking")
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), LOCATION_PERMISSION_REQUEST)
            }
            promptBackgroundOnce()
        }
    }

    private fun refreshCertStatus(label: TextView) {
        val hash = CertHelper.getAcceptedHash(prefs)
        label.text = if (hash.isNullOrBlank()) {
            "Certificate: Android trust store"
        } else {
            "Accepted certificate:\n$hash"
        }
    }

    private fun showCertProbe(probe: CertProbe, label: TextView) {
        refreshCertStatus(label)
        val cert = probe.cert
        when (probe.trust) {
            CertTrust.TRUSTED -> {
                AlertDialog.Builder(this)
                    .setTitle("Certificate already trusted")
                    .setMessage(certMessage(cert, "Android already trusts this server. You do not need to accept it."))
                    .setPositiveButton("OK", null)
                    .show()
            }
            CertTrust.UNTRUSTED -> {
                if (cert == null) {
                    Toast.makeText(this, "Could not read the server certificate", Toast.LENGTH_LONG).show()
                    return
                }
                AlertDialog.Builder(this)
                    .setTitle("Accept self-signed certificate?")
                    .setMessage(certMessage(cert, "Android does not trust this certificate. Accept it only if the fingerprint matches your Nextcloud server. Later requests will trust this certificate only."))
                    .setPositiveButton("Accept") { _, _ ->
                        CertHelper.setAcceptedHash(prefs, cert.hash)
                        refreshCertStatus(label)
                        Toast.makeText(this, "Certificate accepted", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            CertTrust.FAILED -> {
                Toast.makeText(this, probe.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun certMessage(cert: ServerCert?, intro: String): String {
        if (cert == null) return intro
        return "$intro\n\nSubject: ${cert.subject}\nIssuer: ${cert.issuer}\nSHA-256:\n${cert.hash}"
    }

    private fun isValidBaseUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        return (scheme == "https" || scheme == "http") && !uri.host.isNullOrBlank()
    }

    private fun parseInterval(raw: String, defaultValue: Int): Int? {
        if (raw.isBlank()) return defaultValue
        val n = raw.trim().toIntOrNull() ?: return null
        if (n < 5 || n > 3600) return null
        return n
    }

    private fun markerIcon(color: Int): BitmapDrawable {
        val size = (36 * resources.displayMetrics.density).toInt().coerceAtLeast(36)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        canvas.drawCircle(size / 2f, size / 2f, size / 2.6f, paint)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size / 8f
        canvas.drawCircle(size / 2f, size / 2f, size / 2.6f, paint)
        return BitmapDrawable(resources, bmp)
    }

    private fun showStatusPanel() {
        formPanel.visibility = View.GONE
        statusPanel.visibility = View.VISIBLE
        val url = prefs.getString("url", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        tvStatusUrl.text = "URL: $url"
        tvStatusToken.text = "Token: $token"
        val state = prefs.getString("logging_state", "idle") ?: "idle"
        val result = prefs.getString("last_result", "none") ?: "none"
        tvLoggingState.text = "Logging: $state ($result)"
        val last = prefs.getLong("last_publish_epoch", 0L)
        tvLastUpdate.text = if (last <= 0L) {
            "Last update: never"
        } else {
            val age = (System.currentTimeMillis() - last) / 1000
            "Last update: ${age}s ago"
        }
        tvBuffer.text = "Buffered points: ${prefs.getInt("buffer_count", 0)}"
        refreshLog()
        startLogTimer()
    }

    private fun refreshLog() {
        tvLog.text = LogManager.getLogs().ifEmpty { "No messages yet" }
    }

    private fun startLogTimer() {
        stopLogTimer()
        logTimer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    runOnUiThread { refreshLog() }
                }
            }, 0, 2000)
        }
    }

    private fun stopLogTimer() {
        logTimer?.cancel()
        logTimer = null
    }

    private fun updateStatusText(msg: String) {
        tvStatus.text = msg
        LogManager.add(msg)
    }

    private fun saveSession(url: String, token: String, device: String) {
        val sessions = loadSessions().filterNot {
            it.optString("url") == url && it.optString("token") == token && it.optString("device") == device
        }.toMutableList()
        val obj = JSONObject().apply {
            put("url", url)
            put("token", token)
            put("device", device)
        }
        sessions.add(0, obj)
        prefs.edit().putString("sessions", JSONArray(sessions).toString()).apply()
    }

    private fun loadSessions(): List<JSONObject> {
        val raw = prefs.getString("sessions", "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun showSessions() {
        val sessions = loadSessions()
        val items = if (sessions.isEmpty()) arrayOf("No sessions yet") else sessions.map { "${it.getString("device")} @ ${it.getString("url")}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Session Configs")
            .setItems(items) { _, which ->
                if (sessions.isNotEmpty()) {
                    val s = sessions[which]
                    etUrl.setText(s.getString("url"))
                    etToken.setText(s.getString("token"))
                    etDevice.setText(s.getString("device"))
                    etPublish.setText(prefs.getInt("publish_interval_sec", 60).toString())
                    etFetch.setText(prefs.getInt("fetch_interval_sec", 30).toString())
                    formPanel.visibility = View.VISIBLE
                    statusPanel.visibility = View.GONE
                    Toast.makeText(this, "Opened session config", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Add New") { _, _ ->
                etUrl.setText("https://")
                etToken.setText("")
                etDevice.setText(android.os.Build.MODEL)
                etPublish.setText("60")
                etFetch.setText("30")
                formPanel.visibility = View.VISIBLE
                statusPanel.visibility = View.GONE
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun checkPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startIfPermittedOrAsk() {
        val needed = mutableListOf<String>()
        if (!checkPermissions()) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty() && !checkPermissions()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), LOCATION_PERMISSION_REQUEST)
            return
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), LOCATION_PERMISSION_REQUEST)
        }
        startTrackingFromPrefs()
        promptBackgroundOnce()
    }

    private fun startTrackingFromPrefs() {
        val url = prefs.getString("url", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        val device = prefs.getString("device", "Android") ?: "Android"
        if (!isValidBaseUrl(url) || token.isBlank()) return
        startLocationService(url, token, device)
        startFetchingPositions(url, token, device)
        updateStatusText("Tracking started")
    }

    private fun promptBackgroundOnce() {
        if (Build.VERSION.SDK_INT < 29) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) return
        if (prefs.getBoolean("asked_background", false)) return
        prefs.edit().putBoolean("asked_background", true).apply()
        AlertDialog.Builder(this)
            .setTitle("Background location")
            .setMessage("Allow location all the time so PhoneTrack keeps sharing while the app is in the background.")
            .setPositiveButton("Continue") { _, _ ->
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    BACKGROUND_PERMISSION_REQUEST
                )
            }
            .setNegativeButton("Not now") { _, _ ->
                Toast.makeText(this, "Sharing continues while the app is open. Background updates may stop.", Toast.LENGTH_LONG).show()
            }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (checkPermissions()) {
                startTrackingFromPrefs()
                promptBackgroundOnce()
            } else {
                updateStatusText("Location permission denied")
                Toast.makeText(this, "Location permission is required to share your position.", Toast.LENGTH_LONG).show()
            }
        } else if (requestCode == BACKGROUND_PERMISSION_REQUEST) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                updateStatusText("Background location allowed")
            } else {
                Toast.makeText(this, "Background location denied. Sharing may pause when the app is closed.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startLocationService(url: String, token: String, device: String) {
        val intent = Intent(this, LocationPublishService::class.java)
        intent.putExtra("url", url)
        intent.putExtra("token", token)
        intent.putExtra("device", device)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun startFetchingPositions(url: String, token: String, device: String) {
        fetchTimer?.cancel()
        val seconds = prefs.getInt("fetch_interval_sec", 30).coerceIn(5, 3600)
        fetchTimer = Timer()
        fetchTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                runOnUiThread { updateMapWithPositions(url, token, device) }
            }
        }, 0, seconds * 1000L)
    }

    private fun updateMapWithPositions(url: String, token: String, ownDevice: String) {
        Thread {
            val positions = PhoneTrackApi.getLastPositions(this, url, token)
            runOnUiThread {
                if (positions == null) {
                    updateStatusText("Map update failed")
                    return@runOnUiThread
                }
                map.overlays.clear()
                if (ownMarkerIcon == null) ownMarkerIcon = markerIcon(Color.parseColor("#1565C0"))
                if (friendMarkerIcon == null) friendMarkerIcon = markerIcon(Color.parseColor("#E65100"))
                val points = mutableListOf<GeoPoint>()
                var ownPoint: GeoPoint? = null
                positions.forEach { (name, data) ->
                    val point = GeoPoint(data.lat, data.lon)
                    points.add(point)
                    val marker = Marker(map)
                    marker.position = point
                    val mine = name == ownDevice
                    marker.title = if (mine) "You · $name" else name
                    marker.snippet = "Acc: ${data.accuracy}m | Age: ${(System.currentTimeMillis()/1000 - data.timestamp)}s"
                    marker.icon = if (mine) ownMarkerIcon else friendMarkerIcon
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    map.overlays.add(marker)
                    if (mine) ownPoint = point
                }
                if (autoCenter && ownPoint != null) {
                    map.controller.setCenter(ownPoint)
                } else if (fitAll && points.isNotEmpty()) {
                    val bb = BoundingBox.fromGeoPoints(points)
                    map.zoomToBoundingBox(bb, false)
                }
                map.invalidate()
                updateStatusText("Map updated: ${positions.size} devices")
            }
        }.start()
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause(); stopLogTimer() }
    override fun onDestroy() {
        fetchTimer?.cancel()
        fetchTimer = null
        super.onDestroy()
    }
}
