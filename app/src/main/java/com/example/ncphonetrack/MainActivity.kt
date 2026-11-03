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
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
import java.io.File
import java.util.Timer
import java.util.TimerTask

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var prefs: SharedPreferences
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var statusPanel: LinearLayout
    private lateinit var tvStatusUrl: TextView
    private lateinit var tvStatusToken: TextView
    private lateinit var tvLoggingState: TextView
    private lateinit var tvConnection: TextView
    private lateinit var sessionStatusList: LinearLayout
    private lateinit var tvMapBanner: TextView
    private lateinit var noSessionBanner: TextView
    private lateinit var loginBanner: android.view.View
    private lateinit var tvLastUpdate: TextView
    private lateinit var tvBuffer: TextView
    private lateinit var tvLog: TextView
    private lateinit var logScroll: android.widget.ScrollView
    private var followLogBottom = true
    private val LOCATION_PERMISSION_REQUEST = 1001
    private val BACKGROUND_PERMISSION_REQUEST = 1002
    private var logTimer: Timer? = null
    private var fetchTimer: Timer? = null
    private var mapEpoch = 0
    private var mapFollow = MapFollow.GROUP
    private var trackedUser: String? = null
    private val lastPoints = mutableMapOf<String, GeoPoint>()
    private val lastSeen = mutableMapOf<String, Long>()
    private lateinit var btnZoomIn: TextView
    private lateinit var btnZoomOut: TextView
    private lateinit var btnGroupView: TextView
    private lateinit var btnReticle: ImageButton
    private var allMode = false
    private lateinit var mapControls: View
    private lateinit var btnPositionMode: ImageButton
    private lateinit var modePicker: LinearLayout
    private var pickFollowOnNextMap = false
    private var bubbleToken = 0
    private val zoomOverlays = mutableListOf<LinearLayout>()
    private val iconCache = mutableMapOf<String, BitmapDrawable>()
    private val colorChoices = listOf(
        "#1565C0", "#E65100", "#2E7D32", "#6A1B9A", "#C62828",
        "#00838F", "#F9A825", "#AD1457", "#6D4C41", "#455A64"
    )

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
        statusPanel = findViewById(R.id.status_panel)
        tvStatusUrl = findViewById(R.id.tvStatusUrl)
        tvStatusToken = findViewById(R.id.tvStatusToken)
        tvLoggingState = findViewById(R.id.tvLoggingState)
        tvConnection = findViewById(R.id.tvConnection)
        sessionStatusList = findViewById(R.id.sessionStatusList)
        tvMapBanner = findViewById(R.id.tvMapBanner)
        noSessionBanner = findViewById(R.id.tvNoSessionBanner)
        loginBanner = findViewById(R.id.loginBanner)
        findViewById<Button>(R.id.btnLoginSettings).setOnClickListener { showSettings() }
        tvLastUpdate = findViewById(R.id.tvLastUpdate)
        tvBuffer = findViewById(R.id.tvBuffer)
        tvLog = findViewById(R.id.tvLog)
        logScroll = findViewById(R.id.logScroll)
        findViewById<Button>(R.id.btnExportLog).setOnClickListener { exportLog() }
        logScroll.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                logScroll.post {
                    val child = logScroll.getChildAt(0) ?: return@post
                    followLogBottom = logScroll.scrollY + logScroll.height >= child.height - 24
                }
            }
            false
        }
        val btnMenu = findViewById<ImageButton>(R.id.btnMenu)
        val navView: NavigationView = findViewById(R.id.nav_view)

        correctSavedServerUrl()

        map = findViewById(R.id.map)
        val httpsMapnik = object : OnlineTileSourceBase("MapnikHTTPS", 0, 19, 256, ".png", arrayOf("https://tile.openstreetmap.org/")) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                return baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex) + "/" + MapTileIndex.getY(pMapTileIndex) + imageFilenameEnding()
            }
        }
        map.setTileSource(httpsMapnik)
        map.setMultiTouchControls(true)
        map.setBuiltInZoomControls(false)
        map.controller.setZoom(12.0)
        map.controller.setCenter(GeoPoint(42.7654, -71.4676))
        btnZoomIn = findViewById(R.id.btnZoomIn)
        btnZoomOut = findViewById(R.id.btnZoomOut)
        btnGroupView = findViewById(R.id.btnGroupView)
        btnReticle = findViewById(R.id.btnReticle)
        mapControls = findViewById(R.id.mapControls)
        btnPositionMode = findViewById(R.id.btnPositionMode)
        modePicker = findViewById(R.id.modePicker)
        refreshModeButton()
        btnPositionMode.setOnClickListener {
            if (modePicker.visibility == View.VISIBLE) {
                modePicker.visibility = View.GONE
            } else {
                sendCurrentPosition()
            }
        }
        btnPositionMode.setOnLongClickListener {
            toggleModePicker()
            true
        }
        btnZoomIn.setOnClickListener { map.controller.setZoom(map.zoomLevelDouble + 0.25) }
        btnZoomOut.setOnClickListener { map.controller.setZoom(map.zoomLevelDouble - 0.25) }
        btnGroupView.setOnClickListener { zoomToLastObserved() }
        btnReticle.setOnClickListener { centerOnSelf() }
        mapControls.bringToFront()
        refreshZoomKeys()
        refreshMapBanner()

        var downX = 0f
        var downY = 0f
        var dragged = false
        val longPress = Runnable {
            if (dragged || statusPanel.visibility == View.VISIBLE) return@Runnable
            val geo = map.projection.fromPixels(downX.toInt(), downY.toInt())
            activateSpoofAt(geo.latitude, geo.longitude)
        }
        map.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    dragged = false
                    modePicker.visibility = View.GONE
                    map.removeCallbacks(longPress)
                    map.postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    map.removeCallbacks(longPress)
                    if (statusPanel.visibility != View.VISIBLE) enterManual()
                }
                MotionEvent.ACTION_MOVE -> {
                    val moved = kotlin.math.abs(event.x - downX) > 24 || kotlin.math.abs(event.y - downY) > 24
                    if (moved) {
                        dragged = true
                        map.removeCallbacks(longPress)
                    }
                    if (statusPanel.visibility != View.VISIBLE && (event.pointerCount > 1 || moved)) enterManual()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    map.removeCallbacks(longPress)
                    if (!dragged && event.actionMasked == MotionEvent.ACTION_UP && statusPanel.visibility == View.VISIBLE) showMap()
                }
            }
            false
        }

        btnMenu.setOnClickListener {
            drawerLayout.openDrawer(navView)
        }

        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_status -> {
                    showStatusPanel()
                    drawerLayout.closeDrawers()
                }
                R.id.nav_sessions -> {
                    showSessions()
                    drawerLayout.closeDrawers()
                }
                R.id.nav_friends -> {
                    showFriends()
                    drawerLayout.closeDrawers()
                }
                R.id.nav_location -> {
                    showLocationSettings()
                    drawerLayout.closeDrawers()
                }
                R.id.nav_settings -> {
                    showSettings()
                    drawerLayout.closeDrawers()
                }
                R.id.nav_about -> {
                    val version = readVersion()
                    AlertDialog.Builder(this)
                        .setTitle("About Nextcloud PhoneTrack")
                        .setMessage("cyberchilli LLC\nRevision $version\nLocation sharing via Nextcloud PhoneTrack")
                        .setPositiveButton("OK", null)
                        .create()
                        .also { dialog ->
                            dialog.show()
                            pinZoomControls(dialog)
                        }
                    drawerLayout.closeDrawers()
                }
                R.id.nav_map -> {
                    showMap()
                    drawerLayout.closeDrawers()
                }
            }
            true
        }

        val savedUrl = prefs.getString("url", null)
        val savedToken = prefs.getString("token", null)
        if (!savedUrl.isNullOrBlank() && !savedToken.isNullOrBlank() && isValidBaseUrl(savedUrl) && checkPermissions() && activeSessionEnabled()) {
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

    private fun correctSavedServerUrl() {
        val saved = prefs.getString("url", "") ?: ""
        val parsed = PhoneTrackUrl.parse(saved)
        val sessions = loadSessions()
        val originalSessions = sessions.map { it.toString() }
        val fixedSessions = sessions.map { session ->
            val sessionParsed = PhoneTrackUrl.parse(session.optString("url"))
            val sessionToken = sessionParsed.token
            if (sessionParsed.base.isNotBlank() && sessionParsed.base != session.optString("url")) {
                session.put("url", sessionParsed.base)
                if (session.optString("token").isBlank() && !sessionToken.isNullOrBlank()) {
                    session.put("token", sessionToken)
                }
            }
            session
        }
        val urlChanged = parsed.base.isNotBlank() && parsed.base != saved
        val sessionsChanged = fixedSessions.map { it.toString() } != originalSessions
        if (!urlChanged && !sessionsChanged) return
        val editor = prefs.edit()
        if (urlChanged) {
            editor.putString("url", parsed.base)
            val fromUrl = parsed.token
            if (prefs.getString("token", "").isNullOrBlank() && !fromUrl.isNullOrBlank()) {
                editor.putString("token", fromUrl)
            }
            LogManager.add("Corrected server URL to ${parsed.base}")
        }
        if (sessionsChanged) editor.putString("sessions", JSONArray(fixedSessions).toString())
        editor.apply()
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
                        CertHelper.accept(prefs, cert)
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

    private fun markerIcon(color: Int, diameterDp: Int, alpha: Float): BitmapDrawable {
        val size = (diameterDp * resources.displayMetrics.density).toInt().coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        paint.color = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawCircle(size / 2f, size / 2f, size / 2.6f, paint)
        paint.color = Color.argb(a, 255, 255, 255)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size / 8f
        canvas.drawCircle(size / 2f, size / 2f, size / 2.6f, paint)
        return BitmapDrawable(resources, bmp)
    }

    private fun iconFor(color: Int, diameterDp: Int, alpha: Float): BitmapDrawable {
        val bucket = (alpha * 20).toInt().coerceIn(1, 20)
        val key = "$color:$diameterDp:$bucket"
        return iconCache.getOrPut(key) { markerIcon(color, diameterDp, bucket / 20f) }
    }

    private fun colorKey(token: String, name: String) = "$token|$name"

    private fun loadJsonPref(key: String): JSONObject =
        runCatching { JSONObject(prefs.getString(key, "{}") ?: "{}") }.getOrDefault(JSONObject())

    private fun savedColor(token: String, name: String): String? =
        loadJsonPref("friend_colors").optString(colorKey(token, name)).ifBlank { null }

    private fun personColor(token: String, name: String, ownName: String, others: List<String>, serverColor: String? = null): Int {
        val saved = savedColor(token, name)
        val hex = when {
            !saved.isNullOrBlank() -> saved
            !serverColor.isNullOrBlank() -> serverColor
            name == ownName || name == prefs.getString("nc_user", "")?.trim() -> "#1565C0"
            else -> colorChoices.drop(1)[others.indexOf(name).coerceAtLeast(0) % (colorChoices.size - 1)]
        }
        return parseHex(hex)
    }

    private fun parseHex(hex: String): Int {
        val raw = hex.trim().removePrefix("#")
        return runCatching { Color.parseColor("#$raw") }.getOrDefault(Color.parseColor("#1565C0"))
    }

    private fun savePersonColor(token: String, name: String, hex: String) {
        val colors = loadJsonPref("friend_colors")
        colors.put(colorKey(token, name), if (hex.startsWith("#")) hex else "#$hex")
        prefs.edit().putString("friend_colors", colors.toString()).apply()
    }

    private fun tracksOn(token: String, name: String): Boolean =
        loadJsonPref("device_tracks").optBoolean(colorKey(token, name), false)

    private fun saveTracks(token: String, name: String, on: Boolean) {
        val tracks = loadJsonPref("device_tracks")
        tracks.put(colorKey(token, name), on)
        prefs.edit().putString("device_tracks", tracks.toString()).apply()
        if (!on && ::map.isInitialized) {
            map.overlays.removeAll { marker ->
                val dot = marker as? Marker ?: return@removeAll false
                dot.title == "Track" && (dot.relatedObject as? String)?.equals(name, true) == true
            }
            map.invalidate()
        } else if (on && fetchUrl.isNotBlank()) {
            updateMapWithPositions(fetchUrl, fetchToken, fetchDevice, mapEpoch)
        }
    }

    private fun ageOutMinutes(token: String): Int {
        val session = loadSessions().find { it.optString("token") == token }
        val minutes = session?.optInt("age_out_min", 15) ?: 15
        return minutes.coerceIn(1, 7 * 24 * 60)
    }

    private fun ageAlpha(timestampSec: Long, ageOutMin: Int): Float {
        if (timestampSec <= 0L) return 1f
        val age = System.currentTimeMillis() / 1000.0 - timestampSec
        val window = ageOutMin * 60.0
        return (1.0 - age / window).toFloat().coerceIn(0f, 1f)
    }

    private fun pickColor(onPick: (String) -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val dialog = AlertDialog.Builder(this).setTitle("Color").setView(box).setNegativeButton("Cancel", null).create()
        colorChoices.forEach { hex ->
            box.addView(TextView(this).apply {
                text = " "
                setBackgroundColor(parseHex(hex))
                val h = (36 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h).apply {
                    bottomMargin = (8 * resources.displayMetrics.density).toInt()
                }
                setOnClickListener {
                    dialog.dismiss()
                    onPick(hex)
                }
            })
        }
        dialog.show()
    }

    private fun showMap() {
        statusPanel.visibility = View.GONE
        refreshMapBanner()
        refreshZoomKeys()
    }

    private fun showStatusPanel() {
        statusPanel.visibility = View.VISIBLE
        statusPanel.bringToFront()
        val url = prefs.getString("url", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        tvStatusUrl.text = "URL: $url"
        tvStatusToken.text = "Token: $token"
        val state = prefs.getString("logging_state", "idle") ?: "idle"
        val result = prefs.getString("last_result", "none") ?: "none"
        tvLoggingState.text = "Logging: $state (${resultLabel(result)})"
        val last = prefs.getLong("last_publish_epoch", 0L)
        tvLastUpdate.text = if (last <= 0L) {
            "Last update: never"
        } else {
            val age = (System.currentTimeMillis() - last) / 1000
            "Last update: ${age}s ago"
        }
        tvBuffer.text = "Buffered points: ${prefs.getInt("buffer_count", 0)}"
        refreshConnectionStatus()
        refreshLog()
        startLogTimer()
        refreshZoomKeys()
    }

    private fun resultLabel(result: String): String {
        return when {
            result.isBlank() || result == "none" -> "no position yet"
            else -> result
        }
    }

    private data class LinkState(val readFail: Boolean, val pushFail: Boolean, val pushWaiting: Boolean, val readOk: Boolean)

    private fun linkState(): LinkState {
        val now = System.currentTimeMillis()
        val publishSec = prefs.getInt("publish_interval_sec", 60).coerceIn(5, 3600)
        val fetchSec = prefs.getInt("fetch_interval_sec", 30).coerceIn(5, 3600)
        val result = prefs.getString("last_result", "") ?: ""
        val stateName = prefs.getString("logging_state", "idle") ?: "idle"
        val readResult = prefs.getString("last_read_result", "") ?: ""
        val mapOk = prefs.getLong("last_map_ok", 0L)
        val readFresh = readResult == "ok" && mapOk > 0L && now - mapOk <= fetchSec * 2L * 1000
        val readFail = readResult == "failed" || (readResult == "ok" && !readFresh)
        val pushFresh = (result == "ok" || result == "buffer cleared") &&
            prefs.getLong("last_success_epoch", 0L) > 0L &&
            now - prefs.getLong("last_success_epoch", 0L) <= publishSec * 2L * 1000
        val quiet = stateName == "off"
        val noAttempt = result.isBlank() || result == "none"
        val pushWaiting = !quiet && !pushFresh && noAttempt &&
            stateName != "location off" && stateName != "permission denied" && stateName != "stopped"
        val pushFail = !quiet && !pushFresh && !pushWaiting
        return LinkState(readFail, pushFail, pushWaiting, readFresh)
    }

    private fun connectionFailMessage(link: LinkState): String? = when {
        link.readFail && link.pushFail -> "Connection fail read and push"
        link.readFail -> "Connection fail read"
        link.pushFail -> "Connection fail push"
        else -> null
    }

    private fun refreshConnectionStatus() {
        val link = linkState()
        val message = connectionFailMessage(link)
        val result = prefs.getString("last_result", "") ?: ""
        val state = prefs.getString("logging_state", "idle") ?: "idle"
        val detail = "Logging: $state (${resultLabel(result)})"
        tvLoggingState.text = if (message == null) detail else "$message\n$detail"
        val lastOk = prefs.getLong("last_success_epoch", 0L)
        val ageSec = if (lastOk <= 0L) Long.MAX_VALUE else (System.currentTimeMillis() - lastOk) / 1000
        tvLastUpdate.text = if (lastOk <= 0L) "Last update: never" else "Last update: ${ageSec}s ago"
        tvBuffer.text = "Buffered points: ${prefs.getInt("buffer_count", 0)}"
        refreshSessionStatusList(link)
        refreshMapBanner()
    }

    private fun refreshSessionStatusList(link: LinkState) {
        sessionStatusList.removeAllViews()
        val sessions = loadSessions()
        if (sessions.isEmpty()) {
            sessionStatusList.addView(TextView(this).apply { text = "No sessions" })
            return
        }
        val activeUrl = prefs.getString("url", "") ?: ""
        val activeToken = prefs.getString("token", "") ?: ""
        val message = connectionFailMessage(link)
        sessions.forEach { session ->
            val enabled = session.optBoolean("enabled", true)
            val active = enabled && activeToken.isNotBlank() &&
                session.optString("url") == activeUrl &&
                session.optString("token") == activeToken
            val status: String
            val color: String
            when {
                !enabled || !active -> {
                    status = "Off"
                    color = "#9E9E9E"
                }
                message != null -> {
                    status = message
                    color = "#C62828"
                }
                link.readOk && !link.pushWaiting -> {
                    status = "OK"
                    color = "#1B7F3A"
                }
                else -> {
                    status = "Waiting"
                    color = "#E65100"
                }
            }
            val name = TextView(this).apply {
                text = sessionLabel(session)
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val state = TextView(this).apply {
                text = status
                textSize = 16f
                setTextColor(Color.parseColor(color))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            sessionStatusList.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
                addView(name)
                addView(state)
            })
        }
    }

    private fun refreshMapBanner() {
        val link = linkState()
        val message = connectionFailMessage(link)
        val mapVisible = statusPanel.visibility != View.VISIBLE
        val verified = prefs.getBoolean("credentials_verified", false)
        val noneActive = loadSessions().none { it.optBoolean("enabled", true) }
        tvMapBanner.text = message ?: "Not connected"
        noSessionBanner.visibility = if (noneActive && mapVisible) View.VISIBLE else View.GONE
        tvMapBanner.visibility = if (message != null && !noneActive && mapVisible) View.VISIBLE else View.GONE
        loginBanner.visibility = if (!verified && mapVisible) View.VISIBLE else View.GONE
    }

    private fun exportLog() {
        val text = LogManager.getLogs().ifEmpty { "No messages yet" }
        val dir = File(cacheDir, "logs").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = File(dir, "phonetrack-log-$stamp.txt")
        try {
            file.writeText(text)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "PhoneTrack log")
                putExtra(Intent.EXTRA_TEXT, text)
                clipData = android.content.ClipData.newRawUri("PhoneTrack log", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "Export log")
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not export the log", Toast.LENGTH_LONG).show()
            LogManager.add("Export log failed: ${e.message}")
        }
    }

    private fun refreshLog() {
        tvLog.text = LogManager.getLogs().ifEmpty { "No messages yet" }
        if (followLogBottom) {
            logScroll.post {
                val child = logScroll.getChildAt(0) ?: return@post
                logScroll.scrollTo(0, (child.height - logScroll.height).coerceAtLeast(0))
            }
        }
        refreshMapBanner()
        if (statusPanel.visibility == View.VISIBLE) refreshConnectionStatus()
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
        LogManager.add(msg)
    }

    private fun saveSession(url: String, token: String, device: String) {
        val previous = loadSessions().find { sameSession(it, url, token, device) }
        val sessions = loadSessions().filterNot {
            sameSession(it, url, token, device)
        }.onEach { it.put("enabled", false) }.toMutableList()
        val obj = JSONObject().apply {
            put("url", url)
            put("token", token)
            put("device", device)
            put("enabled", true)
            val name = previous?.optString("name").orEmpty()
            if (name.isNotBlank()) put("name", name)
            val publicToken = previous?.optString("public_token").orEmpty()
            if (publicToken.isNotBlank()) put("public_token", publicToken)
        }
        sessions.add(0, obj)
        prefs.edit().putString("sessions", JSONArray(sessions).toString()).apply()
    }

    private fun loadSessions(): List<JSONObject> {
        val raw = prefs.getString("sessions", "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private var sessionsDialog: AlertDialog? = null
    private var sessionsList: LinearLayout? = null
    private var refreshingNames = false
    private var refreshSettingsPermissions: (() -> Unit)? = null

    private fun showSessions(reloadNames: Boolean = true) {
        val sessions = loadSessions()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val list = sessionsList ?: LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }.also { sessionsList = it }
        list.removeAllViews()
        if (sessions.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No sessions yet. Use New session."
                setPadding(0, pad, 0, pad)
            })
        } else {
            list.addView(TextView(this).apply {
                text = "Tap a session to activate it. Hold to configure or delete."
                textSize = 12f
                setPadding(0, 0, 0, pad / 2)
            })
            sessions.forEach { session ->
                val label = TextView(this).apply {
                    text = sessionLabel(session)
                    textSize = 16f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val toggle = androidx.appcompat.widget.SwitchCompat(this).apply {
                    isChecked = session.optBoolean("enabled", true)
                    contentDescription = "Enabled"
                    setOnCheckedChangeListener { button, checked ->
                        if (button.isPressed) setSessionEnabled(session, checked)
                    }
                }
                list.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, pad / 2, 0, pad / 2)
                    isClickable = true
                    isLongClickable = true
                    setOnClickListener {
                        if (!session.optBoolean("enabled", true)) setSessionEnabled(session, true)
                        else chooseActivationZoom()
                        sessionsDialog?.dismiss()
                        statusPanel.visibility = View.GONE
                        refreshMapBanner()
                    }
                    setOnLongClickListener { anchor ->
                        showSessionHoldMenu(anchor, session)
                        true
                    }
                    label.setOnClickListener {
                        if (!session.optBoolean("enabled", true)) setSessionEnabled(session, true)
                        else chooseActivationZoom()
                        sessionsDialog?.dismiss()
                        statusPanel.visibility = View.GONE
                        refreshMapBanner()
                    }
                    label.setOnLongClickListener { anchor ->
                        showSessionHoldMenu(anchor, session)
                        true
                    }
                    addView(label)
                    addView(toggle)
                })
            }
        }
        if (sessionsDialog?.isShowing != true) {
            val scroll = android.widget.ScrollView(this).apply { addView(list) }
            sessionsDialog = AlertDialog.Builder(this)
                .setTitle("Sessions")
                .setView(scroll)
                .setNeutralButton("New session") { _, _ -> openNewSessionForm() }
                .setPositiveButton("Close", null)
                .create()
            sessionsDialog?.setOnDismissListener {
                sessionsDialog = null
                sessionsList = null
            }
            sessionsDialog?.show()
            sessionsDialog?.let { pinZoomControls(it) }
        }
        if (reloadNames) refreshSessionNames()
    }

    private fun refreshSessionNames() {
        if (refreshingNames) return
        val url = prefs.getString("nc_auth_url", null) ?: return
        val user = prefs.getString("nc_user", "")?.trim().orEmpty()
        val pass = prefs.getString("nc_pass", "").orEmpty()
        if (user.isBlank() || pass.isBlank() || !isValidBaseUrl(url)) return
        refreshingNames = true
        Thread {
            val (remote, error) = PhoneTrackApi.fetchSessionList(this, url, user, pass)
            runOnUiThread {
                refreshingNames = false
                if (error == null) {
                    val before = prefs.getString("sessions", "")
                    mergeRemoteSessions(url, remote)
                    val after = prefs.getString("sessions", "")
                    if (before != after && sessionsDialog?.isShowing == true) showSessions(reloadNames = false)
                }
            }
        }.start()
    }

    private fun showFriends() {
        val sessions = loadSessions()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val indent = (28 * resources.displayMetrics.density).toInt()
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        if (sessions.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No sessions yet."
                setPadding(0, pad, 0, pad)
            })
        } else {
            list.addView(TextView(this).apply {
                text = "Loading users…"
                setPadding(0, pad, 0, pad)
            })
        }
        val scroll = android.widget.ScrollView(this).apply { addView(list) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Friends")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .create()
        dialog.show()
        pinZoomControls(dialog)
        if (sessions.isEmpty()) return
        Thread {
            val rows = sessions.map { session ->
                val title = sessionLabel(session)
                val positions = PhoneTrackApi.getLastPositions(this, session.optString("url"), session.optString("token"))
                val points = positions?.mapValues { (_, data) -> GeoPoint(data.lat, data.lon) }.orEmpty()
                FriendRow(
                    title,
                    session.optString("token"),
                    session.optString("device"),
                    points.keys.sorted(),
                    session.optBoolean("enabled", true),
                    positions != null,
                    points
                )
            }
            runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                list.removeAllViews()
                rows.forEach { row ->
                    val muted = !row.enabled || !row.reachable
                    val sessionColor = if (muted) Color.parseColor("#9E9E9E") else Color.BLACK
                    val userColor = if (muted) Color.parseColor("#757575") else Color.BLACK
                    val title = when {
                        !row.enabled -> "${row.title} (disabled)"
                        !row.reachable -> "${row.title} (unavailable)"
                        else -> row.title
                    }
                    list.addView(TextView(this).apply {
                        text = title
                        textSize = 16f
                        setTextColor(sessionColor)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setPadding(0, pad, 0, pad / 4)
                    })
                    if (row.users.isEmpty()) {
                        list.addView(TextView(this).apply {
                            text = if (row.reachable) "No users reported" else "Users could not be loaded"
                            setTextColor(userColor)
                            setPadding(indent, 0, 0, pad / 2)
                        })
                    } else {
                        val others = row.users.filter { it != row.ownName && it != prefs.getString("nc_user", "")?.trim() }
                        row.users.forEach { user ->
                            val color = personColor(row.token, user, row.ownName, others)
                            val chip = View(this).apply {
                                val d = (22 * resources.displayMetrics.density).toInt()
                                layoutParams = LinearLayout.LayoutParams(d, d).apply {
                                    marginEnd = pad / 2
                                }
                                setBackgroundColor(color)
                                setOnClickListener {
                                    pickColor { hex ->
                                        savePersonColor(row.token, user, hex)
                                        setBackgroundColor(parseHex(hex))
                                    }
                                }
                            }
                            val nameView = TextView(this).apply {
                                text = user
                                textSize = 15f
                                setTextColor(userColor)
                                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                                setOnClickListener {
                                    dialog.dismiss()
                                    statusPanel.visibility = View.GONE
                                    lastPoints.putAll(row.points)
                                    enterUserTrack(user)
                                    refreshMapBanner()
                                }
                            }
                            val track = android.widget.Switch(this).apply {
                                text = "Tracks"
                                isChecked = tracksOn(row.token, user)
                                setOnCheckedChangeListener { _, on -> saveTracks(row.token, user, on) }
                            }
                            list.addView(LinearLayout(this).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = android.view.Gravity.CENTER_VERTICAL
                                setPadding(indent, pad / 4, 0, pad / 4)
                                addView(chip)
                                addView(nameView)
                                addView(track)
                            })
                        }
                    }
                }
            }
        }.start()
    }

    private fun showSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
        }
        val width = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        val urlField = EditText(this).apply {
            hint = "Nextcloud server"
            setText(prefs.getString("nc_auth_url", "") ?: "")
            layoutParams = width
        }
        val userField = EditText(this).apply {
            hint = "Nextcloud username"
            setText(prefs.getString("nc_user", "") ?: "")
            layoutParams = width
        }
        val passField = EditText(this).apply {
            hint = "Nextcloud app password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("nc_pass", "") ?: "")
            layoutParams = width
        }
        box.addView(urlField)
        box.addView(userField)
        box.addView(passField)
        box.addView(TextView(this).apply {
            text = "Required permissions"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, pad, 0, pad / 4)
        })
        val permissionList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = width
        }
        box.addView(permissionList)
        fun showPermissions() {
            permissionList.removeAllViews()
            requiredPermissions().forEach { item ->
                val granted = ContextCompat.checkSelfPermission(this, item.permission) == PackageManager.PERMISSION_GRANTED
                val label = TextView(this).apply {
                    text = item.label
                    textSize = 16f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val status = TextView(this).apply {
                    text = if (granted) "OK" else "not granted"
                    textSize = 16f
                    setTextColor(Color.parseColor(if (granted) "#1B7F3A" else "#C62828"))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(pad / 2, 0, pad / 2, 0)
                }
                val link = TextView(this).apply {
                    text = "Settings"
                    textSize = 16f
                    setTextColor(Color.parseColor("#1565C0"))
                    paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                    setOnClickListener { openPermissionSettings(item.destination) }
                }
                permissionList.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, pad / 3, 0, pad / 3)
                    addView(label)
                    addView(status)
                    addView(link)
                })
            }
        }
        showPermissions()
        refreshSettingsPermissions = { showPermissions() }
        box.addView(TextView(this).apply {
            text = "Accepted self-signed certificates"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, pad, 0, pad / 4)
        })
        val certList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = width
        }
        box.addView(certList)
        fun showAcceptedCerts() {
            certList.removeAllViews()
            val certs = CertHelper.acceptedCerts(prefs)
            if (certs.isEmpty()) {
                certList.addView(TextView(this).apply { text = "None" })
                return
            }
            certs.forEach { cert ->
                val label = TextView(this).apply {
                    val who = cert.subject.ifBlank { "Saved certificate" }
                    text = "$who\n${cert.hash}"
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val remove = Button(this).apply {
                    text = "Remove"
                    setOnClickListener {
                        CertHelper.forget(prefs, cert.hash)
                        prefs.edit().putBoolean("credentials_verified", false).apply()
                        showAcceptedCerts()
                        refreshMapBanner()
                        Toast.makeText(this@MainActivity, "Certificate removed", Toast.LENGTH_SHORT).show()
                    }
                }
                certList.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(label)
                    addView(remove)
                })
            }
        }
        showAcceptedCerts()
        val scroll = android.widget.ScrollView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(box)
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
            .setTitle("Settings")
            .setMessage("Nextcloud login used to load sessions and map positions.")
            .setView(scroll)
            .setPositiveButton("Save", null)
            .setNeutralButton("Clear") { _, _ ->
                prefs.edit().remove("nc_auth_url").remove("nc_user").remove("nc_pass").putBoolean("credentials_verified", false).commit()
                Toast.makeText(this, "Login cleared", Toast.LENGTH_SHORT).show()
                refreshMapBanner()
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = PhoneTrackUrl.loginBase(urlField.text.toString())
                val user = userField.text.toString().trim()
                val pass = passField.text.toString()
                if (!isValidBaseUrl(url) || user.isBlank() || pass.isBlank()) {
                    Toast.makeText(this, "Server, username, and app password are required", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                prefs.edit()
                    .putString("nc_auth_url", url)
                    .putString("nc_user", user)
                    .putString("nc_pass", pass)
                    .commit()
                dialog.dismiss()
                Toast.makeText(this, "Checking login…", Toast.LENGTH_SHORT).show()
                refreshMapBanner()
                verifyLogin(url, user, pass)
            }
        }
        dialog.setOnDismissListener { refreshSettingsPermissions = null }
        dialog.show()
        pinZoomControls(dialog)
    }

    private data class RequiredPermission(val label: String, val permission: String, val destination: String)

    private fun requiredPermissions(): List<RequiredPermission> {
        val items = mutableListOf(
            RequiredPermission("Precise location", Manifest.permission.ACCESS_FINE_LOCATION, "app"),
            RequiredPermission("Approximate location", Manifest.permission.ACCESS_COARSE_LOCATION, "app")
        )
        if (Build.VERSION.SDK_INT >= 29) {
            items.add(RequiredPermission("Background location", Manifest.permission.ACCESS_BACKGROUND_LOCATION, "app"))
        }
        if (Build.VERSION.SDK_INT >= 33) {
            items.add(RequiredPermission("Notifications", Manifest.permission.POST_NOTIFICATIONS, "notifications"))
        }
        return items
    }

    private fun openPermissionSettings(destination: String) {
        val intent = if (destination == "notifications" && Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open settings", Toast.LENGTH_LONG).show()
        }
    }

    private fun verifyLogin(url: String, user: String, pass: String) {
        Thread {
            val (_, error) = PhoneTrackApi.fetchSessionList(this, url, user, pass)
            runOnUiThread {
                if (error == null) {
                    prefs.edit().putBoolean("credentials_verified", true).commit()
                    Toast.makeText(this, "Login verified", Toast.LENGTH_SHORT).show()
                    refreshMapBanner()
                    return@runOnUiThread
                }
                if (CertHelper.looksLikeTlsFailure(error)) {
                    CertHelper.inspect(url) { probe ->
                        runOnUiThread {
                            val cert = probe.cert
                            if (probe.trust == CertTrust.UNTRUSTED && cert != null) {
                                promptAcceptCert(cert) { verifyLogin(url, user, pass) }
                            } else {
                                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                            }
                            refreshMapBanner()
                        }
                    }
                } else {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    refreshMapBanner()
                }
            }
        }.start()
    }

    private fun promptAcceptCert(cert: ServerCert, onAccepted: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Accept self-signed certificate?")
            .setMessage(certMessage(cert, "Login cannot continue until you accept this certificate. Accept it only if the fingerprint matches your Nextcloud server."))
            .setPositiveButton("Accept") { _, _ ->
                CertHelper.accept(prefs, cert)
                Toast.makeText(this, "Certificate accepted", Toast.LENGTH_SHORT).show()
                onAccepted()
            }
            .setNegativeButton("Cancel") { _, _ -> refreshMapBanner() }
            .show()
    }

    private fun promptLoadSessions() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
        }
        val width = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        val savedUrl = prefs.getString("nc_auth_url", null)
            ?: prefs.getString("url", null)
            ?: loadSessions().firstOrNull()?.optString("url")
            ?: "https://"
        val urlField = EditText(this).apply {
            hint = "Nextcloud server"
            setText(savedUrl)
            layoutParams = width
        }
        val userField = EditText(this).apply {
            hint = "Nextcloud username"
            setText(prefs.getString("nc_user", ""))
            layoutParams = width
        }
        val passField = EditText(this).apply {
            hint = "Nextcloud app password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("nc_pass", ""))
            layoutParams = width
        }
        box.addView(urlField)
        box.addView(userField)
        box.addView(passField)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Load sessions from server")
            .setMessage("PhoneTrack lists only sessions owned by or shared with this Nextcloud user. Use an app password.")
            .setView(box)
            .setPositiveButton("Load", null)
            .setNegativeButton("Cancel") { _, _ -> showSessions() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = PhoneTrackUrl.loginBase(urlField.text.toString())
                val user = userField.text.toString().trim()
                val pass = passField.text.toString()
                if (!isValidBaseUrl(url) || user.isBlank() || pass.isBlank()) {
                    Toast.makeText(this, "Server, username, and app password are required", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                prefs.edit().putString("nc_auth_url", url).putString("nc_user", user).putString("nc_pass", pass).commit()
                dialog.dismiss()
                Toast.makeText(this, "Loading sessions…", Toast.LENGTH_SHORT).show()
                Thread {
                    val (remote, error) = PhoneTrackApi.fetchSessionList(this, url, user, pass)
                    runOnUiThread {
                        if (error != null && CertHelper.looksLikeTlsFailure(error)) {
                            CertHelper.inspect(url) { probe ->
                                runOnUiThread {
                                    val cert = probe.cert
                                    if (probe.trust == CertTrust.UNTRUSTED && cert != null) {
                                        promptAcceptCert(cert) {
                                            Thread {
                                                val (remote, retryError) = PhoneTrackApi.fetchSessionList(this, url, user, pass)
                                                runOnUiThread {
                                                    if (retryError != null) {
                                                        Toast.makeText(this, retryError, Toast.LENGTH_LONG).show()
                                                    } else {
                                                        val added = mergeRemoteSessions(url, remote)
                                                        Toast.makeText(this, "Server has ${remote.size} sessions. Added $added.", Toast.LENGTH_LONG).show()
                                                    }
                                                    showSessions()
                                                    refreshMapBanner()
                                                }
                                            }.start()
                                        }
                                    } else {
                                        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                                        showSessions()
                                    }
                                }
                            }
                            return@runOnUiThread
                        }
                        if (error != null) {
                            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                        } else {
                            val added = mergeRemoteSessions(url, remote)
                            Toast.makeText(this, "Server has ${remote.size} sessions. Added $added.", Toast.LENGTH_LONG).show()
                        }
                        showSessions()
                        refreshMapBanner()
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun mergeRemoteSessions(baseUrl: String, remote: List<RemoteSession>): Int {
        val sessions = loadSessions().toMutableList()
        var added = 0
        remote.forEach { item ->
            val existing = sessions.find { it.optString("token") == item.token }
            if (existing == null) {
                sessions.add(JSONObject().apply {
                    put("name", item.name)
                    put("url", baseUrl)
                    put("token", item.token)
                    put("public_token", item.publicToken)
                    put("device", android.os.Build.MODEL)
                    put("enabled", false)
                })
                added += 1
            } else {
                if (item.name.isNotBlank()) existing.put("name", item.name)
                if (item.publicToken.isNotBlank()) existing.put("public_token", item.publicToken)
                if (existing.optString("url").isBlank()) existing.put("url", baseUrl)
            }
        }
        prefs.edit().putString("sessions", JSONArray(sessions).toString()).apply()
        return added
    }

    private fun sessionLabel(session: JSONObject): String {
        val name = session.optString("name")
        if (name.isNotBlank()) return name
        return "Unnamed session"
    }

    private fun sameSession(a: JSONObject, url: String, token: String, device: String): Boolean {
        return a.optString("url") == url && a.optString("token") == token && a.optString("device") == device
    }

    private fun activeSessionEnabled(): Boolean {
        val url = prefs.getString("url", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        val device = prefs.getString("device", "") ?: ""
        val match = loadSessions().find { sameSession(it, url, token, device) } ?: return true
        return match.optBoolean("enabled", true)
    }

    private fun isActiveSession(session: JSONObject): Boolean {
        return prefs.getString("url", "") == session.optString("url") &&
            prefs.getString("token", "") == session.optString("token") &&
            (prefs.getString("device", "") ?: "") == session.optString("device")
    }

    private fun setSessionEnabled(session: JSONObject, enabled: Boolean) {
        val url = session.optString("url")
        val token = session.optString("token")
        val device = session.optString("device")
        val sessions = loadSessions()
        sessions.forEach { item ->
            if (sameSession(item, url, token, device)) item.put("enabled", enabled)
            else if (enabled) item.put("enabled", false)
        }
        prefs.edit().putString("sessions", JSONArray(sessions).toString()).apply()
        if (enabled) {
            prefs.edit()
                .putString("url", url)
                .putString("token", token)
                .putString("device", device)
                .remove("last_result")
                .remove("last_success_epoch")
                .remove("last_publish_epoch")
                .remove("last_read_result")
                .remove("last_map_ok")
                .apply()
            armActivationZoom()
            startIfPermittedOrAsk()
        } else if (isActiveSession(session)) {
            stopTracking()
        }
        if (sessionsDialog?.isShowing == true) syncSessionSwitches() else showSessions(reloadNames = false)
        refreshMapBanner()
    }

    private fun syncSessionSwitches() {
        val list = sessionsList ?: return
        val sessions = loadSessions()
        var index = 0
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i) as? LinearLayout ?: continue
            val toggle = row.getChildAt(1) as? android.widget.CompoundButton ?: continue
            val enabled = sessions.getOrNull(index)?.optBoolean("enabled", true) ?: continue
            index += 1
            if (toggle.isChecked != enabled) toggle.isChecked = enabled
        }
    }

    private fun stopTracking() {
        mapEpoch += 1
        fetchTimer?.cancel()
        fetchTimer = null
        stopService(Intent(this, LocationPublishService::class.java))
        prefs.edit().remove("url").remove("token").apply()
        clearMap()
        updateStatusText("Tracking stopped")
        refreshMapBanner()
    }

    private fun clearMap() {
        if (!::map.isInitialized) return
        map.overlays.clear()
        map.invalidate()
    }

    private fun showSessionHoldMenu(anchor: View, session: JSONObject) {
        PopupMenu(this, anchor).apply {
            menu.add("Configure")
            menu.add("Delete")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Configure" -> {
                        sessionsDialog?.dismiss()
                        openNewSessionForm(session)
                    }
                    "Delete" -> confirmDeleteSession(session)
                }
                true
            }
            show()
        }
    }

    private fun openNewSessionForm(existing: JSONObject? = null) {
        val server = prefs.getString("nc_auth_url", "")?.trim().orEmpty()
        val user = prefs.getString("nc_user", "")?.trim().orEmpty()
        val pass = prefs.getString("nc_pass", "").orEmpty()
        if (!isValidBaseUrl(server) || user.isBlank() || pass.isBlank()) {
            Toast.makeText(this, "Log in from Settings before creating a session", Toast.LENGTH_LONG).show()
            showSettings()
            return
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val width = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val nameField = EditText(this).apply {
            hint = "Session name"
            setText(existing?.optString("name").orEmpty())
            layoutParams = width
        }
        val searchField = EditText(this).apply {
            hint = "Search friends"
            layoutParams = width
        }
        val resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val selectedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val selected = mutableListOf<String>()
        var directory = emptyList<Pair<String, String>>()
        fun renderSelected() {
            selectedBox.removeAllViews()
            selected.forEach { id ->
                selectedBox.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = id
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(Button(this@MainActivity).apply {
                        text = "Remove"
                        setOnClickListener {
                            selected.remove(id)
                            renderSelected()
                        }
                    })
                })
            }
        }
        fun renderResults() {
            resultsBox.removeAllViews()
            val query = searchField.text.toString().trim().lowercase()
            if (query.isEmpty()) return
            val matches = directory.filter { (id, label) ->
                id !in selected && (id.lowercase().contains(query) || label.lowercase().contains(query))
            }.take(8)
            if (matches.isEmpty()) {
                resultsBox.addView(TextView(this).apply { text = "No matching users" })
                return
            }
            matches.forEach { (id, label) ->
                resultsBox.addView(TextView(this).apply {
                    text = if (label.isBlank() || label == id) id else "$label ($id)"
                    textSize = 16f
                    setPadding(0, pad / 3, 0, pad / 3)
                    setOnClickListener {
                        selected.add(id)
                        searchField.setText("")
                        renderSelected()
                        renderResults()
                    }
                })
            }
        }
        searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { renderResults() }
        })
        val publicSwitch = android.widget.Switch(this).apply {
            text = "Public"
            layoutParams = width
        }
        val publicUrl = TextView(this).apply {
            text = "The public URL is available after the session is created."
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val publicAccordion = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(TextView(this@MainActivity).apply {
                text = "Public URL"
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(publicUrl)
        }
        publicSwitch.setOnCheckedChangeListener { _, checked ->
            publicAccordion.visibility = if (checked) View.VISIBLE else View.GONE
        }
        val serverSwitch = android.widget.Switch(this).apply {
            text = "Enabled on server"
            isChecked = true
            layoutParams = width
        }
        val purgeValues = listOf("no", "day", "week", "month")
        val purge = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Don't purge", "Daily", "Weekly", "Monthly")
            )
            layoutParams = width
        }
        val devicesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val ageField = EditText(this).apply {
            hint = "Position age-out minutes"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText((existing?.optInt("age_out_min", 15) ?: 15).toString())
            layoutParams = width
        }
        box.addView(nameField)
        val sessionToken = existing?.optString("token").orEmpty()
        if (sessionToken.isNotBlank()) {
            box.addView(TextView(this).apply {
                text = "Token"
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, pad, 0, pad / 4)
            })
            box.addView(TextView(this).apply {
                text = sessionToken
                textSize = 14f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(0, 0, 0, pad / 2)
            })
        }
        box.addView(TextView(this).apply {
            text = "Friends"
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, pad, 0, pad / 4)
        })
        box.addView(searchField)
        box.addView(resultsBox)
        box.addView(selectedBox)
        box.addView(publicSwitch)
        box.addView(publicAccordion)
        box.addView(serverSwitch)
        box.addView(TextView(this).apply {
            text = "Auto purge"
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, pad, 0, pad / 4)
        })
        box.addView(purge)
        box.addView(TextView(this).apply {
            text = "Devices"
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, pad, 0, pad / 4)
        })
        box.addView(devicesBox)
        box.addView(TextView(this).apply {
            text = "Local view"
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, pad, 0, pad / 4)
        })
        box.addView(TextView(this).apply {
            text = "Age out"
            setPadding(0, pad / 2, 0, 0)
        })
        box.addView(ageField)
        var sessionId = existing?.optInt("id", 0) ?: 0
        var publicToken = existing?.optString("public_token").orEmpty()
        val creating = existing == null
        val scroll = android.widget.ScrollView(this).apply { addView(box) }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
            .setTitle(if (creating) "New session" else "Session")
            .setView(scroll)
            .setPositiveButton(if (creating) "Create" else "Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        fun showDevices(detail: SessionDetail?) {
            devicesBox.removeAllViews()
            val devices = detail?.devices.orEmpty()
            if (devices.isEmpty()) {
                devicesBox.addView(TextView(this).apply { text = "No devices have reported yet." })
                return
            }
            val own = existing?.optString("device").orEmpty()
            val others = devices.map { it.name }.filter { it != own && it != user }
            devices.forEach { device ->
                val color = personColor(existing?.optString("token").orEmpty(), device.name, own, others, device.color)
                val chip = View(this).apply {
                    val d = (22 * resources.displayMetrics.density).toInt()
                    layoutParams = LinearLayout.LayoutParams(d, d).apply { marginEnd = pad / 2 }
                    setBackgroundColor(color)
                    setOnClickListener {
                        pickColor { hex ->
                            val token = existing?.optString("token").orEmpty()
                            if (token.isNotBlank()) savePersonColor(token, device.name, hex)
                            setBackgroundColor(parseHex(hex))
                            Thread {
                                PhoneTrackApi.updateDeviceColor(this@MainActivity, server, user, pass, sessionId, device.id, hex)
                            }.start()
                        }
                    }
                }
                val track = android.widget.Switch(this).apply {
                    text = "Tracks"
                    isChecked = tracksOn(existing?.optString("token").orEmpty(), device.name)
                    setOnCheckedChangeListener { _, on ->
                        val token = existing?.optString("token").orEmpty()
                        if (token.isNotBlank()) saveTracks(token, device.name, on)
                    }
                }
                devicesBox.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, pad / 4, 0, pad / 4)
                    addView(chip)
                    addView(TextView(this@MainActivity).apply {
                        text = device.name
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(track)
                })
            }
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE))
            Thread {
                val (users, _) = PhoneTrackApi.searchUsers(this, server, user, pass)
                val detail = existing?.optString("token")?.takeIf { it.isNotBlank() }?.let {
                    PhoneTrackApi.fetchSessionDetail(this, server, user, pass, it)
                }
                runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    directory = users
                    renderResults()
                    if (detail != null) {
                        sessionId = detail.id
                        if (detail.publicToken.isNotBlank()) publicToken = detail.publicToken
                        publicSwitch.isChecked = detail.isPublic
                        serverSwitch.isChecked = detail.serverEnabled
                        val index = purgeValues.indexOf(detail.autoPurge).coerceAtLeast(0)
                        purge.setSelection(index)
                        if (detail.publicToken.isNotBlank()) {
                            publicUrl.text = PhoneTrackUrl.parse(server).base +
                                "/index.php/apps/phonetrack/publicSessionWatch/${detail.publicToken}"
                        }
                        showDevices(detail)
                    } else {
                        showDevices(null)
                    }
                }
            }.start()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameField.text.toString().trim()
                val age = ageField.text.toString().trim().toIntOrNull()?.coerceIn(1, 7 * 24 * 60) ?: 15
                val purgeValue = purgeValues[purge.selectedItemPosition]
                if (name.isBlank()) {
                    Toast.makeText(this, "Session name is required", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                val shares = selected.toList()
                if (existing != null) {
                    val token = existing.optString("token")
                    val sessions = loadSessions()
                    sessions.forEach { item ->
                        if (item.optString("token") == token) {
                            item.put("name", name)
                            item.put("age_out_min", age)
                            if (sessionId > 0) item.put("id", sessionId)
                            if (publicToken.isNotBlank()) item.put("public_token", publicToken)
                        }
                    }
                    prefs.edit().putString("sessions", JSONArray(sessions).toString()).apply()
                    Toast.makeText(this, "Saving session…", Toast.LENGTH_SHORT).show()
                    val target = CreatedSession(sessionId, name, token, publicToken)
                    Thread {
                        val notes = mutableListOf<String>()
                        PhoneTrackApi.updateRemoteSession(
                            this, server, user, pass, sessionId,
                            publicSwitch.isChecked, serverSwitch.isChecked, purgeValue
                        )?.let { notes.add(it) }
                        shares.forEach { shareWith ->
                            val shareError = PhoneTrackApi.shareRemoteSession(this, server, user, pass, target, shareWith)
                            notes.add(if (shareError == null) "Shared with $shareWith" else shareError)
                        }
                        runOnUiThread {
                            if (notes.isEmpty()) showSessions()
                            else AlertDialog.Builder(this)
                                .setTitle("Session")
                                .setMessage(notes.joinToString("\n"))
                                .setPositiveButton("OK") { _, _ -> showSessions() }
                                .show()
                        }
                    }.start()
                    return@setOnClickListener
                }
                Toast.makeText(this, "Creating session…", Toast.LENGTH_SHORT).show()
                Thread {
                    val (created, error) = PhoneTrackApi.createRemoteSession(this, server, user, pass, name)
                    val notes = mutableListOf<String>()
                    if (created != null) {
                        PhoneTrackApi.updateRemoteSession(
                            this, server, user, pass, created.id,
                            publicSwitch.isChecked, serverSwitch.isChecked, purgeValue
                        )?.let { notes.add(it) }
                        shares.forEach { shareWith ->
                            val shareError = PhoneTrackApi.shareRemoteSession(this, server, user, pass, created, shareWith)
                            notes.add(if (shareError == null) "Shared with $shareWith" else shareError)
                        }
                    }
                    runOnUiThread {
                        if (created == null) {
                            Toast.makeText(this, error ?: "Could not create the session", Toast.LENGTH_LONG).show()
                        } else {
                            rememberCreatedSession(server, created)
                            val sessions = loadSessions()
                            sessions.forEach { item ->
                                if (item.optString("token") == created.token) item.put("age_out_min", age)
                            }
                            prefs.edit().putString("sessions", JSONArray(sessions).toString()).apply()
                            val summary = buildString {
                                append("Created ")
                                append(created.name)
                                if (notes.isNotEmpty()) {
                                    append("\n")
                                    append(notes.joinToString("\n"))
                                }
                            }
                            AlertDialog.Builder(this)
                                .setTitle("Session created")
                                .setMessage(summary)
                                .setPositiveButton("OK") { _, _ -> showSessions() }
                                .show()
                        }
                    }
                }.start()
            }
        }
        dialog.show()
        pinZoomControls(dialog)
    }

    private fun rememberCreatedSession(baseUrl: String, created: CreatedSession) {
        val sessions = loadSessions().toMutableList()
        if (sessions.none { it.optString("token") == created.token }) {
            sessions.add(JSONObject().apply {
                put("name", created.name)
                put("url", baseUrl)
                put("token", created.token)
                put("public_token", created.publicToken)
                if (created.id > 0) put("id", created.id)
                put("device", prefs.getString("nc_user", "")?.trim().orEmpty().ifEmpty { android.os.Build.MODEL })
                put("enabled", false)
            })
            prefs.edit().putString("sessions", JSONArray(sessions).toString()).apply()
        }
    }

    private fun confirmDeleteSession(session: JSONObject) {
        AlertDialog.Builder(this)
            .setTitle("Delete session?")
            .setMessage("${sessionLabel(session)} will be deleted from the PhoneTrack server.")
            .setPositiveButton("Delete") { _, _ ->
                val user = prefs.getString("nc_user", "")?.trim().orEmpty()
                val pass = prefs.getString("nc_pass", "").orEmpty()
                val server = prefs.getString("nc_auth_url", "")?.trim().orEmpty().ifBlank { session.optString("url") }
                if (user.isBlank() || pass.isBlank() || !isValidBaseUrl(server)) {
                    Toast.makeText(this, "Log in from Settings before deleting a session", Toast.LENGTH_LONG).show()
                    showSettings()
                    return@setPositiveButton
                }
                Toast.makeText(this, "Deleting session…", Toast.LENGTH_SHORT).show()
                Thread {
                    val error = PhoneTrackApi.deleteRemoteSession(
                        this, server, user, pass, session.optString("token"), session.optInt("id", 0)
                    )
                    runOnUiThread {
                        if (error != null) {
                            AlertDialog.Builder(this)
                                .setTitle("Session not deleted")
                                .setMessage(error)
                                .setPositiveButton("OK", null)
                                .show()
                            return@runOnUiThread
                        }
                        val url = session.optString("url")
                        val token = session.optString("token")
                        val device = session.optString("device")
                        val remaining = loadSessions().filterNot { sameSession(it, url, token, device) }
                        prefs.edit().putString("sessions", JSONArray(remaining).toString()).apply()
                        if (isActiveSession(session)) stopTracking()
                        refreshMapBanner()
                        showSessions()
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
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
        if (!isValidBaseUrl(url) || token.isBlank() || !activeSessionEnabled()) return
        val positionId = prefs.getString("nc_user", "")?.trim().orEmpty().ifEmpty { device }
        startLocationService(url, token, positionId)
        startFetchingPositions(url, token, positionId)
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

    private var fetchUrl = ""
    private var fetchToken = ""
    private var fetchDevice = ""

    private fun startFetchingPositions(url: String, token: String, device: String) {
        fetchUrl = url
        fetchToken = token
        fetchDevice = device
        fetchTimer?.cancel()
        mapEpoch += 1
        val epoch = mapEpoch
        val seconds = prefs.getInt("fetch_interval_sec", 30).coerceIn(5, 3600)
        fetchTimer = Timer()
        fetchTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                runOnUiThread { updateMapWithPositions(url, token, device, epoch) }
            }
        }, 0, seconds * 1000L)
    }

    private fun updateMapWithPositions(url: String, token: String, ownDevice: String, epoch: Int) {
        Thread {
            val positions = PhoneTrackApi.getLastPositions(this, url, token)
            val ageOut = ageOutMinutes(token)
            val tracked = positions?.keys?.filter { tracksOn(token, it) }.orEmpty()
            val tracks = if (tracked.isNotEmpty()) PhoneTrackApi.getTrackPoints(this, url, token, ageOut) else emptyMap()
            runOnUiThread {
                if (epoch != mapEpoch) return@runOnUiThread
                if (positions == null) {
                    prefs.edit().putString("last_read_result", "failed").apply()
                    updateStatusText("Map update failed")
                    refreshConnectionStatus()
                    return@runOnUiThread
                }
                prefs.edit()
                    .putString("last_read_result", "ok")
                    .putLong("last_map_ok", System.currentTimeMillis())
                    .apply()
                refreshMapBanner()
                map.overlays.clear()
                lastPoints.clear()
                lastSeen.clear()
                val others = positions.keys.filter { it != ownDevice }.sorted()
                tracked.forEach { name ->
                    val color = personColor(token, name, ownDevice, others)
                    val history = tracks[name]
                        ?: tracks.entries.firstOrNull { it.key.equals(name, true) }?.value
                        ?: emptyList()
                    val current = positions[name]
                    history.forEach { point ->
                        if (current != null && kotlin.math.abs(point.lat - current.lat) < 0.00005 && kotlin.math.abs(point.lon - current.lon) < 0.00005) {
                            return@forEach
                        }
                        val alpha = ageAlpha(point.timestamp, ageOut)
                        if (alpha <= 0.05f) return@forEach
                        val dot = Marker(map)
                        dot.position = GeoPoint(point.lat, point.lon)
                        dot.title = "Track"
                        dot.snippet = "Age: ${ageText(point.timestamp)}"
                        dot.icon = iconFor(color, 16, alpha)
                        dot.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        val seenAt = point.timestamp
                        dot.relatedObject = name
                        dot.setOnMarkerClickListener { clicked, _ ->
                            clicked.snippet = "Age: ${ageText(seenAt)}"
                            showStatusBubble(clicked)
                            true
                        }
                        map.overlays.add(dot)
                    }
                }
                positions.forEach { (name, data) ->
                    val alpha = ageAlpha(data.timestamp, ageOut)
                    if (alpha <= 0.05f) return@forEach
                    val point = GeoPoint(data.lat, data.lon)
                    lastPoints[name] = point
                    lastSeen[name] = data.timestamp
                    val marker = Marker(map)
                    marker.position = point
                    val mine = name == ownDevice
                    marker.title = if (mine) "You · $name" else name
                    marker.snippet = "Acc: ${data.accuracy}m | Age: ${ageText(data.timestamp)}"
                    marker.icon = iconFor(personColor(token, name, ownDevice, others), 28, alpha)
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    marker.setOnMarkerClickListener { clicked, _ ->
                        clicked.snippet = "Acc: ${data.accuracy}m | Age: ${ageText(data.timestamp)}"
                        enterUserTrack(name)
                        showStatusBubble(clicked)
                        true
                    }
                    map.overlays.add(marker)
                }
                addSpoofMarker()
                if (pickFollowOnNextMap) {
                    pickFollowOnNextMap = false
                    when (lastPoints.size) {
                        0 -> {
                            mapFollow = MapFollow.GROUP
                            trackedUser = null
                        }
                        1 -> {
                            mapFollow = MapFollow.USER
                            trackedUser = lastPoints.keys.first()
                        }
                        else -> {
                            mapFollow = MapFollow.GROUP
                            trackedUser = null
                        }
                    }
                    refreshZoomKeys()
                }
                applyMapFollow(lastPoints.values.toList())
                map.invalidate()
                updateStatusText("Map updated: ${positions.size} devices")
            }
        }.start()
    }

    private fun activeSessionFailed(): Boolean {
        val url = prefs.getString("url", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        if (url.isBlank() || token.isBlank()) return false
        val active = loadSessions().any {
            it.optBoolean("enabled", true) && it.optString("url") == url && it.optString("token") == token
        }
        if (!active) return false
        val link = linkState()
        return link.readFail || link.pushFail
    }

    private fun armActivationZoom() {
        pickFollowOnNextMap = true
    }

    private fun chooseActivationZoom() {
        when (lastPoints.size) {
            0 -> enterGroupView()
            1 -> enterUserTrack(lastPoints.keys.first())
            else -> enterGroupView()
        }
    }

    private fun showStatusBubble(marker: Marker) {
        marker.showInfoWindow()
        val token = ++bubbleToken
        marker.infoWindow?.view?.alpha = 1f
        map.postDelayed({
            if (token != bubbleToken) return@postDelayed
            val bubble = marker.infoWindow?.view
            if (bubble == null) {
                marker.closeInfoWindow()
                return@postDelayed
            }
            bubble.animate().alpha(0f).setDuration(400).withEndAction {
                if (token == bubbleToken) marker.closeInfoWindow()
                bubble.alpha = 1f
            }.start()
        }, 5000)
    }

    private fun pinZoomControls(dialog: AlertDialog) {
        if (dialog.isShowing) return
    }

    private fun zoomKey(label: String, onClick: () -> Unit): TextView {
        val gap = (6 * resources.displayMetrics.density).toInt()
        val size = (44 * resources.displayMetrics.density).toInt()
        return TextView(this).apply {
            text = label
            gravity = android.view.Gravity.CENTER
            setBackgroundResource(R.drawable.zoom_key_bg)
            setTextColor(Color.parseColor("#111111"))
            textSize = if (label.length > 1) 13f else 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { bottomMargin = gap }
            setOnClickListener { onClick() }
        }
    }

    private fun showLocationSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val width = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        var selectedMode = currentPositionMode()
        val modeList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun renderModes() {
            modeList.removeAllViews()
            positionModes.forEach { item ->
                modeList.addView(modeChoice(item, item == selectedMode) {
                    selectedMode = item
                    setPositionMode(item)
                    renderModes()
                })
            }
        }
        renderModes()
        var spoofAdjusting = false
        val spoof = androidx.appcompat.widget.SwitchCompat(this).apply {
            text = "Location spoof"
            isChecked = prefs.getBoolean("spoof_enabled", false)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnCheckedChangeListener { _, checked ->
                if (spoofAdjusting) return@setOnCheckedChangeListener
                spoofAdjusting = true
                isChecked = !checked
                spoofAdjusting = false
                val message = if (checked) "Turn on location spoof?" else "Turn off location spoof?"
                confirmChoice(message) {
                    spoofAdjusting = true
                    isChecked = checked
                    spoofAdjusting = false
                    prefs.edit().putBoolean("spoof_enabled", checked).apply()
                    if (checked) addSpoofMarker() else removeSpoofMarker()
                    refreshModeButton()
                    if (!prefs.getString("url", "").isNullOrBlank()) startTrackingFromPrefs()
                }
            }
        }
        val spoofIcon = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_mode_spy)
            val size = (32 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = pad / 2 }
        }
        val lat = EditText(this).apply {
            hint = "Latitude"
            setText(prefs.getString("spoof_lat", "") ?: "")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            layoutParams = width
        }
        val lon = EditText(this).apply {
            hint = "Longitude"
            setText(prefs.getString("spoof_lon", "") ?: "")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            layoutParams = width
        }
        box.addView(TextView(this).apply {
            text = "Position update mode"
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = "Off sends nothing. Manual sends when you tap Send now. Significant sends after a real move. Move sends on the normal interval. Rapid sends every second."
            setPadding(0, pad / 4, 0, pad / 2)
        })
        box.addView(modeList)
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, pad, 0, 0)
            addView(spoofIcon)
            addView(spoof)
        })
        box.addView(lat)
        box.addView(lon)
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
            .setTitle("Location")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save", null)
            .setNeutralButton("Send now", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.WHITE))
            pinZoomControls(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                prefs.edit()
                    .putString("position_mode", selectedMode)
                    .putBoolean("spoof_enabled", spoof.isChecked)
                    .putString("spoof_lat", lat.text.toString().trim())
                    .putString("spoof_lon", lon.text.toString().trim())
                    .apply()
                dialog.dismiss()
                refreshModeButton()
                if (!spoof.isChecked) removeSpoofMarker()
                if (!prefs.getString("url", "").isNullOrBlank()) startTrackingFromPrefs()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                prefs.edit()
                    .putBoolean("spoof_enabled", spoof.isChecked)
                    .putString("spoof_lat", lat.text.toString().trim())
                    .putString("spoof_lon", lon.text.toString().trim())
                    .apply()
                val url = prefs.getString("url", "") ?: ""
                val token = prefs.getString("token", "") ?: ""
                if (!isValidBaseUrl(url) || token.isBlank()) {
                    Toast.makeText(this, "Turn on a session before sending a position", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val device = prefs.getString("nc_user", "")?.trim().orEmpty()
                    .ifEmpty { prefs.getString("device", "Android") ?: "Android" }
                startService(Intent(this, LocationPublishService::class.java).apply {
                    action = LocationPublishService.ACTION_PUBLISH_ONCE
                    putExtra("url", url)
                    putExtra("token", token)
                    putExtra("device", device)
                })
                Toast.makeText(this, "Sending position", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private val positionModes = listOf("manual", "quiet", "significant", "move", "rapid")

    private fun currentPositionMode(): String = prefs.getString("position_mode", "move") ?: "move"

    private fun modeLabel(mode: String): String = when (mode) {
        "quiet" -> "Off"
        "manual" -> "Manual"
        "significant" -> "Significant"
        "rapid" -> "Rapid"
        "spoof" -> "Spoof"
        else -> "Move"
    }

    private fun modeIcon(mode: String): Int = when (mode) {
        "quiet" -> R.drawable.ic_mode_off
        "manual" -> R.drawable.ic_mode_manual
        "significant" -> R.drawable.ic_mode_walk
        "rapid" -> R.drawable.ic_mode_rocket
        "spoof" -> R.drawable.ic_mode_spy
        else -> R.drawable.ic_mode_car
    }

    private fun refreshModeButton() {
        if (!::btnPositionMode.isInitialized) return
        val spoofing = prefs.getBoolean("spoof_enabled", false)
        btnPositionMode.setImageResource(if (spoofing) R.drawable.ic_mode_spy else modeIcon(currentPositionMode()))
        btnPositionMode.contentDescription = if (spoofing) "Location spoof" else modeLabel(currentPositionMode())
        btnPositionMode.invalidate()
    }

    private fun toggleModePicker() {
        if (modePicker.visibility == View.VISIBLE) {
            modePicker.visibility = View.GONE
            return
        }
        val current = currentPositionMode()
        val spoofing = prefs.getBoolean("spoof_enabled", false)
        modePicker.removeAllViews()
        positionModes.forEach { mode ->
            modePicker.addView(modeChoice(mode, !spoofing && mode == current) {
                modePicker.visibility = View.GONE
                if (spoofing) {
                    confirmChoice("Turn off location spoof?") {
                        prefs.edit().putBoolean("spoof_enabled", false).apply()
                        removeSpoofMarker()
                        setPositionMode(mode)
                    }
                } else {
                    setPositionMode(mode)
                }
            })
        }
        modePicker.addView(modeChoice("spoof", spoofing) {
            modePicker.visibility = View.GONE
            if (spoofing) return@modeChoice
            confirmChoice("Turn on location spoof?") {
                prefs.edit().putBoolean("spoof_enabled", true).apply()
                refreshModeButton()
                addSpoofMarker()
                if (!prefs.getString("url", "").isNullOrBlank()) startTrackingFromPrefs()
            }
        })
        modePicker.visibility = View.VISIBLE
        modePicker.bringToFront()
        btnPositionMode.bringToFront()
    }

    private fun modeChoice(mode: String, selected: Boolean, onClick: () -> Unit): LinearLayout {
        val pad = (10 * resources.displayMetrics.density).toInt()
        val iconSize = (28 * resources.displayMetrics.density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad * 2, pad)
            if (selected) setBackgroundColor(Color.parseColor("#E3F2FD"))
            addView(android.widget.ImageView(this@MainActivity).apply {
                setImageResource(modeIcon(mode))
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            })
            addView(TextView(this@MainActivity).apply {
                text = modeLabel(mode)
                setPadding(pad, 0, 0, 0)
                textSize = 16f
                setTextColor(Color.parseColor("#111111"))
            })
            setOnClickListener { onClick() }
        }
    }

    private fun confirmChoice(message: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("Yes") { _, _ -> onYes() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun activateSpoofAt(lat: Double, lon: Double) {
        val on = prefs.getBoolean("spoof_enabled", false)
        val message = if (on) "Move location spoof to this point?" else "Turn on location spoof at this point?"
        confirmChoice(message) { activateSpoof(lat, lon) }
    }

    private fun setPositionMode(mode: String) {
        prefs.edit().putString("position_mode", mode).apply()
        refreshModeButton()
        if (!prefs.getString("url", "").isNullOrBlank()) startTrackingFromPrefs()
    }

    private fun ageText(timestampSec: Long): String {
        val sec = (System.currentTimeMillis() / 1000 - timestampSec).coerceAtLeast(0)
        val hours = sec / 3600
        val minutes = (sec % 3600) / 60
        return "${hours}h ${minutes}m"
    }

    private fun sendCurrentPosition() {
        if (currentPositionMode() == "quiet" && !prefs.getBoolean("spoof_enabled", false)) return
        val url = prefs.getString("url", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        if (!isValidBaseUrl(url) || token.isBlank()) {
            Toast.makeText(this, "Turn on a session before sending a position", Toast.LENGTH_LONG).show()
            return
        }
        val device = prefs.getString("nc_user", "")?.trim().orEmpty()
            .ifEmpty { prefs.getString("device", "Android") ?: "Android" }
        startService(Intent(this, LocationPublishService::class.java).apply {
            action = LocationPublishService.ACTION_PUBLISH_ONCE
            putExtra("url", url)
            putExtra("token", token)
            putExtra("device", device)
        })
        Toast.makeText(this, "Sending position", Toast.LENGTH_SHORT).show()
    }

    private fun centerOnSelf() {
        val name = prefs.getString("nc_user", "")?.trim().orEmpty()
        val match = lastPoints.keys.firstOrNull { it.equals(name, true) }
        if (match == null) {
            Toast.makeText(this, "Your position is not on the map", Toast.LENGTH_SHORT).show()
            return
        }
        allMode = false
        enterUserTrack(match)
    }

    private fun zoomToLastObserved() {
        val name = lastSeen.maxByOrNull { it.value }?.key
        if (name != null && lastPoints.containsKey(name)) {
            allMode = true
            enterUserTrack(name, fromAll = true)
        }
    }

    private fun activateSpoof(lat: Double, lon: Double) {
        prefs.edit()
            .putBoolean("spoof_enabled", true)
            .putString("spoof_lat", lat.toString())
            .putString("spoof_lon", lon.toString())
            .apply()
        enterManual()
        refreshModeButton()
        addSpoofMarker()
        map.invalidate()
        if (!prefs.getString("url", "").isNullOrBlank()) startTrackingFromPrefs()
        Toast.makeText(this, "Spoofing %.5f, %.5f".format(lat, lon), Toast.LENGTH_SHORT).show()
    }

    private fun removeSpoofMarker() {
        if (!::map.isInitialized) return
        map.overlays.removeAll { (it as? Marker)?.title == "Spoof" }
        map.invalidate()
    }

    private fun addSpoofMarker() {
        if (!::map.isInitialized || !prefs.getBoolean("spoof_enabled", false)) return
        val lat = prefs.getString("spoof_lat", null)?.toDoubleOrNull() ?: return
        val lon = prefs.getString("spoof_lon", null)?.toDoubleOrNull() ?: return
        map.overlays.removeAll { (it as? Marker)?.title == "Spoof" }
        val marker = Marker(map)
        marker.position = GeoPoint(lat, lon)
        marker.title = "Spoof"
        marker.snippet = "%.5f, %.5f".format(lat, lon)
        marker.icon = ContextCompat.getDrawable(this, R.drawable.ic_mode_spy)
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        marker.setOnMarkerClickListener { clicked, _ ->
            showStatusBubble(clicked)
            true
        }
        map.overlays.add(marker)
    }

    private fun enterManual() {
        if (mapFollow == MapFollow.MANUAL && !allMode) return
        allMode = false
        mapFollow = MapFollow.MANUAL
        trackedUser = null
        refreshZoomKeys()
    }

    private fun enterUserTrack(name: String, fromAll: Boolean = false) {
        allMode = fromAll
        mapFollow = MapFollow.USER
        trackedUser = name
        refreshZoomKeys()
        lastPoints[name]?.let { map.controller.animateTo(it) }
    }

    private fun enterGroupView() {
        allMode = false
        mapFollow = MapFollow.GROUP
        trackedUser = null
        refreshZoomKeys()
        applyMapFollow(lastPoints.values.toList())
    }

    private fun refreshZoomKeys() {
        val tracking = mapFollow == MapFollow.USER
        val mapContext = !::statusPanel.isInitialized || statusPanel.visibility != View.VISIBLE
        mapControls.visibility = if (mapContext) View.VISIBLE else View.GONE
        btnZoomIn.visibility = if (mapContext && tracking) View.VISIBLE else View.GONE
        btnZoomOut.visibility = if (mapContext && tracking) View.VISIBLE else View.GONE
        btnGroupView.visibility = if (mapContext && !allMode) View.VISIBLE else View.GONE
        if (::btnReticle.isInitialized) btnReticle.visibility = if (mapContext) View.VISIBLE else View.GONE
        if (::mapControls.isInitialized && mapContext) mapControls.bringToFront()
        if (::btnPositionMode.isInitialized && mapContext) btnPositionMode.bringToFront()
        if (::btnReticle.isInitialized && mapContext) btnReticle.bringToFront()
        if (!mapContext && ::statusPanel.isInitialized && statusPanel.visibility == View.VISIBLE) statusPanel.bringToFront()
    }

    private fun applyMapFollow(points: List<GeoPoint>) {
        when (mapFollow) {
            MapFollow.MANUAL -> Unit
            MapFollow.USER -> lastPoints[trackedUser]?.let { map.controller.setCenter(it) }
            MapFollow.GROUP -> {
                if (points.isEmpty()) return
                if (points.size == 1) {
                    map.controller.setCenter(points[0])
                    map.controller.setZoom(16.0)
                } else {
                    map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), false, 100)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        refreshMapBanner()
        if (logTimer == null) startLogTimer()
        refreshSettingsPermissions?.invoke()
        val url = prefs.getString("url", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        if (fetchTimer == null && url.isNotBlank() && token.isNotBlank() && checkPermissions() && activeSessionEnabled()) {
            startTrackingFromPrefs()
        }
    }
    override fun onPause() { super.onPause(); map.onPause(); stopLogTimer() }
    override fun onDestroy() {
        fetchTimer?.cancel()
        fetchTimer = null
        super.onDestroy()
    }
}

private enum class MapFollow { GROUP, USER, MANUAL }

private data class FriendRow(
    val title: String,
    val token: String,
    val ownName: String,
    val users: List<String>,
    val enabled: Boolean,
    val reachable: Boolean,
    val points: Map<String, GeoPoint>
)
