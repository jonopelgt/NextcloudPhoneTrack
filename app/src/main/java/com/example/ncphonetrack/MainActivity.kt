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
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.android.material.navigation.NavigationView
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
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
    private lateinit var tvStatusCreator: TextView
    private lateinit var tvStatusToken: TextView
    private lateinit var tvLoggingState: TextView
    private lateinit var tvConnection: TextView
    private lateinit var sessionStatusList: LinearLayout
    private lateinit var tvMapBanner: TextView
    private lateinit var sessionBanner: TextView
    private lateinit var noSessionBanner: TextView
    private lateinit var loginBanner: android.view.View
    private lateinit var tvLastUpdate: TextView
    private lateinit var tvBuffer: TextView
    private lateinit var tvLog: TextView
    private lateinit var logScroll: android.widget.ScrollView
    private var followLogBottom = true
    private val LOCATION_PERMISSION_REQUEST = 1001
    private val BACKGROUND_PERMISSION_REQUEST = 1002

    companion object {
        var superuser = false
        private var superuserTaps = 0
    }
    private var logTimer: Timer? = null
    private var fetchTimer: Timer? = null
    private val mapReadSeq = java.util.concurrent.atomic.AtomicInteger(0)
    private val mapPaintedSeq = java.util.concurrent.atomic.AtomicInteger(0)
    private val trackReadBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val trackCache = mutableMapOf<String, MutableList<TrackPoint>>()
    private var mapEpoch = 0
    private var mapFollow = MapFollow.GROUP
    private var zoomControlActive = false
    private var pendingSlideZoom: Double? = null
    private var slideFramePosted = false
    private val shownColor = mutableMapOf<String, Int>()
    private var trackedUser: String? = null
    private val lastPoints = mutableMapOf<String, GeoPoint>()
    private lateinit var btnZoomIn: TextView
    private lateinit var btnZoomOut: TextView
    private lateinit var zoomSlide: ZoomSlide
    private lateinit var btnGroupView: TextView
    private lateinit var btnReticle: ImageButton
    private var allMode = false
    private lateinit var mapControls: View
    private lateinit var btnPositionMode: ImageButton
    private lateinit var btnSharing: ImageButton
    private lateinit var modePicker: LinearLayout
    private var openModeMenu: String? = null
    private var pickFollowOnNextMap = false
    private var bubbleToken = 0
    private var lastSeenName: String? = null
    private var lastSeenPoint: GeoPoint? = null
    private var lastSeenText: String = ""
    private var lastSeenUntil = 0L
    private var lastSeenBubbleUntil = 0L
    private var lastSeenAlpha = 1f
    private var lastSeenGen = 0
    private var deviceBubbleWindow: org.osmdroid.views.overlay.infowindow.InfoWindow? = null
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
        val mapStore = File(filesDir, "osmdroid")
        Configuration.getInstance().osmdroidBasePath = mapStore
        Configuration.getInstance().osmdroidTileCache = File(mapStore, "tiles")
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        if (prefs.getString("position_mode", null).isNullOrBlank()) {
            prefs.edit().putString("position_mode", "move").apply()
        }
        SharingControl.ensureFirstShare(this)
        backfillPostedNames()
        val sharingFilter = android.content.IntentFilter(SharingControl.ACTION_SHARING_CHANGED)
        sharingFilter.addAction(SharingControl.ACTION_TIMER_TICK)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(sharingChanged, sharingFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(sharingChanged, sharingFilter)
        }
        drawerLayout = findViewById(R.id.drawer_layout)
        statusPanel = findViewById(R.id.status_panel)
        tvStatusUrl = findViewById(R.id.tvStatusUrl)
        tvStatusCreator = findViewById(R.id.tvStatusCreator)
        tvStatusToken = findViewById(R.id.tvStatusToken)
        tvLoggingState = findViewById(R.id.tvLoggingState)
        tvConnection = findViewById(R.id.tvConnection)
        sessionStatusList = findViewById(R.id.sessionStatusList)
        tvMapBanner = findViewById(R.id.tvMapBanner)
        sessionBanner = findViewById(R.id.tvSessionBanner)
        noSessionBanner = findViewById(R.id.tvNoSessionBanner)
        loginBanner = findViewById(R.id.loginBanner)
        val countSuperuser = {
            if (!superuser) {
                superuserTaps++
                if (superuserTaps >= 15) {
                    superuser = true
                    refreshShareModeLabel()
                    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
                        .setMessage("Superuser mode is active.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
        sessionBanner.setOnClickListener { countSuperuser() }
        tvMapBanner.setOnClickListener {
            val link = linkState()
            val localOnly = link.localUnavailable && !link.readFail && !link.pushFail
            if (localOnly || connectionFailMessage(displayedSessionName(), link) == null) return@setOnClickListener
            showStatusPanel()
        }
        findViewById<Button>(R.id.btnLoginSettings).setOnClickListener { showSettings() }
        tvLastUpdate = findViewById(R.id.tvLastUpdate)
        tvBuffer = findViewById(R.id.tvBuffer)
        tvLog = findViewById(R.id.tvLog)
        logScroll = findViewById(R.id.logScroll)
        val verbosity = findViewById<android.widget.SeekBar>(R.id.logVerbosity)
        val verbosityLabel = findViewById<TextView>(R.id.tvLogVerbosity)
        verbosity.progress = prefs.getInt("log_verbosity", 1).coerceIn(0, 3)
        verbosityLabel.text = verbosityName(verbosity.progress)
        verbosity.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.edit().putInt("log_verbosity", progress).apply()
                verbosityLabel.text = verbosityName(progress)
                if (::statusPanel.isInitialized && statusPanel.visibility == View.VISIBLE) refreshLog()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        findViewById<ImageButton>(R.id.btnCopyLog).setOnClickListener { copyLog() }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener { clearLog() }
        findViewById<Button>(R.id.btnExportLog).setOnClickListener { exportLog() }
        findViewById<View>(R.id.btnShareLog).setOnClickListener { postLogToTelemetry() }
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
        applyBaseMap(prefs.getString("base_map", "organic") ?: "organic")
        map.setMultiTouchControls(true)
        map.setBuiltInZoomControls(false)
        map.controller.setZoom(12.0)
        map.controller.setCenter(GeoPoint(42.7654, -71.4676))
        btnZoomIn = findViewById(R.id.btnZoomIn)
        btnZoomOut = findViewById(R.id.btnZoomOut)
        zoomSlide = findViewById(R.id.zoomSlide)
        zoomSlide.limits = { 6.0 to 19.0 }
        zoomSlide.onZoom = { level ->
            pendingSlideZoom = level.coerceIn(6.0, 19.0)
            OfflineMaps.scaleOnly = true
            if (!slideFramePosted) {
                slideFramePosted = true
                map.postOnAnimation {
                    slideFramePosted = false
                    val target = pendingSlideZoom ?: return@postOnAnimation
                    keepFollow { zoomAroundFollow(target) }
                }
            }
        }
        zoomSlide.onGesture = { active ->
            zoomControlActive = active
            OfflineMaps.scaleOnly = active
            map.setUseDataConnection(!active)
            if (!active) {
                slideFramePosted = false
                val target = pendingSlideZoom
                pendingSlideZoom = null
                if (target != null) keepFollow { zoomAroundFollow(target) }
                else recenterFollow()
                reloadHighestTiles()
            }
        }
        zoomSlide.setZoom(map.zoomLevelDouble)
        map.addMapListener(object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean = false
            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                zoomSlide.setZoom(map.zoomLevelDouble)
                return false
            }
        })
        btnGroupView = findViewById(R.id.btnGroupView)
        btnReticle = findViewById(R.id.btnReticle)
        mapControls = findViewById(R.id.mapControls)
        btnPositionMode = findViewById(R.id.btnPositionMode)
        btnSharing = findViewById(R.id.btnSharing)
        modePicker = findViewById(R.id.modePicker)
        refreshModeButton()
        LauncherIcon.onActivityLaunch(this)
        btnPositionMode.setOnClickListener {
            if (modePicker.visibility == View.VISIBLE) closeModeMenu() else sendCurrentPosition()
        }
        btnPositionMode.setOnLongClickListener {
            showModeMenu("position")
            true
        }
        btnSharing.setOnLongClickListener {
            showModeMenu("sharing")
            true
        }
        holdZoom(btnZoomIn, 0.25)
        holdZoom(btnZoomOut, -0.25)
        btnGroupView.setOnClickListener { zoomToAll() }
        btnReticle.setOnClickListener { cycleFollow() }
        mapControls.bringToFront()
        refreshZoomKeys()
        refreshMapBanner()

        var downX = 0f
        var downY = 0f
        var dragged = false
        val longPress = Runnable {
            if (!superuser || dragged || statusPanel.visibility == View.VISIBLE) return@Runnable
            val geo = map.projection.fromPixels(downX.toInt(), downY.toInt())
            activateSpoofAt(geo.latitude, geo.longitude)
        }
        map.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    dragged = false
                    closeModeMenu()
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
                R.id.nav_diagnostics -> {
                    navView.post { toggleDiagnosticPages(navView) }
                }
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
                R.id.nav_position_mode -> {
                    showDiagnostics()
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
                    showMaps()
                    drawerLayout.closeDrawers()
                }
            }
            true
        }

        val savedUrl = prefs.getString("url", null)
        val savedToken = prefs.getString("token", null)
        if (!savedUrl.isNullOrBlank() && !savedToken.isNullOrBlank() && isValidBaseUrl(savedUrl) && activeSessionEnabled() &&
            (checkPermissions() || positionUpdatesOff())
        ) {
            startTrackingFromPrefs()
            updateStatusText("Auto-started tracking")
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), LOCATION_PERMISSION_REQUEST)
            }
            promptBackgroundOnce()
        }
        offerHereState()
    }

    private fun toggleDiagnosticPages(navView: NavigationView) {
        val menu = navView.menu
        if (menu.findItem(R.id.nav_status) != null) {
            menu.removeItem(R.id.nav_status)
            menu.removeItem(R.id.nav_position_mode)
            return
        }
        menu.add(R.id.nav_diag, R.id.nav_status, 7, "Connection Status")
            .setIcon(android.R.drawable.ic_menu_info_details)
        menu.add(R.id.nav_diag, R.id.nav_position_mode, 8, "Position update mode")
            .setIcon(android.R.drawable.ic_menu_sort_by_size)
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

    private fun markerIcon(color: Int, diameterDp: Int, alpha: Float, outline: Boolean): BitmapDrawable {
        val size = (diameterDp * resources.displayMetrics.density).toInt().coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        paint.color = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawCircle(size / 2f, size / 2f, size / 2.6f, paint)
        if (outline) {
            paint.color = Color.argb(a, 255, 255, 255)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size / 8f
            canvas.drawCircle(size / 2f, size / 2f, size / 2.6f, paint)
        }
        return BitmapDrawable(resources, bmp)
    }

    private fun iconFor(color: Int, diameterDp: Int, alpha: Float, outline: Boolean = true): BitmapDrawable {
        val bucket = (alpha * 20).toInt().coerceIn(1, 20)
        val key = "$color:$diameterDp:$bucket:$outline"
        return iconCache.getOrPut(key) { markerIcon(color, diameterDp, bucket / 20f, outline) }
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
            name == ownName || isSelf(name) -> "#1565C0"
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
        val token = prefs.getString("token", "") ?: ""
        val name = displayedSessionName()
        tvStatusUrl.text = "Session name: ${name.ifBlank { "—" }}"
        tvStatusCreator.text = "Session creator: ${displayedSession()?.optString("owner").orEmpty().ifBlank { "—" }}"
        tvStatusToken.text = "Token: $token"
        val state = prefs.getString("logging_state", "idle") ?: "idle"
        val result = sessionPush(token).first.ifBlank { prefs.getString("last_result", "none") ?: "none" }
        tvLoggingState.text = named("$name", "Logging: $state (${resultLabel(result)})")
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

    private data class LinkState(
        val readFail: Boolean,
        val pushFail: Boolean,
        val localUnavailable: Boolean,
        val pushWaiting: Boolean,
        val readOk: Boolean,
        val pushFresh: Boolean
    )

    private fun displayedToken(): String = prefs.getString("token", "") ?: ""

    private fun displayedSession(): JSONObject? {
        val token = displayedToken()
        if (token.isBlank()) return null
        return loadSessions().find { it.optString("token") == token && it.optBoolean("enabled", true) }
    }

    private fun displayedSessionName(): String = displayedSession()?.let { sessionLabel(it) }.orEmpty()

    private fun named(name: String, message: String): String {
        return if (name.isBlank()) message else "$name: $message"
    }

    private fun sessionPush(token: String): Pair<String, Long> {
        if (token.isBlank()) return "" to 0L
        val all = try {
            JSONObject(prefs.getString("session_push", "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        val item = all.optJSONObject(token) ?: return "" to 0L
        return item.optString("result") to item.optLong("epoch")
    }

    private fun linkStateFor(token: String, includeRead: Boolean): LinkState {
        val now = System.currentTimeMillis()
        val publishSec = prefs.getInt("publish_interval_sec", 60).coerceIn(5, 3600)
        val fetchMs = fetchIntervalMs()
        val readWindow = maxOf(fetchMs * 2, 20_000L)
        val (pushResult, pushEpoch) = sessionPush(token)
        val result = pushResult.ifBlank {
            if (token.isNotBlank() && token == displayedToken()) prefs.getString("last_result", "") ?: "" else ""
        }
        val successEpoch = if (pushEpoch > 0L) pushEpoch else {
            if (token.isNotBlank() && token == displayedToken()) prefs.getLong("last_success_epoch", 0L) else 0L
        }
        val stateName = prefs.getString("logging_state", "idle") ?: "idle"
        val updatesOff = positionUpdatesOff()
        val (netResult, netEpoch) = sessionNet(token)
        val mapRead = if (includeRead) prefs.getLong("last_map_ok", 0L) else 0L
        val mapFresh = mapRead > 0L && now - mapRead <= readWindow
        val readResult = when {
            includeRead && mapFresh && prefs.getString("last_read_result", "") == "ok" -> "ok"
            netResult.isNotBlank() -> netResult
            includeRead -> prefs.getString("last_read_result", "") ?: ""
            else -> ""
        }
        val mapOk = maxOf(netEpoch, mapRead)
        val usingNet = netResult.isNotBlank() || mapFresh
        val readFresh = (usingNet || includeRead) && readResult == "ok" && mapOk > 0L && now - mapOk <= readWindow
        val readFail = (usingNet || includeRead) && (readResult == "failed" || (readResult == "ok" && !readFresh))
        val pushFresh = result == "ok" && successEpoch > 0L && now - successEpoch <= publishSec * 2L * 1000
        val quiet = stateName == "off" || updatesOff
        val localUnavailable = updatesOff || (!quiet && stateName != "manual" && stateName != "stopped" &&
            (stateName == "location off" || stateName == "permission denied" ||
                stateName == "waiting for GPS" || stateName == "no position"))
        val pushFail = !updatesOff && !localUnavailable && result == "network"
        val noAttempt = result.isBlank() || result == "none"
        val pushWaiting = !quiet && !localUnavailable && !pushFresh && !pushFail && noAttempt && stateName != "stopped"
        return LinkState(readFail, pushFail, localUnavailable, pushWaiting, readFresh, pushFresh)
    }

    private fun linkState(): LinkState = linkStateFor(displayedToken(), true)

    private fun connectionFailMessage(name: String, link: LinkState, includeName: Boolean = true): String? {
        val recovered = link.readOk || link.pushFresh
        val readFail = link.readFail && !recovered
        val pushFail = link.pushFail && !recovered
        val text = when {
            link.localUnavailable && readFail -> "Connection fail read. local position unavailable."
            link.localUnavailable -> "local position unavailable."
            readFail && pushFail -> "Connection fail read and push"
            readFail -> "Connection fail read"
            pushFail -> "Connection fail push"
            else -> return null
        }
        return if (includeName) named(name, text) else text
    }

    private fun bufferedFor(token: String): Int = LocationPublishService.unsent(this, token)

    private fun sessionNet(token: String): Pair<String, Long> {
        if (token.isBlank()) return "" to 0L
        val all = try {
            org.json.JSONObject(prefs.getString("session_net", "{}") ?: "{}")
        } catch (_: Exception) {
            org.json.JSONObject()
        }
        val item = all.optJSONObject(token) ?: return "" to 0L
        return item.optString("result") to item.optLong("epoch")
    }

    private var netCheckRunning = false
    private var lastNetCheck = 0L

    private fun checkAllSessionNetworks() {
        if (netCheckRunning || System.currentTimeMillis() - lastNetCheck < 15000L) return
        val sessions = loadSessions().filter { it.optString("token").isNotBlank() && it.optString("url").isNotBlank() }
        if (sessions.isEmpty()) return
        netCheckRunning = true
        lastNetCheck = System.currentTimeMillis()
        val pending = java.util.concurrent.atomic.AtomicInteger(sessions.size)
        val results = try {
            org.json.JSONObject(prefs.getString("session_net", "{}") ?: "{}")
        } catch (_: Exception) {
            org.json.JSONObject()
        }
        fun finish() {
            prefs.edit().putString("session_net", results.toString()).apply()
            netCheckRunning = false
            runOnUiThread {
                if (!isDestroyed && statusPanel.visibility == View.VISIBLE) refreshConnectionStatus()
            }
        }
        sessions.forEach { session ->
            val accepted = ReadLanes.execute(this) { client ->
                val token = session.optString("token")
                val name = sessionLabel(session)
                val reached = PhoneTrackApi.getLastPositions(this, session.optString("url"), token, quiet = true, client = client) != null
                val result = if (reached) "ok" else "failed"
                synchronized(results) {
                    val previous = results.optJSONObject(token)?.optString("result").orEmpty()
                    results.put(token, org.json.JSONObject().put("result", result).put("epoch", System.currentTimeMillis()))
                    if (previous != result) LogManager.add("$name: connection $result")
                }
                if (pending.decrementAndGet() == 0) finish()
            }
            if (!accepted && pending.decrementAndGet() == 0) finish()
        }
    }

    private fun verbosityName(level: Int): String = when (level) {
        0 -> "Quiet"
        2 -> "Verbose"
        3 -> "Debug"
        else -> "Normal"
    }

    private fun logDetail(message: String): Int {
        val text = message.lowercase()
        if (text.startsWith("get ") || text.contains("get response") || text.contains("get parsed") ||
            text.startsWith("fix lat") || text.contains("tracks parsed") || text.contains("tracks http") ||
            text.contains("map updated") || text.contains("map update skipped")
        ) return 2
        if (text.contains("fail") || text.contains("error") || text.contains("denied") ||
            text.contains("rejected") || text.contains("unavailable") || text.contains("stopped") ||
            text.contains("permission") || text.contains("rate limit") || text.contains(": network") ||
            text.contains("are off") || text.contains("no position") || text.contains("no location") ||
            text.contains("not sent")
        ) return 0
        return 1
    }

    private fun selectedSessionLog(): String {
        val name = displayedSessionName()
        val verbosity = prefs.getInt("log_verbosity", 1).coerceIn(0, 3)
        val kept = LogManager.entries().filter { (line, detail) ->
            val message = line.substringAfter(' ').trim()
            val level = if (detail >= 0) detail else logDetail(message)
            val forSession = message.startsWith("App ") || verbosity >= 3 || name.isBlank() || message.startsWith("$name:") || message.startsWith("$name ")
            forSession && level <= verbosity
        }
        return kept.joinToString("\n") { it.first }.ifEmpty { "No messages yet" }
    }

    private fun refreshConnectionStatus() {
        val name = displayedSessionName()
        val token = displayedToken()
        val link = linkState()
        val message = connectionFailMessage(name, link)
        val (pushResult, pushEpoch) = sessionPush(token)
        val result = pushResult.ifBlank {
            if (token.isNotBlank()) prefs.getString("last_result", "") ?: "" else ""
        }
        val state = prefs.getString("logging_state", "idle") ?: "idle"
        tvStatusUrl.text = "Session name: ${name.ifBlank { "—" }}"
        tvStatusCreator.text = "Session creator: ${displayedSession()?.optString("owner").orEmpty().ifBlank { "—" }}"
        tvStatusToken.text = "Token: $token"
        val detail = named(name, "Logging: $state (${resultLabel(result)})")
        tvLoggingState.text = if (message == null) detail else "$message\n$detail"
        val ageSec = if (pushEpoch <= 0L) Long.MAX_VALUE else (System.currentTimeMillis() - pushEpoch) / 1000
        tvLastUpdate.text = if (pushEpoch <= 0L) named(name, "Last update: never") else named(name, "Last update: ${ageSec}s ago")
        tvBuffer.text = named(name, "Buffered points: ${bufferedFor(token)}")
        refreshSessionStatusList()
        refreshMapBanner()
        if (statusPanel.visibility == View.VISIBLE) checkAllSessionNetworks()
    }

    private fun refreshSessionStatusList() {
        sessionStatusList.removeAllViews()
        val sessions = loadSessions()
        if (sessions.isEmpty()) {
            sessionStatusList.addView(TextView(this).apply { text = "No sessions" })
            return
        }
        val shown = displayedToken()
        val nameWeight = 1.4f
        val creatorWeight = 1f
        val statusWeight = 1f
        fun weighted(weight: Float) = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        sessionStatusList.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val radioSpace = (48 * resources.displayMetrics.density).toInt()
            addView(android.view.View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(radioSpace, 1)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Session name"
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = weighted(nameWeight)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Creator"
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = weighted(creatorWeight)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Status"
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = weighted(statusWeight)
            })
        })
        sessions.forEach { session ->
            val enabled = session.optBoolean("enabled", true)
            val token = session.optString("token")
            val selected = token.isNotBlank() && token == shown
            val sessionLink = linkStateFor(token, selected)
            val label = sessionLabel(session)
            val message = connectionFailMessage(label, sessionLink, includeName = false)
            val status: String
            val color: String
            val rowColor: Int
            when {
                !enabled -> {
                    status = "Off"
                    color = "#9E9E9E"
                    rowColor = Color.TRANSPARENT
                }
                message != null && sessionLink.localUnavailable && !sessionLink.readFail && !sessionLink.pushFail -> {
                    status = message
                    color = "#424242"
                    rowColor = Color.parseColor("#E0E0E0")
                }
                message != null -> {
                    status = message
                    color = "#C62828"
                    rowColor = Color.TRANSPARENT
                }
                ReadLanes.waiting() + bufferedFor(token) > 10 -> {
                    status = "Queue constipated"
                    color = "#4E342E"
                    rowColor = Color.parseColor("#D7CCC8")
                }
                else -> {
                    status = "Healthy"
                    color = "#1B7F3A"
                    rowColor = Color.TRANSPARENT
                }
            }
            val name = TextView(this).apply {
                text = label
                textSize = 16f
                layoutParams = weighted(nameWeight)
            }
            val creator = TextView(this).apply {
                text = session.optString("owner").ifBlank { "—" }
                textSize = 14f
                setTextColor(Color.parseColor("#616161"))
                layoutParams = weighted(creatorWeight)
            }
            val state = TextView(this).apply {
                text = status
                textSize = 16f
                setTextColor(Color.parseColor(color))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = weighted(statusWeight)
            }
            val radio = android.widget.RadioButton(this).apply {
                isChecked = selected
                contentDescription = "Shown on the map"
                setOnClickListener {
                    if (token == displayedToken()) {
                        isChecked = true
                        return@setOnClickListener
                    }
                    showSessionOnMap(session)
                    refreshConnectionStatus()
                }
            }
            name.setOnClickListener { radio.performClick() }
            sessionStatusList.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
                setBackgroundColor(rowColor)
                setOnClickListener { radio.performClick() }
                addView(radio)
                addView(name)
                addView(creator)
                addView(state)
            })
        }
    }

    private fun refreshMapBanner() {
        val name = displayedSessionName()
        val link = linkState()
        val message = connectionFailMessage(name, link)
        val mapVisible = statusPanel.visibility != View.VISIBLE
        val verified = prefs.getBoolean("credentials_verified", false)
        val noneActive = loadSessions().none { it.optBoolean("enabled", true) }
        tvMapBanner.text = message ?: named(name, "Not connected")
        val localOnly = link.localUnavailable && !link.readFail && !link.pushFail
        tvMapBanner.setBackgroundColor(Color.parseColor(if (localOnly) "#757575" else "#C62828"))
        sessionBanner.text = if (SharingControl.remaining(this) > 0L && !positionUpdatesOff()) {
            val left = SharingControl.remainingWithUntil(this)
            if (name.isBlank()) "($left)" else "$name ($left)"
        } else {
            name
        }
        sessionBanner.visibility = if (name.isNotBlank() && !noneActive && mapVisible) View.VISIBLE else View.GONE
        noSessionBanner.visibility = if (noneActive && mapVisible) View.VISIBLE else View.GONE
        tvMapBanner.visibility = if (message != null && !noneActive && mapVisible) View.VISIBLE else View.GONE
        loginBanner.visibility = if (!verified && mapVisible) View.VISIBLE else View.GONE
    }

    private fun copyLog() {
        val text = selectedSessionLog()
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PhoneTrack log", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun clearLog() {
        LogManager.clear()
        refreshLog()
    }

    private fun exportLog() {
        val header = debugHeader()
        LogManager.add(header, 0)
        refreshLog()
        val text = "$header\n${selectedSessionLog()}"
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

    private fun debugHeader(): String {
        val user = prefs.getString("nc_user", "")?.trim().orEmpty().ifBlank { "none" }
        val phone = Build.MODEL?.trim().orEmpty().ifBlank { "unknown" }
        val release = Build.VERSION.RELEASE?.trim().orEmpty().ifBlank { "?" }
        val session = displayedSessionName().ifBlank { "none" }
        val sharing = SharingControl.statusLabel(this)
        val position = modeLabel(currentPositionMode())
        return "App ${readVersion()} user $user phone $phone Android $release session $session sharing $sharing position $position"
    }

    private fun postLogToTelemetry() {
        val user = prefs.getString("nc_user", "")?.trim().orEmpty()
        val pass = prefs.getString("nc_pass", "").orEmpty()
        val server = PhoneTrackUrl.parse(
            prefs.getString("nc_auth_url", null)?.takeIf { it.isNotBlank() } ?: prefs.getString("url", "") ?: ""
        ).base
        if (user.isBlank() || pass.isBlank() || server.isBlank()) {
            Toast.makeText(this, "Sign in to Nextcloud first", Toast.LENGTH_LONG).show()
            return
        }
        val header = debugHeader()
        LogManager.add(header, 0)
        refreshLog()
        val text = "$header\n${selectedSessionLog()}"
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val name = "phonetrack-log-$stamp.txt"
        Toast.makeText(this, "Posting log", Toast.LENGTH_SHORT).show()
        Thread {
            val result = uploadTelemetryLog(server, user, pass, name, text)
            runOnUiThread {
                val named = displayedSessionName().let { if (it.isBlank()) result else "$it: $result" }
                LogManager.add(named, 0)
                refreshLog()
                Toast.makeText(this, result, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun uploadTelemetryLog(server: String, user: String, pass: String, name: String, text: String): String {
        fun enc(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        val client = CertHelper.buildClient(prefs)
        val auth = Credentials.basic(user, pass)
        val parts = listOf("Shared", "nextcloud development file share", "Telemetry", "NC-phone-track-client-app")
        var folder = "$server/remote.php/dav/files/${enc(user)}"
        try {
            for (part in parts) {
                folder += "/${enc(part)}"
                val made = Request.Builder().url(folder).method("MKCOL", null).header("Authorization", auth).build()
                client.newCall(made).execute().close()
            }
            val url = "$folder/${enc(name)}"
            val put = Request.Builder()
                .url(url)
                .put(text.toRequestBody("text/plain".toMediaType()))
                .header("Authorization", auth)
                .build()
            client.newCall(put).execute().use { response ->
                return if (response.isSuccessful) "Log posted to telemetry" else "Log post failed: ${response.code}"
            }
        } catch (e: Exception) {
            return "Log post failed: ${e.message}"
        }
    }

    private fun refreshLog() {
        tvLog.text = selectedSessionLog()
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
        }.toMutableList()
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
    private var refreshSettingsSharing: (() -> Unit)? = null
    private var refreshLocationPage: (() -> Unit)? = null
    private var serverTokens: Set<String>? = null
    private var dismissedMissing = setOf<String>()
    private val createdTokens = mutableSetOf<String>()
    private var missingPrompt: AlertDialog? = null

    private fun showSessions(reloadNames: Boolean = true) {
        val sessions = sessionsForList()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val list = sessionsList ?: LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }.also { sessionsList = it }
        list.removeAllViews()
        if (sessions.isEmpty()) {
            list.addView(TextView(this).apply {
                text = if (serverTokens != null) "No sessions on the server." else "No sessions yet. Use New session."
                setPadding(0, pad, 0, pad)
            })
        } else {
            list.addView(TextView(this).apply {
                text = "The filled circle is the session shown on the map. More than one session can stay on."
                textSize = 12f
                setPadding(0, 0, 0, pad / 2)
            })
            sessions.forEach { session ->
                val viewing = session.optString("token") == displayedToken()
                val radio = android.widget.RadioButton(this).apply {
                    isChecked = viewing
                    contentDescription = "Shown on the map"
                    setOnClickListener {
                        if (session.optString("token") == displayedToken()) {
                            isChecked = true
                            return@setOnClickListener
                        }
                        showSessionOnMap(session)
                        statusPanel.visibility = View.GONE
                        refreshMapBanner()
                        showSessions(reloadNames = false)
                    }
                }
                val label = TextView(this).apply {
                    text = sessionLabel(session)
                    textSize = 16f
                }
                val title = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(label)
                    val owner = session.optString("owner")
                    if (owner.isNotBlank()) {
                        addView(TextView(this@MainActivity).apply {
                            text = owner
                            textSize = 14f
                            setTextColor(Color.parseColor("#616161"))
                            setPadding((8 * resources.displayMetrics.density).toInt(), 0, 0, 0)
                        })
                    }
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
                    setOnClickListener { radio.performClick() }
                    setOnLongClickListener { anchor ->
                        showSessionHoldMenu(anchor, session)
                        true
                    }
                    addView(radio)
                    addView(title)
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
                    mergeRemoteSessions(url, remote)
                    val tokens = remote.map { it.token }.toSet()
                    serverTokens = tokens
                    createdTokens.removeAll(tokens)
                    if (sessionsDialog?.isShowing == true) showSessions(reloadNames = false)
                    promptMissingSessions()
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

        val displayToken = displayedToken()
        val ordered = sessions.sortedWith(
            compareByDescending<JSONObject> { it.optString("token").isNotBlank() && it.optString("token") == displayToken }
                .thenByDescending { it.optBoolean("enabled", true) }
        )
        val loaded = mutableMapOf<String, FriendLoad>()
        var tracksLabel: TextView? = null
        fun paintUsers(
            token: String,
            session: JSONObject,
            usersBox: LinearLayout,
            titleView: TextView,
            enabled: Boolean,
            data: FriendLoad
        ) {
            val userColor = if (enabled && data.reachable) Color.BLACK else Color.parseColor("#757575")
            if (enabled && !data.reachable) {
                titleView.text = "${sessionLabel(session)} (unavailable)"
                titleView.setTextColor(Color.parseColor("#9E9E9E"))
            }
            if (usersBox.visibility != View.VISIBLE) return
            usersBox.removeAllViews()
            val users = data.points.keys.sorted()
            if (users.isEmpty()) {
                usersBox.addView(TextView(this).apply {
                    text = if (data.reachable) "No users reported" else "Users could not be loaded"
                    setTextColor(userColor)
                    setPadding(indent, 0, 0, pad / 2)
                })
                return
            }
            tracksLabel?.visibility = View.VISIBLE
            val ownName = session.optString("device")
            val others = users.filter { it != ownName && it != prefs.getString("nc_user", "")?.trim() }
            users.forEach { user ->
                val chip = View(this).apply {
                    val d = (22 * resources.displayMetrics.density).toInt()
                    layoutParams = LinearLayout.LayoutParams(d, d).apply { marginEnd = pad / 2 }
                    setBackgroundColor(personColor(token, user, ownName, others))
                    setOnClickListener {
                        pickColor { hex ->
                            savePersonColor(token, user, hex)
                            setBackgroundColor(parseHex(hex))
                        }
                    }
                }
                val nameView = TextView(this).apply {
                    val seenAt = lastSeenLabel(data.seen[user] ?: 0L)
                    text = if (seenAt.isBlank()) user else "$user ($seenAt)"
                    textSize = 15f
                    setTextColor(userColor)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        dialog.dismiss()
                        statusPanel.visibility = View.GONE
                        val point = data.points[user]
                        val displayed = deviceOnMap(user)
                        loadSessions().find { it.optString("token") == token }?.let {
                            showSessionOnMap(it, keepView = true)
                        }
                        if (!displayed && point != null) {
                            showLastSeen(user, point, data.seen[user] ?: 0L)
                        } else {
                            lastPoints.clear()
                            lastPoints.putAll(data.points)
                            enterUserTrack(user)
                        }
                        refreshMapBanner()
                    }
                }
                val share = ImageButton(this).apply {
                    val point = data.points[user]
                    val d = (40 * resources.displayMetrics.density).toInt()
                    val gap = (8 * resources.displayMetrics.density).toInt()
                    layoutParams = LinearLayout.LayoutParams(d, d).apply { marginEnd = gap }
                    setImageResource(R.drawable.ic_share_pin)
                    setBackgroundResource(R.drawable.share_button_bg)
                    setPadding(gap / 2, gap / 2, gap / 2, gap / 2)
                    contentDescription = "Share location"
                    visibility = if (point == null) View.GONE else View.VISIBLE
                    setOnClickListener {
                        val here = data.points[user] ?: return@setOnClickListener
                        sharePosition(user, here.latitude, here.longitude)
                    }
                }
                val track = android.widget.Switch(this).apply {
                    contentDescription = "Tracks"
                    isChecked = tracksOn(token, user)
                    setOnCheckedChangeListener { _, on -> saveTracks(token, user, on) }
                }
                usersBox.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(indent, pad / 4, 0, pad / 4)
                    addView(chip)
                    addView(nameView)
                    addView(share)
                    addView(track)
                })
            }
        }
        ordered.forEachIndexed { index, session ->
            val token = session.optString("token")
            val enabled = session.optBoolean("enabled", true)
            val sessionColor = if (enabled) Color.BLACK else Color.parseColor("#9E9E9E")
            val selected = token.isNotBlank() && token == displayToken
            val usersBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (selected) View.VISIBLE else View.GONE
            }
            val titleView = TextView(this).apply {
                text = if (enabled) sessionLabel(session) else "${sessionLabel(session)} (disabled)"
                textSize = 16f
                setTextColor(sessionColor)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            titleView.setOnClickListener {
                if (usersBox.visibility != View.VISIBLE) {
                    usersBox.visibility = View.VISIBLE
                    val data = loaded[token] ?: cachedFriends(token)
                    if (data == null) {
                        usersBox.removeAllViews()
                        usersBox.addView(TextView(this).apply {
                            text = "Loading users…"
                            setTextColor(Color.parseColor("#757575"))
                            setPadding(indent, 0, 0, pad / 2)
                        })
                    } else {
                        loaded[token] = data
                        paintUsers(token, session, usersBox, titleView, enabled, data)
                    }
                    return@setOnClickListener
                }
                val current = loadSessions().find { it.optString("token") == token } ?: return@setOnClickListener
                dialog.dismiss()
                statusPanel.visibility = View.GONE
                showSessionOnMap(current, keepView = true)
                lastPoints.clear()
                loaded[token]?.points?.let { lastPoints.putAll(it) }
                zoomToAll()
                refreshMapBanner()
            }
            if (selected) {
                val cached = cachedFriends(token)
                if (cached == null) {
                    usersBox.addView(TextView(this).apply {
                        text = "Loading users…"
                        setTextColor(Color.parseColor("#757575"))
                        setPadding(indent, 0, 0, pad / 2)
                    })
                } else {
                    loaded[token] = cached
                    paintUsers(token, session, usersBox, titleView, enabled, cached)
                }
            }
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, pad, 0, pad / 4)
                addView(android.widget.RadioButton(this@MainActivity).apply {
                    isChecked = selected
                    isClickable = false
                    isFocusable = false
                    contentDescription = "Shown on the map"
                })
                addView(titleView)
                if (index == 0) {
                    tracksLabel = TextView(this@MainActivity).apply {
                        text = "Tracks"
                        textSize = 13f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        visibility = View.GONE
                    }
                    addView(tracksLabel)
                }
            })
            list.addView(usersBox)
            ReadLanes.execute(this) { client ->
                val positions = PhoneTrackApi.getLastPositions(this, session.optString("url"), token, client = client)
                val data = FriendLoad(
                    positions?.mapValues { (_, item) -> GeoPoint(item.lat, item.lon) }.orEmpty(),
                    positions?.mapValues { (_, item) -> item.timestamp }.orEmpty(),
                    positions != null
                )
                runOnUiThread {
                    if (!dialog.isShowing || isFinishing) return@runOnUiThread
                    loaded[token] = data
                    if (data.reachable) rememberFriends(token, data.points, data.seen)
                    paintUsers(token, session, usersBox, titleView, enabled, data)
                }
            }
        }
    }

    private fun showSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
        }
        val width = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, pad, 0, pad / 2)
            addView(TextView(this@MainActivity).apply {
                text = "Location sharing"
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(android.widget.Switch(this@MainActivity).apply {
                isChecked = !positionUpdatesOff()
                setOnCheckedChangeListener { _, checked ->
                    if (checked == !positionUpdatesOff()) return@setOnCheckedChangeListener
                    setLocationSharing(checked)
                }
                refreshSettingsSharing = { isChecked = !positionUpdatesOff() }
            })
        })
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
                    setOnClickListener { openPermissionSettings(item) }
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
                backfillPostedNames()
                dialog.dismiss()
                Toast.makeText(this, "Checking login…", Toast.LENGTH_SHORT).show()
                refreshMapBanner()
                verifyLogin(url, user, pass)
            }
        }
        dialog.setOnDismissListener {
            refreshSettingsPermissions = null
            refreshSettingsSharing = null
        }
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

    private fun openPermissionSettings(item: RequiredPermission) {
        openPermissionPage(item)
    }

    private fun openPermissionPage(item: RequiredPermission) {
        for (intent in permissionPageIntents(item)) {
            if (packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) continue
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open settings", Toast.LENGTH_LONG).show()
        }
    }

    private fun permissionPageIntents(item: RequiredPermission): List<Intent> {
        if (item.permission == Manifest.permission.POST_NOTIFICATIONS) {
            return listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                putExtra("android.provider.extra.APP_PACKAGE", packageName)
                putExtra("app_package", packageName)
                putExtra("app_uid", applicationInfo.uid)
            })
        }
        val group = when (item.permission) {
            Manifest.permission.ACTIVITY_RECOGNITION -> "android.permission-group.ACTIVITY_RECOGNITION"
            else -> "android.permission-group.LOCATION"
        }
        val controllers = listOf(
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.samsung.android.permissioncontroller"
        )
        val intents = mutableListOf<Intent>()
        fun locationPage(pkg: String?) = Intent("android.intent.action.MANAGE_APP_PERMISSION").apply {
            putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
            putExtra("android.intent.extra.PERMISSION_NAME", item.permission)
            putExtra("android.intent.extra.PERMISSION_GROUP_NAME", group)
            addCategory(Intent.CATEGORY_DEFAULT)
            if (pkg != null) setPackage(pkg)
        }
        controllers.forEach { intents.add(locationPage(it)) }
        intents.add(locationPage(null))
        controllers.forEach { pkg ->
            intents.add(Intent("android.intent.action.MANAGE_APP_PERMISSIONS").apply {
                putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
                addCategory(Intent.CATEGORY_DEFAULT)
                setPackage(pkg)
            })
        }
        return intents
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
                    put("owner", item.owner)
                    put("device", LocationPublishService.defaultPostedName(this@MainActivity))
                    put("enabled", false)
                })
                added += 1
            } else {
                if (item.name.isNotBlank()) existing.put("name", item.name)
                if (item.publicToken.isNotBlank()) existing.put("public_token", item.publicToken)
                if (item.owner.isNotBlank()) existing.put("owner", item.owner)
                if (existing.optString("url").isBlank()) existing.put("url", baseUrl)
            }
        }
        prefs.edit().putString("sessions", JSONArray(sessions).toString()).apply()
        return added
    }

    private fun sessionsForList(): List<JSONObject> {
        val sessions = loadSessions()
        val tokens = serverTokens ?: return sessions
        return sessions.filter { it.optString("token") in tokens || it.optString("token") in createdTokens }
    }

    private fun promptMissingSessions() {
        val tokens = serverTokens ?: return
        val missing = loadSessions().filter {
            val token = it.optString("token")
            token !in tokens && token !in createdTokens
        }
        if (missing.isEmpty()) return
        val keys = missing.map { it.optString("token") }.toSet()
        if (keys == dismissedMissing || missingPrompt?.isShowing == true) return
        val names = missing.joinToString("\n") { session ->
            if (isActiveSession(session)) "${sessionLabel(session)} (active)" else sessionLabel(session)
        }
        missingPrompt = AlertDialog.Builder(this)
            .setTitle("Not on the server")
            .setMessage("These sessions are not on the PhoneTrack server:\n\n$names\n\nDelete them from this phone?")
            .setPositiveButton("Delete") { _, _ ->
                removeLocalSessions(missing)
                dismissedMissing = emptySet()
                if (sessionsDialog?.isShowing == true) showSessions(reloadNames = false)
            }
            .setNegativeButton("Cancel") { _, _ -> dismissedMissing = keys }
            .setOnDismissListener { missingPrompt = null }
            .create()
        missingPrompt?.show()
    }

    private fun removeLocalSessions(gone: List<JSONObject>) {
        val tokens = gone.map { it.optString("token") }.toSet()
        val remaining = loadSessions().filter { it.optString("token") !in tokens }
        prefs.edit().putString("sessions", JSONArray(remaining).toString()).commit()
        val shown = prefs.getString("token", "") ?: ""
        if (shown in tokens) {
            val next = remaining.firstOrNull { it.optBoolean("enabled", true) }
            if (next != null) showSessionOnMap(next) else stopTracking()
        } else if (remaining.any { it.optBoolean("enabled", true) }) {
            startIfPermittedOrAsk()
        }
        refreshMapBanner()
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

    private fun showSessionOnMap(session: JSONObject, keepView: Boolean = false) {
        val url = session.optString("url")
        val token = session.optString("token")
        val device = session.optString("device")
        val sessions = loadSessions()
        sessions.forEach { item ->
            if (sameSession(item, url, token, device)) item.put("enabled", true)
        }
        prefs.edit()
            .putString("sessions", JSONArray(sessions).toString())
            .putString("url", url)
            .putString("token", token)
            .putString("device", device)
            .remove("last_read_result")
            .remove("last_map_ok")
            .commit()
        if (!keepView) armActivationZoom()
        val name = sessionLabel(session)
        updateStatusText("$name: shown on the map")
        startIfPermittedOrAsk()
        publishNow()
    }

    private fun setSessionEnabled(session: JSONObject, enabled: Boolean) {
        val url = session.optString("url")
        val token = session.optString("token")
        val device = session.optString("device")
        val name = sessionLabel(session)
        val sessions = loadSessions()
        sessions.forEach { item ->
            if (sameSession(item, url, token, device)) item.put("enabled", enabled)
        }
        prefs.edit().putString("sessions", JSONArray(sessions).toString()).commit()
        if (enabled) {
            updateStatusText("$name: on")
            if (displayedSession() == null) showSessionOnMap(session)
            else {
                startIfPermittedOrAsk()
                publishNow()
            }
        } else if (isActiveSession(session)) {
            updateStatusText("$name: off")
            val next = loadSessions().firstOrNull { it.optBoolean("enabled", true) }
            if (next != null) showSessionOnMap(next) else stopTracking()
        } else {
            updateStatusText("$name: off")
            if (loadSessions().any { it.optBoolean("enabled", true) }) startIfPermittedOrAsk()
            else stopTracking()
        }
        if (sessionsDialog?.isShowing == true) syncSessionSwitches() else showSessions(reloadNames = false)
        refreshMapBanner()
    }

    private fun syncSessionSwitches() {
        val list = sessionsList ?: return
        val sessions = sessionsForList()
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
                    "Delete" -> {
                        if (ownsSession(session)) confirmDeleteSession(session)
                        else Toast.makeText(this@MainActivity, "Only the owner can delete this session", Toast.LENGTH_LONG).show()
                    }
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
            setText(existing?.optString("name").orEmpty())
            layoutParams = width
        }
        val creatorField = EditText(this).apply {
            setText(existing?.optString("owner").orEmpty().ifBlank { if (existing == null) user else "" })
            isEnabled = false
            layoutParams = width
        }
        val postedField = EditText(this).apply {
            val stored = existing?.optString("device").orEmpty()
            setText(if (stored.isBlank()) LocationPublishService.defaultPostedName(this@MainActivity) else stored)
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
            setPadding(0, pad / 2, pad, pad / 2)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setTextIsSelectable(true)
        }
        val copyLink = android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_copy)
            contentDescription = "Copy public link"
            background = null
            visibility = View.GONE
            setOnClickListener {
                val link = publicUrl.text?.toString().orEmpty()
                if (!link.startsWith("http")) return@setOnClickListener
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Public link", link))
                Toast.makeText(this@MainActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
        val publicAccordion = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(TextView(this@MainActivity).apply {
                text = "Public URL"
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(publicUrl)
                addView(copyLink)
            })
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
        box.addView(TextView(this).apply {
            text = "Session name"
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        box.addView(nameField)
        box.addView(TextView(this).apply {
            text = "Session creator"
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, pad, 0, pad / 4)
        })
        box.addView(creatorField)
        box.addView(TextView(this).apply {
            text = "Posted name"
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, pad, 0, pad / 4)
        })
        box.addView(postedField)
        val ownsServer = existing == null || ownsSession(existing)
        if (!ownsServer) {
            searchField.isEnabled = false
            publicSwitch.isEnabled = false
            serverSwitch.isEnabled = false
            purge.isEnabled = false
            box.addView(TextView(this).apply {
                text = "Only the owner can change this session on the server."
                setPadding(0, pad / 2, 0, 0)
            })
        }
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
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(ageField.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply {
                text = "minutes"
                setPadding(pad / 2, 0, 0, 0)
            })
        })
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
                            if (!ownsServer || sessionId <= 0) return@pickColor
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
                            copyLink.visibility = View.VISIBLE
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
                val posted = postedField.text.toString().trim().ifBlank {
                    LocationPublishService.defaultPostedName(this)
                }
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
                            item.put("device", posted)
                            item.put("device_set", true)
                            if (sessionId > 0) item.put("id", sessionId)
                            if (publicToken.isNotBlank()) item.put("public_token", publicToken)
                        }
                    }
                    val edit = prefs.edit().putString("sessions", JSONArray(sessions).toString())
                    if (prefs.getString("token", "") == token) edit.putString("device", posted)
                    edit.apply()
                    if (!ownsSession(existing)) {
                        Toast.makeText(this, "Saved on this phone. Only the owner can change the session on the server.", Toast.LENGTH_LONG).show()
                        showSessions()
                        return@setOnClickListener
                    }
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
                            rememberCreatedSession(server, created, posted)
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

    private fun rememberCreatedSession(baseUrl: String, created: CreatedSession, posted: String) {
        createdTokens.add(created.token)
        val sessions = loadSessions().toMutableList()
        if (sessions.none { it.optString("token") == created.token }) {
            sessions.add(JSONObject().apply {
                put("name", created.name)
                put("url", baseUrl)
                put("token", created.token)
                put("public_token", created.publicToken)
                put("owner", prefs.getString("nc_user", "")?.trim().orEmpty())
                if (created.id > 0) put("id", created.id)
                put("device", posted.ifBlank { LocationPublishService.defaultPostedName(this@MainActivity) })
                put("device_set", true)
                put("enabled", false)
            })
            prefs.edit().putString("sessions", JSONArray(sessions).toString()).apply()
        }
    }

    private fun backfillPostedNames() {
        val user = prefs.getString("nc_user", "")?.trim().orEmpty()
        if (user.isBlank()) return
        val model = android.os.Build.MODEL?.trim().orEmpty()
        val fallback = LocationPublishService.defaultPostedName(this)
        val sessions = loadSessions()
        var changed = false
        sessions.forEach { item ->
            if (item.optBoolean("device_set", false)) return@forEach
            val device = item.optString("device").trim()
            if (device.isBlank() || device == user || device == model || device == "Android") {
                item.put("device", fallback)
            }
            item.put("device_set", true)
            changed = true
        }
        if (!changed) return
        val edit = prefs.edit().putString("sessions", JSONArray(sessions).toString())
        val active = sessions.find { it.optString("token") == prefs.getString("token", "") }
        if (active != null) edit.putString("device", active.optString("device"))
        edit.apply()
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
        if (needed.isNotEmpty() && !checkPermissions() && !positionUpdatesOff()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), LOCATION_PERMISSION_REQUEST)
            return
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), LOCATION_PERMISSION_REQUEST)
        }
        startTrackingFromPrefs()
        promptBackgroundOnce()
    }

    private fun publishNow() {
        if (positionUpdatesOff() || !checkPermissions()) return
        val url = prefs.getString("url", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        if (!isValidBaseUrl(url) || token.isBlank()) return
        val device = prefs.getString("nc_user", "")?.trim().orEmpty()
            .ifEmpty { prefs.getString("device", "Android") ?: "Android" }
        try {
            ContextCompat.startForegroundService(this, Intent(this, LocationPublishService::class.java).apply {
                action = LocationPublishService.ACTION_PUBLISH_ONCE
                putExtra("url", url)
                putExtra("token", token)
                putExtra("device", device)
            })
        } catch (e: Exception) {
            LogManager.add("Could not send position: ${e.message}")
        }
    }

    private fun startTrackingFromPrefs() {
        val url = prefs.getString("url", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        val device = prefs.getString("device", "Android") ?: "Android"
        if (!isValidBaseUrl(url) || token.isBlank() || !activeSessionEnabled()) return
        LogManager.add(debugHeader(), 0)
        val positionId = prefs.getString("nc_user", "")?.trim().orEmpty().ifEmpty { device }
        if (positionUpdatesOff()) {
            stopService(Intent(this, LocationPublishService::class.java))
            prefs.edit().putString("logging_state", "off").apply()
        } else {
            startLocationService(url, token, positionId)
        }
        startFetchingPositions(url, token, positionId)
        val names = loadSessions().filter { it.optBoolean("enabled", true) }.joinToString(", ") { sessionLabel(it) }
        updateStatusText(if (names.isBlank()) "Tracking started" else "Tracking $names")
        updateStatusText("${displayedSessionName()}: map")
        refreshMapBanner()
    }

    private fun positionUpdatesOff(): Boolean = !SharingControl.isOn(this)

    private fun setLocationSharing(on: Boolean) {
        SharingControl.set(this, on)
        if (!on) removeSpoofMarker()
        refreshModeButton()
        startTrackingFromPrefs()
        refreshMapBanner()
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
                offerHereState()
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
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            LogManager.add("Could not start sharing: ${e.message}")
        }
    }

    private var fetchUrl = ""
    private var fetchToken = ""
    private var fetchDevice = ""

    private fun startFetchingPositions(url: String, token: String, device: String) {
        fetchTimer?.cancel()
        if (fetchToken != token) {
            trackCache.clear()
            if (::map.isInitialized) map.overlays.removeAll { (it as? Marker)?.title == "Track" }
        }
        fetchUrl = url
        fetchToken = token
        fetchDevice = device
        mapEpoch += 1
        val epoch = mapEpoch
        val seconds = fetchIntervalMs()
        fetchTimer = Timer()
        fetchTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                runOnUiThread { updateMapWithPositions(url, token, device, epoch) }
            }
        }, 0, seconds)
    }

    private fun updateMapWithPositions(url: String, token: String, ownDevice: String, epoch: Int) {
        val seq = mapReadSeq.incrementAndGet()
        val accepted = ReadLanes.execute(this) { client ->
            val positions = PhoneTrackApi.getLastPositions(this, url, token, client = client)
            val ageOut = ageOutMinutes(token)
            runOnUiThread {
                if (epoch != mapEpoch) return@runOnUiThread
                if (positions == null) {
                    if (seq < mapReadSeq.get()) return@runOnUiThread
                    prefs.edit().putString("last_read_result", "failed").apply()
                    updateStatusText("${displayedSessionName()}: Map update failed")
                    refreshConnectionStatus()
                    return@runOnUiThread
                }
                if (seq < mapPaintedSeq.get()) return@runOnUiThread
                mapPaintedSeq.set(seq)
                paintPositions(token, ownDevice, positions, ageOut)
                val tracked = positions.keys.filter { tracksOn(token, it) }
                if (tracked.isNotEmpty()) loadTracks(url, token, ownDevice, epoch, tracked, positions, ageOut)
            }
        }
        if (!accepted) mapReadSeq.compareAndSet(seq, seq - 1)
    }

    private fun paintPositions(
        token: String,
        ownDevice: String,
        positions: Map<String, GeoPointData>,
        ageOut: Int
    ) {
        prefs.edit()
            .putString("last_read_result", "ok")
            .putLong("last_map_ok", System.currentTimeMillis())
            .apply()
        refreshMapBanner()
        map.overlays.removeAll { overlay ->
            val marker = overlay as? Marker ?: return@removeAll true
            marker.title != "Track"
        }
        lastPoints.clear()
        val localSec = prefs.getLong("own_fix_sec", 0L)
        val local = ownLastPoint()
        val others = positions.keys.filter { it != ownDevice && !isSelf(it) }.sorted()
        positions.forEach { (name, data) ->
            val alpha = ageAlpha(data.timestamp, ageOut)
            if (alpha <= 0.05f) return@forEach
            val mine = name == ownDevice || isSelf(name)
            val useLocal = mine && local != null && localSec >= data.timestamp
            val point = if (useLocal && local != null) local else GeoPoint(data.lat, data.lon)
            if (mine && !useLocal) rememberOwn(data.lat, data.lon)
            lastPoints[name] = point
            val color = personColor(token, name, ownDevice, others)
            shownColor[name] = color
            val marker = Marker(map)
            marker.position = point
            marker.title = if (mine) "You · $name" else name
            marker.snippet = "Acc: ${data.accuracy}m | Age: ${ageText(if (useLocal) localSec else data.timestamp)}"
            marker.icon = iconFor(color, 28, if (useLocal) ageAlpha(localSec, ageOut).coerceAtLeast(alpha) else alpha)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            marker.infoWindow = deviceBubble()
            marker.setOnMarkerClickListener { clicked, _ ->
                clicked.snippet = marker.snippet
                enterUserTrack(name)
                showStatusBubble(clicked)
                true
            }
            map.overlays.add(marker)
        }
        if (local != null && ownDevice.isNotBlank() && lastPoints.keys.none { it == ownDevice || isSelf(it) }) {
            val alpha = ageAlpha(localSec, ageOut)
            if (alpha > 0.05f) {
                lastPoints[ownDevice] = local
                val color = personColor(token, ownDevice, ownDevice, others)
                shownColor[ownDevice] = color
                val marker = Marker(map)
                marker.position = local
                marker.title = "You · $ownDevice"
                marker.snippet = "Age: ${ageText(localSec)}"
                marker.icon = iconFor(color, 28, alpha)
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                marker.infoWindow = deviceBubble()
                marker.setOnMarkerClickListener { clicked, _ ->
                    enterUserTrack(ownDevice)
                    showStatusBubble(clicked)
                    true
                }
                map.overlays.add(marker)
            }
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
        restoreLastSeen()
        map.invalidate()
        updateStatusText("${displayedSessionName()}: Map updated: ${positions.size} devices")
        logMapDisplay(positions, ageOut)
        rememberFriends(token, positions.mapValues { GeoPoint(it.value.lat, it.value.lon) }, positions.mapValues { it.value.timestamp })
        refreshReticle()
    }

    private fun rememberFriends(token: String, points: Map<String, GeoPoint>, seen: Map<String, Long>) {
        if (token.isBlank() || points.isEmpty()) return
        val users = org.json.JSONArray()
        points.forEach { (name, point) ->
            users.put(org.json.JSONObject()
                .put("name", name)
                .put("lat", point.latitude)
                .put("lon", point.longitude)
                .put("ts", seen[name] ?: 0L))
        }
        val root = loadJsonPref("friend_lists")
        root.put(token, org.json.JSONObject().put("users", users))
        prefs.edit().putString("friend_lists", root.toString()).apply()
    }

    private fun cachedFriends(token: String): FriendLoad? {
        if (token.isBlank()) return null
        val users = loadJsonPref("friend_lists").optJSONObject(token)?.optJSONArray("users") ?: return null
        val points = linkedMapOf<String, GeoPoint>()
        val seen = linkedMapOf<String, Long>()
        for (i in 0 until users.length()) {
            val user = users.optJSONObject(i) ?: continue
            val name = user.optString("name")
            if (name.isBlank()) continue
            points[name] = GeoPoint(user.optDouble("lat"), user.optDouble("lon"))
            seen[name] = user.optLong("ts")
        }
        if (points.isEmpty()) return null
        return FriendLoad(points, seen, true)
    }

    private fun logMapDisplay(positions: Map<String, GeoPointData>, ageOut: Int) {
        if (!::map.isInitialized) return
        val center = map.mapCenter
        val follow = when (mapFollow) {
            MapFollow.MANUAL -> "Manual"
            MapFollow.USER -> trackedUser?.takeIf { it.isNotBlank() } ?: "Manual"
            MapFollow.GROUP -> "All"
        }
        val left = SharingControl.remaining(this)
        val sharing = when {
            positionUpdatesOff() -> "Off"
            left > 0L -> "Timer ${SharingControl.remainingWithUntil(this)}"
            else -> "On"
        }
        val position = if (prefs.getBoolean("spoof_enabled", false)) "Spoof" else modeLabel(currentPositionMode())
        LogManager.add(
            "Map display center ${"%.5f".format(center.latitude)}, ${"%.5f".format(center.longitude)} zoom ${"%.1f".format(map.zoomLevelDouble)} follow $follow sharing $sharing position $position",
            3
        )
        positions.forEach { (name, data) ->
            val alpha = ageAlpha(data.timestamp, ageOut)
            val drawn = alpha > 0.05f
            val why = if (drawn) "yes" else "no, aged out"
            val point = GeoPoint(data.lat, data.lon)
            val onScreen = if (!drawn || map.width < 2) "no" else if (dotOnScreen(point)) "yes" else "no"
            LogManager.add("$name age ${ageText(data.timestamp)} age-out ${ageOut}m drawn $why on screen $onScreen", 3)
        }
    }

    private fun dotOnScreen(point: GeoPoint): Boolean {
        val pixel = map.projection.toPixels(point, null)
        return pixel.x >= 0 && pixel.y >= 0 && pixel.x <= map.width && pixel.y <= map.height
    }

    private fun loadTracks(
        url: String,
        token: String,
        ownDevice: String,
        epoch: Int,
        tracked: List<String>,
        positions: Map<String, GeoPointData>,
        ageOut: Int
    ) {
        if (!trackReadBusy.compareAndSet(false, true)) return
        val accepted = ReadLanes.execute(this) { client ->
            try {
                val nowSec = System.currentTimeMillis() / 1000
                val cutoff = nowSec - ageOut.coerceAtLeast(1) * 60
                var dropped = false
                tracked.forEach { name ->
                    val kept = trackCache[trackKey(token, name)]
                    if (kept != null && kept.removeAll { it.timestamp < cutoff }) dropped = true
                }
                val newest = tracked.mapNotNull { name -> trackCache[trackKey(token, name)]?.maxOfOrNull { it.timestamp } }
                val tsmin = if (newest.size == tracked.size && newest.isNotEmpty()) newest.min() + 1 else cutoff
                val incoming = PhoneTrackApi.getTrackPoints(this, url, token, ageOut, tsmin, client)
                var added = 0
                incoming.forEach { (name, points) ->
                    if (tracked.none { it.equals(name, true) }) return@forEach
                    val key = trackKey(token, tracked.first { it.equals(name, true) })
                    val list = trackCache.getOrPut(key) { mutableListOf() }
                    points.forEach { point ->
                        if (point.timestamp < cutoff) return@forEach
                        val known = list.any {
                            it.timestamp == point.timestamp && it.lat == point.lat && it.lon == point.lon
                        }
                        if (!known) {
                            list.add(point)
                            added += 1
                        }
                    }
                }
                if (added == 0 && !dropped) return@execute
                val snapshot = tracked.associateWith { name ->
                    trackCache[trackKey(token, name)].orEmpty().filter { it.timestamp >= cutoff }
                }
                runOnUiThread {
                    if (epoch != mapEpoch || !::map.isInitialized) return@runOnUiThread
                    map.overlays.removeAll { (it as? Marker)?.title == "Track" }
                    val others = positions.keys.filter { it != ownDevice && !isSelf(it) }.sorted()
                    snapshot.forEach { (name, history) ->
                        val color = shownColor.entries.firstOrNull { it.key.equals(name, true) }?.value
                            ?: personColor(token, name, ownDevice, others)
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
                            dot.icon = iconFor(color, 16, alpha, outline = false)
                            dot.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            val seenAt = point.timestamp
                            dot.relatedObject = name
                            dot.setOnMarkerClickListener { clicked, _ ->
                                clicked.snippet = "Age: ${ageText(seenAt)}"
                                showStatusBubble(clicked)
                                true
                            }
                            map.overlays.add(0, dot)
                        }
                    }
                    map.invalidate()
                    LogManager.add("Tracks showing ${snapshot.values.sumOf { it.size }} points, $added new")
                }
            } finally {
                trackReadBusy.set(false)
            }
        }
        if (!accepted) trackReadBusy.set(false)
    }

    private fun trackKey(token: String, name: String) = "$token\n$name"

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

    private fun sharePosition(name: String, lat: Double, lon: Double) {
        val who = name.removePrefix("You · ").trim().ifBlank { "Location" }
        val geo = Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(who)})")
        val view = Intent(Intent.ACTION_VIEW, geo)
        startActivity(Intent.createChooser(view, "Share location"))
    }

    private fun deviceBubble(): org.osmdroid.views.overlay.infowindow.InfoWindow {
        deviceBubbleWindow?.let { return it }
        val window = object : org.osmdroid.views.overlay.infowindow.InfoWindow(R.layout.device_bubble, map) {
            override fun onOpen(item: Any?) {
                val marker = item as? Marker ?: return
                mView.findViewById<TextView>(R.id.bubbleTitle).text = marker.title ?: ""
                mView.findViewById<TextView>(R.id.bubbleSnippet).text = marker.snippet ?: ""
                mView.findViewById<View>(R.id.bubbleShare).setOnClickListener {
                    val title = marker.title ?: "Location"
                    sharePosition(title, marker.position.latitude, marker.position.longitude)
                }
            }

            override fun onClose() {}
        }
        deviceBubbleWindow = window
        return window
    }

    private fun deviceOnMap(name: String): Boolean {
        if (!::map.isInitialized) return false
        return map.overlays.any { overlay ->
            val marker = overlay as? Marker ?: return@any false
            if (marker.relatedObject == "last-seen" || marker.title == "Track" || marker.title == "Spoof") return@any false
            val title = marker.title ?: return@any false
            title.equals(name, true) || title.equals("You · $name", true)
        }
    }

    private fun showLastSeen(name: String, point: GeoPoint, seenSec: Long) {
        lastSeenGen++
        val generation = lastSeenGen
        lastSeenName = name
        lastSeenPoint = point
        lastSeenText = lastSeenLabel(seenSec).ifBlank { "unknown" }
        lastSeenAlpha = 1f
        val now = System.currentTimeMillis()
        lastSeenUntil = now + 30_000
        lastSeenBubbleUntil = now + 20_000
        allMode = false
        mapFollow = MapFollow.MANUAL
        trackedUser = null
        refreshZoomKeys()
        map.controller.setZoom(map.maxZoomLevel)
        centerAboveBanner(point)
        restoreLastSeen()
        map.invalidate()
        map.postDelayed({
            if (generation != lastSeenGen) return@postDelayed
            lastSeenBubbleUntil = 0L
            (map.overlays.firstOrNull { (it as? Marker)?.relatedObject == "last-seen" } as? Marker)?.closeInfoWindow()
        }, 20_000)
        map.postDelayed({
            if (generation != lastSeenGen) return@postDelayed
            fadeLastSeen(generation)
        }, 30_000)
    }

    private fun fadeLastSeen(generation: Int) {
        if (generation != lastSeenGen || lastSeenPoint == null) return
        lastSeenAlpha -= 0.2f
        if (lastSeenAlpha <= 0.05f) {
            clearLastSeen()
            return
        }
        restoreLastSeen()
        map.invalidate()
        map.postDelayed({ fadeLastSeen(generation) }, 160)
    }

    private fun restoreLastSeen() {
        if (!::map.isInitialized) return
        val point = lastSeenPoint ?: return
        val name = lastSeenName ?: return
        if (System.currentTimeMillis() >= lastSeenUntil && lastSeenAlpha >= 1f) {
            clearLastSeen()
            return
        }
        map.overlays.removeAll { (it as? Marker)?.relatedObject == "last-seen" }
        val marker = Marker(map)
        marker.position = point
        marker.title = name
        marker.snippet = "Last seen $lastSeenText"
        marker.relatedObject = "last-seen"
        marker.icon = iconFor(Color.parseColor("#9E9E9E"), 28, lastSeenAlpha, outline = false)
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        marker.infoWindow = deviceBubble()
        marker.setOnMarkerClickListener { clicked, _ ->
            showStatusBubble(clicked)
            true
        }
        map.overlays.add(marker)
        if (System.currentTimeMillis() < lastSeenBubbleUntil) marker.showInfoWindow()
    }

    private fun clearLastSeen() {
        lastSeenName = null
        lastSeenPoint = null
        lastSeenUntil = 0L
        lastSeenBubbleUntil = 0L
        lastSeenAlpha = 1f
        if (::map.isInitialized) {
            map.overlays.removeAll { (it as? Marker)?.relatedObject == "last-seen" }
            map.invalidate()
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

    private fun applyBaseMap(id: String) {
        if (!::map.isInitialized) return
        OfflineMaps.installBase(this, map, OfflineMaps.base(id))
    }

    private fun fetchIntervalMs(): Long {
        val steps = intArrayOf(500, 1000, 2000, 3000, 4000, 5000, 10000, 15000, 30000, 60000, 300000, 600000)
        val saved = prefs.getInt("fetch_interval_ms", 0)
        val target = if (saved > 0) saved else 3000
        return steps.minBy { kotlin.math.abs(it - target) }.toLong()
    }

    private fun refreshText(ms: Int): String {
        return if (ms < 1000) "0.5 s" else "${ms / 1000} s"
    }

    private fun showMaps() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val saved = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var startDownload: (List<UsState>, Boolean) -> Unit = { _, _ -> }
        val downloadedTitle = TextView(this).apply {
            text = "Downloaded"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, pad / 2, 0, 0)
        }
        var mapsDialog: AlertDialog? = null
        var showSaved: () -> Unit = {}
        var sortByMap = false
        fun mapLabel(id: String): String = OfflineMaps.bases.firstOrNull { it.id == id }?.label ?: "Maps"
        var measuringSizes = false
        fun refreshSizes() {
            if (measuringSizes) return
            measuringSizes = true
            Thread {
                val missing = OfflineMaps.statesMissingSize(prefs)
                missing.forEach { state ->
                    if (OfflineMaps.queue().any { it.abbr == state.abbr }) return@forEach
                    val bytes = OfflineMaps.measureState(this, state, OfflineMaps.storedZoom(prefs, state.abbr))
                    OfflineMaps.saveBytes(prefs, state, bytes)
                }
                OfflineMaps.storedMaps(prefs).filter { it.mapId.isNotBlank() && it.bytes <= 0L }.forEach { piece ->
                    if (OfflineMaps.queue().any { it.key == "${piece.state.abbr}|${piece.mapId}|${piece.zoom}" }) return@forEach
                    val bytes = OfflineMaps.measurePiece(this, piece.state, piece.mapId, piece.zoom)
                    if (bytes > 0L) OfflineMaps.savePieceBytes(prefs, piece.state, piece.mapId, piece.zoom, bytes)
                }
                runOnUiThread {
                    measuringSizes = false
                    if (mapsDialog?.isShowing == true) showSaved()
                }
            }.start()
        }
        fun stateAction(label: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                textSize = 12f
                setOnClickListener { onClick() }
            }
        }
        var measuringCache = false
        var cacheLabelView: TextView? = null
        val progressRows = HashMap<String, Triple<TextView, TextView, android.widget.ProgressBar>>()
        fun paintProgress() {
            val active = OfflineMaps.queue()
            val live = active.filter { !it.queued }.map { it.key }.toSet()
            if (live != progressRows.keys) {
                showSaved()
                return
            }
            active.forEach { job ->
                val row = progressRows[job.abbr] ?: return@forEach
                if (job.queued) return@forEach
                val percent = if (job.total <= 0) 0 else (job.done * 100 / job.total).coerceIn(0, 100)
                row.first.text = OfflineMaps.rateText(job.rateBps)
                row.second.text = "$percent%"
                row.third.progress = percent
            }
        }
        fun paintSaved() {
            saved.removeAllViews()
            progressRows.clear()
            val known = OfflineMaps.viewedSize
            val cacheLabel = TextView(this).apply {
                text = if (known < 0L) "Local cache" else "Local cache (${OfflineMaps.compactSize(known)})"
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            cacheLabelView = cacheLabel
            saved.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, pad / 4, 0, pad / 2)
                addView(cacheLabel)
                addView(Button(this@MainActivity).apply {
                    text = "Clear"
                    textSize = 12f
                    setOnClickListener {
                        OfflineMaps.clearViewed(this@MainActivity)
                        cacheLabel.text = "Local cache (${OfflineMaps.compactSize(0)})"
                        if (::map.isInitialized) {
                            map.tileProvider.clearTileCache()
                            map.invalidate()
                        }
                    }
                })
            })
            if (known < 0L && !measuringCache) {
                measuringCache = true
                Thread {
                    val bytes = OfflineMaps.measureViewed(this)
                    runOnUiThread {
                        measuringCache = false
                        if (mapsDialog?.isShowing == true) {
                            cacheLabelView?.text = "Local cache (${OfflineMaps.compactSize(bytes)})"
                        }
                    }
                }.start()
            }
            val totalBytes = OfflineMaps.storedTotal(prefs)
            downloadedTitle.text = if (totalBytes > 0L) {
                "Downloaded (${OfflineMaps.compactSize(totalBytes)})"
            } else {
                "Downloaded"
            }
            val active = OfflineMaps.queue()
            val activeKeys = active.map { it.key }.toSet()
            active.forEach { job ->
                val status = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    if (job.queued) {
                        addView(TextView(this@MainActivity).apply {
                            text = "queued"
                            textSize = 14f
                            setTextColor(Color.parseColor("#E65100"))
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        })
                    } else {
                        val percent = if (job.total <= 0) 0 else (job.done * 100 / job.total).coerceIn(0, 100)
                        val rateView = TextView(this@MainActivity).apply {
                            text = OfflineMaps.rateText(job.rateBps)
                            textSize = 13f
                            setPadding(0, 0, pad / 3, 0)
                        }
                        val percentView = TextView(this@MainActivity).apply {
                            text = "$percent%"
                            textSize = 13f
                            setPadding(0, 0, pad / 2, 0)
                        }
                        val bar = android.widget.ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                            max = 100
                            progress = percent
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        addView(rateView)
                        addView(percentView)
                        addView(bar)
                        progressRows[job.key] = Triple(rateView, percentView, bar)
                    }
                    addView(stateAction("Cancel") {
                        OfflineMaps.cancel(job.key)
                        paintSaved()
                    })
                }
                saved.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, pad / 4, 0, pad / 2)
                    addView(TextView(this@MainActivity).apply {
                        text = job.name
                        textSize = 16f
                    })
                    addView(status)
                })
            }
            val rows = OfflineMaps.storedMaps(prefs).filter {
                "${it.state.abbr}|${it.mapId}|${it.zoom}" !in activeKeys
            }.sortedWith(
                if (sortByMap) compareBy({ mapLabel(it.mapId) }, { it.state.name }, { it.zoom })
                else compareBy({ it.state.name }, { mapLabel(it.mapId) }, { it.zoom })
            )
            if (rows.isEmpty() && active.isEmpty()) {
                saved.addView(TextView(this).apply {
                    text = "No states downloaded."
                    setPadding(0, pad / 2, 0, pad / 2)
                })
                return
            }
            val format = java.text.SimpleDateFormat("M/d/yy", java.util.Locale.US)
            rows.forEach { item ->
                val fresh = OfflineMaps.isFresh(item.at)
                val whenText = format.format(java.util.Date(item.at))
                val sizeText = if (item.bytes >= 0L) OfflineMaps.compactSize(item.bytes) else null
                val title = when {
                    item.mapId.isBlank() -> item.state.name
                    sortByMap -> "${mapLabel(item.mapId)} · ${item.state.name} z${item.zoom}"
                    else -> "${item.state.name} · ${mapLabel(item.mapId)} z${item.zoom}"
                }
                val statusText = when {
                    fresh && sizeText != null -> "$whenText ($sizeText)  OK"
                    sizeText != null -> "$whenText ($sizeText)"
                    fresh -> "$whenText  OK"
                    else -> whenText
                }
                saved.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, pad / 4, 0, pad / 2)
                    addView(TextView(this@MainActivity).apply {
                        text = title
                        textSize = 16f
                    })
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        addView(TextView(this@MainActivity).apply {
                            text = statusText
                            textSize = 14f
                            setTextColor(Color.parseColor(if (fresh) "#1B7F3A" else "#757575"))
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        addView(stateAction(if (fresh) "Delete" else "Download") {
                            if (fresh) {
                                if (item.mapId.isBlank()) OfflineMaps.remove(this@MainActivity, prefs, item.state)
                                else OfflineMaps.removePiece(this@MainActivity, prefs, item.state, item.mapId, item.zoom)
                                paintSaved()
                            } else if (item.mapId.isBlank()) {
                                startDownload(listOf(item.state), true)
                            } else {
                                confirmMeteredDownload(
                                    listOf(OfflineMaps.MapPick(item.state, item.mapId, item.zoom)),
                                    true
                                ) { line ->
                                    val structural = line == "queued" || line.startsWith("Starting") ||
                                        line.endsWith("started") || line.endsWith("downloaded") ||
                                        line.endsWith("cancelled") || line.contains("failed")
                                    if (structural) paintSaved() else paintProgress()
                                }
                            }
                        })
                    })
                })
            }
        }
        showSaved = { paintSaved() }
        startDownload = { states, force ->
            chooseMapZoom(states, force) { line ->
                val structural = line == "queued" || line.startsWith("Starting") ||
                    line.endsWith("started") || line.endsWith("downloaded") ||
                    line.endsWith("cancelled") || line.contains("failed")
                if (structural) paintSaved() else paintProgress()
            }
        }
        box.addView(TextView(this).apply {
            text = "Refresh"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val refreshSteps = intArrayOf(500, 1000, 2000, 3000, 4000, 5000, 10000, 15000, 30000, 60000, 300000, 600000)
        val refreshLabel = TextView(this).apply {
            text = refreshText(fetchIntervalMs().toInt())
            textSize = 16f
        }
        val refreshSlider = android.widget.SeekBar(this).apply {
            max = refreshSteps.lastIndex
            progress = refreshSteps.indexOf(fetchIntervalMs().toInt()).coerceAtLeast(0)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    val ms = refreshSteps[progress.coerceIn(0, refreshSteps.lastIndex)]
                    refreshLabel.text = refreshText(ms)
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                    val ms = refreshSteps[progress.coerceIn(0, refreshSteps.lastIndex)]
                    prefs.edit().putInt("fetch_interval_ms", ms).apply()
                    if (fetchTimer != null && fetchUrl.isNotBlank() && fetchToken.isNotBlank()) {
                        startFetchingPositions(fetchUrl, fetchToken, fetchDevice)
                    }
                }
            })
        }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(refreshSlider.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(refreshLabel)
        })
        box.addView(TextView(this).apply {
            text = "Base map"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        var selected = prefs.getString("base_map", "organic") ?: "organic"
        val choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun paintChoices() {
            choices.removeAllViews()
            OfflineMaps.bases.forEach { item ->
                choices.addView(TextView(this).apply {
                    text = if (item.id == selected) "●  ${item.label}  z${item.maxZoom}" else "○  ${item.label}  z${item.maxZoom}"
                    textSize = 16f
                    setPadding(0, pad / 3, 0, pad / 3)
                    setOnClickListener {
                        selected = item.id
                        prefs.edit().putString("base_map", item.id).apply()
                        applyBaseMap(item.id)
                        paintChoices()
                    }
                })
            }
        }
        paintChoices()
        box.addView(choices)
        val search = EditText(this).apply {
            hint = "Search US states"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val update = Button(this).apply {
            text = "Update"
            setOnClickListener {
                val picks = OfflineMaps.storedMaps(prefs)
                    .filter { !OfflineMaps.isFresh(it.at) && it.mapId.isNotBlank() }
                    .map { OfflineMaps.MapPick(it.state, it.mapId, it.zoom) }
                if (picks.isEmpty()) {
                    Toast.makeText(this@MainActivity, "All downloaded maps are up to date", Toast.LENGTH_SHORT).show()
                } else {
                    confirmMeteredDownload(picks, true) { line ->
                        val structural = line == "queued" || line.startsWith("Starting") ||
                            line.endsWith("started") || line.endsWith("downloaded") ||
                            line.endsWith("cancelled") || line.contains("failed")
                        if (structural) showSaved() else paintProgress()
                    }
                }
            }
        }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, pad, 0, 0)
            addView(search)
            addView(update)
        })
        box.addView(TextView(this).apply {
            text = "Choose a state, then select each map and zoom."
            textSize = 13f
            setPadding(0, pad / 4, 0, 0)
        })
        box.addView(results)
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(downloadedTitle.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Button(this@MainActivity).apply {
                text = "Sort: State"
                textSize = 12f
                setOnClickListener {
                    sortByMap = !sortByMap
                    text = if (sortByMap) "Sort: Map" else "Sort: State"
                    showSaved()
                }
            })
        })
        box.addView(saved)
        paintSaved()
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                results.removeAllViews()
                OfflineMaps.search(s?.toString().orEmpty()).forEach { state ->
                    results.addView(TextView(this@MainActivity).apply {
                        text = state.name
                        textSize = 16f
                        setPadding(0, pad / 3, 0, pad / 3)
                        setOnClickListener {
                            search.setText("")
                            results.removeAllViews()
                            startDownload(listOf(state), false)
                        }
                    })
                }
            }
        })
        val scroll = android.widget.ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
            .setTitle("Maps")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .create()
            .also { dialog ->
                mapsDialog = dialog
                dialog.show()
                pinZoomControls(dialog)
                refreshSizes()
                offerHereState()
            }
    }

    private var hereStateDialog: AlertDialog? = null
    private var hereStateBusy = false

    private fun offerHereState() {
        if (prefs.getBoolean("asked_here_state", false)) return
        if (OfflineMaps.downloaded(prefs).isNotEmpty()) return
        if (hereStateBusy || hereStateDialog?.isShowing == true) return
        if (!checkPermissions()) return
        hereStateBusy = true
        val manager = getSystemService(android.location.LocationManager::class.java)
        val provider = when {
            manager?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) == true -> android.location.LocationManager.GPS_PROVIDER
            manager?.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) == true -> android.location.LocationManager.NETWORK_PROVIDER
            else -> {
                hereStateBusy = false
                return
            }
        }
        try {
            manager.getLastKnownLocation(provider)?.let {
                askToDownloadState(it.latitude, it.longitude)
                return
            }
            manager.requestSingleUpdate(provider, { fresh ->
                askToDownloadState(fresh.latitude, fresh.longitude)
            }, android.os.Looper.getMainLooper())
        } catch (_: SecurityException) {
            hereStateBusy = false
        }
    }

    private fun askToDownloadState(lat: Double, lon: Double) {
        if (isDestroyed || OfflineMaps.downloaded(prefs).isNotEmpty() || hereStateDialog?.isShowing == true) {
            hereStateBusy = false
            return
        }
        val state = OfflineMaps.stateAt(lat, lon)
        if (state == null || OfflineMaps.isQueued(state.abbr)) {
            hereStateBusy = false
            return
        }
        hereStateDialog = AlertDialog.Builder(this)
            .setTitle("Download ${state.name}?")
            .setMessage("No offline map is saved. Download all five maps for ${state.name}, where this phone is?")
            .setPositiveButton("Download") { _, _ ->
                chooseMapZoom(listOf(state), false) { line ->
                    if (line.endsWith("downloaded") || line.contains("failed")) {
                        Toast.makeText(this, line, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Not now", null)
            .setOnDismissListener {
                hereStateDialog = null
                hereStateBusy = false
            }
            .create()
        prefs.edit().putBoolean("asked_here_state", true).apply()
        hereStateDialog?.show()
    }

    private fun chooseMapZoom(states: List<UsState>, force: Boolean, onLine: (String) -> Unit) {
        val state = states.firstOrNull() ?: run {
            Toast.makeText(this, "All downloaded maps are up to date", Toast.LENGTH_SHORT).show()
            return
        }
        val picks = linkedSetOf<Pair<String, Int>>()
        val zooms = (6..19).toList()
        val sizes = HashMap<Pair<String, Int>, Long>()
        val rowTotals = HashMap<String, TextView>()
        val colTotals = HashMap<Int, TextView>()
        val grand = TextView(this).apply {
            text = "Selected 0"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        }
        var cornerLabel: TextView? = null
        fun cellTotal(bytes: Long): String = if (bytes <= 0L) "0" else OfflineMaps.compactSize(bytes)
        fun refreshTotals() {
            val byRow = HashMap<String, Long>()
            val byCol = HashMap<Int, Long>()
            var all = 0L
            picks.forEach { key ->
                val bytes = sizes[key] ?: 0L
                byRow[key.first] = (byRow[key.first] ?: 0L) + bytes
                byCol[key.second] = (byCol[key.second] ?: 0L) + bytes
                all += bytes
            }
            rowTotals.forEach { (id, view) -> view.text = cellTotal(byRow[id] ?: 0L) }
            colTotals.forEach { (zoom, view) -> view.text = cellTotal(byCol[zoom] ?: 0L) }
            val label = cellTotal(all)
            grand.text = "Selected $label"
            cornerLabel?.text = label
        }
        val table = android.widget.TableLayout(this)
        val header = android.widget.TableRow(this)
        header.addView(TextView(this).apply {
            text = "Map"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 12, 0)
        })
        zooms.forEach { zoom ->
            header.addView(TextView(this).apply {
                text = "z$zoom"
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
            })
        }
        header.addView(TextView(this).apply {
            text = "Total"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(12, 0, 0, 0)
        })
        table.addView(header)
        OfflineMaps.bases.forEach { map ->
            val row = android.widget.TableRow(this)
            row.addView(TextView(this).apply {
                text = map.label
                textSize = 12f
                setPadding(0, 8, 12, 8)
            })
            zooms.forEach { zoom ->
                if (zoom > map.maxZoom) {
                    row.addView(TextView(this).apply {
                        text = "N/A"
                        textSize = 11f
                        gravity = android.view.Gravity.CENTER
                        setTextColor(Color.parseColor("#757575"))
                    })
                } else if (OfflineMaps.hasPiece(prefs, state.abbr, map.id, zoom)) {
                    row.addView(TextView(this).apply {
                        text = "OK"
                        textSize = 12f
                        gravity = android.view.Gravity.CENTER
                        setTextColor(Color.parseColor("#1B7F3A"))
                    })
                } else {
                    val bytes = OfflineMaps.estimateMapZoom(state, zoom)
                    sizes[map.id to zoom] = bytes
                    row.addView(android.widget.CheckBox(this).apply {
                        text = OfflineMaps.compactSize(bytes)
                        textSize = 11f
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) picks.add(map.id to zoom) else picks.remove(map.id to zoom)
                            refreshTotals()
                        }
                    })
                }
            }
            val rowTotal = TextView(this).apply {
                text = "0"
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(12, 0, 0, 0)
            }
            rowTotals[map.id] = rowTotal
            row.addView(rowTotal)
            table.addView(row)
        }
        val footer = android.widget.TableRow(this)
        footer.addView(TextView(this).apply {
            text = "Total"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 8, 12, 0)
        })
        zooms.forEach { zoom ->
            val colTotal = TextView(this).apply {
                text = "0"
                textSize = 11f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
            }
            colTotals[zoom] = colTotal
            footer.addView(colTotal)
        }
        val corner = TextView(this).apply {
            text = "0"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(12, 8, 0, 0)
        }
        cornerLabel = corner
        footer.addView(corner)
        table.addView(footer)
        val scroller = android.widget.HorizontalScrollView(this).apply { addView(table) }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(grand)
            addView(scroller)
        }
        AlertDialog.Builder(this)
            .setTitle(state.name)
            .setMessage("Select each map and zoom. N/A means that map does not supply that zoom.")
            .setView(body)
            .setPositiveButton("Download") { _, _ ->
                val chosen = picks.map { OfflineMaps.MapPick(state, it.first, it.second) }
                confirmMeteredDownload(chosen, force, onLine)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmMeteredDownload(picks: List<OfflineMaps.MapPick>, force: Boolean, onLine: (String) -> Unit) {
        if (picks.isEmpty()) {
            Toast.makeText(this, "Select a map", Toast.LENGTH_SHORT).show()
            return
        }
        val go = {
            onLine("Starting download…")
            val progressQueued = java.util.concurrent.atomic.AtomicBoolean(false)
            OfflineMaps.enqueue(this, prefs, picks, force) { line ->
                val structural = line == "queued" || line.endsWith("started") ||
                    line.endsWith("downloaded") || line.endsWith("cancelled") || line.contains("failed")
                if (!structural) {
                    if (!progressQueued.compareAndSet(false, true)) return@enqueue
                    runOnUiThread {
                        progressQueued.set(false)
                        if (isDestroyed) return@runOnUiThread
                        onLine(line)
                    }
                } else {
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        onLine(line)
                    }
                }
            }
        }
        Thread {
            val metered = try {
                getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered == true
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (metered) {
                    AlertDialog.Builder(this)
                        .setTitle("Metered connection")
                        .setMessage("This connection is metered. Download offline maps anyway?")
                        .setPositiveButton("Download") { _, _ -> go() }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    go()
                }
            }
        }.start()
    }

    private fun pinZoomControls(dialog: AlertDialog) {
        if (dialog.isShowing) return
    }

    private fun holdZoom(key: View, delta: Double) {
        var repeating = false
        val repeat = object : Runnable {
            override fun run() {
                if (!repeating) return
                stepZoom(delta)
                key.postDelayed(this, 70)
            }
        }
        key.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    zoomControlActive = true
                    repeating = true
                    key.isPressed = true
                    stepZoom(delta)
                    key.postDelayed(repeat, 280)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    zoomControlActive = false
                    repeating = false
                    key.isPressed = false
                    key.removeCallbacks(repeat)
                    reloadHighestTiles()
                    true
                }
                else -> false
            }
        }
    }

    private fun stepZoom(delta: Double) {
        if (!::map.isInitialized) return
        keepFollow {
            map.controller.setZoom(map.zoomLevelDouble + delta)
        }
    }

    private fun zoomAroundFollow(level: Double) {
        map.controller.stopAnimation(false)
        map.controller.setZoom(level.coerceIn(6.0, 19.0))
    }

    private fun keepFollow(changeZoom: () -> Unit) {
        val follow = mapFollow
        val tracked = trackedUser
        val all = allMode
        val gesture = zoomControlActive
        zoomControlActive = true
        changeZoom()
        mapFollow = follow
        trackedUser = tracked
        allMode = all
        zoomControlActive = gesture
        recenterFollow()
    }

    private fun recenterFollow() {
        if (!::map.isInitialized || mapFollow != MapFollow.USER) return
        val name = trackedUser ?: return
        val point = lastPoints[name] ?: if (isSelf(name)) ownLastPoint() else null
        if (point == null) return
        centerAboveBanner(point)
    }

    private fun mapBottomInset(): Int {
        if (!::sessionBanner.isInitialized) return 0
        fun shown(view: android.view.View) = if (view.visibility == View.VISIBLE) view.height else 0
        return shown(sessionBanner) + shown(tvMapBanner) + shown(noSessionBanner) + shown(loginBanner)
    }

    private fun centerAboveBanner(point: GeoPoint) {
        map.controller.stopAnimation(false)
        map.controller.setCenter(point)
        val lift = mapBottomInset() / 2
        if (lift > 0) map.controller.scrollBy(0, lift)
    }

    private fun reloadHighestTiles() {
        if (!::map.isInitialized) return
        OfflineMaps.scaleOnly = false
        OfflineMaps.tileEpoch++
        map.setUseDataConnection(true)
        map.tileProvider.clearTileCache()
        map.invalidate()
        map.postDelayed({
            if (OfflineMaps.scaleOnly || !::map.isInitialized) return@postDelayed
            map.tileProvider.clearTileCache()
            map.invalidate()
        }, 400)
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
        var selectedMode = currentPositionMode().let { mode ->
            if (mode == "quiet") prefs.getString("share_mode", "move")?.takeIf { it.isNotBlank() && it != "quiet" } ?: "move" else mode
        }
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
                    logSpoof(checked)
                    if (checked) addSpoofMarker() else removeSpoofMarker()
                    refreshModeButton()
                    if (!prefs.getString("url", "").isNullOrBlank()) startTrackingFromPrefs()
                    if (checked) sendSpoofNow()
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
        listOf(
            "Auto starts in Move. Under 0.4 m/s with no recent steps is Low power. From 0.4 to 2.5 m/s, or steps, is Walk. 2.5 m/s or faster is Move. Still to Walk and Walk to Move are immediate. Other changes wait 60 seconds. Battery saver, or a battery under 5 percent, forces Low power.",
            "Manual sends only when you tap Send now.",
            "Low power sends when a short GPS check shows about 300 meters of movement.",
            "Walk sends after about 30 meters.",
            "Move sends the current position every 5 seconds.",
            "Rapid sends every second."
        ).forEach { line ->
            box.addView(TextView(this).apply {
                text = "\u2022 $line"
                setPadding(0, pad / 8, 0, pad / 8)
            })
        }
        box.addView(modeList)
        if (superuser) {
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, pad, 0, 0)
                addView(spoofIcon)
                addView(spoof)
            })
            box.addView(lat)
            box.addView(lon)
        }
        val durations = SharingControl.DURATIONS
        val durationLabels = durations.map { SharingControl.durationLabel(it) }
        var durationAdjusting = false
        val remainingView = TextView(this).apply {
            text = "Timer off"
            setPadding(0, pad / 2, 0, pad / 2)
        }
        val showRemaining = {
            val left = SharingControl.remaining(this)
            val until = SharingControl.untilClock(this)
            remainingView.text = if (left > 0L) {
                val clock = if (until.isBlank()) "" else ", until $until"
                "Time left ${SharingControl.countdownLabel(left)}$clock"
            } else {
                "Timer off"
            }
        }
        val durationSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                durationLabels
            )
            val index = durations.indexOf(SharingControl.savedDuration(this@MainActivity)).coerceAtLeast(0)
            setSelection(index)
            layoutParams = width
        }
        durationAdjusting = true
        durationSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putLong("share_timer_ms", durations[position]).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        durationSpinner.post { durationAdjusting = false }
        showRemaining()
        box.addView(TextView(this).apply {
            text = "Timer"
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, pad, 0, pad / 4)
        })
        box.addView(TextView(this).apply {
            text = "Pick a duration, then press Reset to start it. Opening this page does not change a running timer. Turning sharing on or off does not change the time left."
            setPadding(0, 0, 0, pad / 2)
        })
        box.addView(durationSpinner)
        box.addView(remainingView)
        box.addView(Button(this).apply {
            text = "Reset"
            setOnClickListener {
                SharingControl.startTimer(this@MainActivity, durations[durationSpinner.selectedItemPosition])
                showRemaining()
                refreshModeButton()
            }
        })
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
            .setTitle("Location")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save", null)
            .setNeutralButton("Send now", null)
            .setNegativeButton("Cancel", null)
            .create()
        refreshLocationPage = {
            selectedMode = currentPositionMode()
            renderModes()
            spoofAdjusting = true
            spoof.isChecked = prefs.getBoolean("spoof_enabled", false)
            spoofAdjusting = false
            durationAdjusting = true
            durationSpinner.setSelection(durations.indexOf(SharingControl.savedDuration(this)).coerceAtLeast(0))
            durationSpinner.post { durationAdjusting = false }
            showRemaining()
        }
        val shareClock = android.os.Handler(android.os.Looper.getMainLooper())
        val tickShareClock = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                SharingControl.expireIfDue(this@MainActivity)
                showRemaining()
                shareClock.postDelayed(this, 1000L)
            }
        }
        dialog.setOnDismissListener {
            shareClock.removeCallbacks(tickShareClock)
            refreshLocationPage = null
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.WHITE))
            pinZoomControls(dialog)
            shareClock.removeCallbacks(tickShareClock)
            shareClock.post(tickShareClock)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val previousMode = currentPositionMode()
                val previousSpoof = prefs.getBoolean("spoof_enabled", false)
                val edit = prefs.edit()
                    .putString("position_mode", selectedMode)
                    .putBoolean("spoof_enabled", spoof.isChecked)
                    .putString("spoof_lat", lat.text.toString().trim())
                    .putString("spoof_lon", lon.text.toString().trim())
                    .putString("share_mode", selectedMode)
                edit.apply()
                LogManager.modeChange("Position", modeLabel(previousMode), modeLabel(selectedMode))
                LogManager.modeChange("Spoof", if (previousSpoof) "On" else "Off", if (spoof.isChecked) "On" else "Off")
                dialog.dismiss()
                refreshModeButton()
                if (!spoof.isChecked) removeSpoofMarker() else addSpoofMarker()
                if (!prefs.getString("url", "").isNullOrBlank()) startTrackingFromPrefs()
                if (spoof.isChecked) sendSpoofNow()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val previousSpoof = prefs.getBoolean("spoof_enabled", false)
                prefs.edit()
                    .putBoolean("spoof_enabled", spoof.isChecked)
                    .putString("spoof_lat", lat.text.toString().trim())
                    .putString("spoof_lon", lon.text.toString().trim())
                    .apply()
                LogManager.modeChange("Spoof", if (previousSpoof) "On" else "Off", if (spoof.isChecked) "On" else "Off")
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

    private val positionModes = listOf("auto", "manual", "significant", "walk", "move", "rapid")

    private fun currentPositionMode(): String = prefs.getString("position_mode", "move") ?: "move"

    private fun modeLabel(mode: String): String = when (mode) {
        "quiet" -> "Off"
        "auto" -> "Auto"
        "manual" -> "Manual"
        "significant" -> "Low power"
        "walk" -> "Walk"
        "rapid" -> "Rapid"
        "spoof" -> "Spoof"
        "timer" -> "Timer"
        "t2" -> "2 hours"
        "t6" -> "6 hours"
        "t1d" -> "1 day"
        "t4d" -> "4 days"
        "on" -> "On"
        else -> "Move"
    }

    private fun modeIcon(mode: String): Int = when (mode) {
        "quiet" -> R.drawable.ic_mode_off
        "auto" -> R.drawable.ic_mode_auto
        "manual" -> R.drawable.ic_mode_manual
        "significant" -> R.drawable.ic_mode_battery
        "walk" -> R.drawable.ic_mode_stick
        "rapid" -> R.drawable.ic_mode_rocket
        "spoof" -> R.drawable.ic_mode_spy
        "timer", "t2", "t6", "t1d", "t4d" -> R.drawable.ic_mode_timer
        "on" -> R.drawable.ic_mode_broadcast
        else -> R.drawable.ic_mode_car
    }

    private fun refreshModeButton() {
        if (!::btnPositionMode.isInitialized) return
        val spoofing = prefs.getBoolean("spoof_enabled", false)
        val sharingOn = !positionUpdatesOff()
        val timing = SharingControl.remaining(this) > 0L
        btnPositionMode.setImageResource(if (spoofing) R.drawable.ic_mode_spy else modeIcon(currentPositionMode()))
        btnPositionMode.contentDescription = if (spoofing) "Location spoof" else modeLabel(currentPositionMode())
        if (::btnSharing.isInitialized) {
            btnSharing.setImageResource(
                when {
                    !sharingOn -> R.drawable.ic_mode_off
                    timing -> R.drawable.ic_mode_timer
                    else -> R.drawable.ic_mode_broadcast
                }
            )
            btnSharing.contentDescription = when {
                !sharingOn -> "Sharing off"
                timing -> "Timer ${SharingControl.remainingLabel(SharingControl.remaining(this))}"
                else -> "Sharing on"
            }
            btnSharing.invalidate()
        }
        btnPositionMode.invalidate()
        refreshShareModeLabel()
    }

    private fun refreshShareModeLabel() {
        val label = findViewById<TextView>(R.id.tvShareMode)
        label.visibility = if (superuser) View.VISIBLE else View.GONE
        if (!superuser) return
        val text = when {
            !SharingControl.isOn(this) -> "Off"
            SharingControl.remaining(this) > 0L -> SharingControl.durationLabel(
                prefs.getLong("share_timer_ms", SharingControl.DEFAULT_MS)
            )
            else -> "On"
        }
        label.text = text
        label.bringToFront()
        val position = findViewById<TextView>(R.id.tvPositionMode)
        position.visibility = if (superuser) View.VISIBLE else View.GONE
        if (!superuser) return
        position.text = when {
            prefs.getBoolean("spoof_enabled", false) -> "Spoof"
            currentPositionMode() == "auto" -> {
                val hidden = prefs.getString("auto_effective", "move") ?: "move"
                "Auto, ${modeLabel(hidden)}"
            }
            else -> modeLabel(currentPositionMode())
        }
        position.bringToFront()
    }

    private fun showDiagnostics() {
        DiagLog.noteMode(this)
        val chart = DiagChart(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (280 * resources.displayMetrics.density).toInt())
            samples = DiagLog.samples(this@MainActivity)
        }
        val legend = TextView(this).apply {
            text = "Blue, on the left, is Android activity. Orange, on the right, is the location mode. Green is battery percent, 0 at the bottom and 100 at the top."
            setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 0)
            addView(chart)
            addView(legend)
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
            .setTitle("Position update mode")
            .setView(box)
            .setPositiveButton("Close", null)
            .create()
        val refresh = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                chart.samples = DiagLog.samples(this@MainActivity)
                chart.postDelayed(this, 5000L)
            }
        }
        dialog.setOnShowListener { chart.post(refresh) }
        dialog.setOnDismissListener { chart.removeCallbacks(refresh) }
        dialog.show()
        pinZoomControls(dialog)
    }

    private fun closeModeMenu() {
        if (::modePicker.isInitialized) modePicker.visibility = View.GONE
        openModeMenu = null
    }

    private fun showModeMenu(kind: String) {
        if (modePicker.visibility == View.VISIBLE && openModeMenu == kind) {
            closeModeMenu()
            return
        }
        val current = currentPositionMode()
        val spoofing = prefs.getBoolean("spoof_enabled", false)
        val sharingOn = !positionUpdatesOff()
        val timing = SharingControl.remaining(this) > 0L
        openModeMenu = kind
        modePicker.removeAllViews()
        if (kind == "position") {
            modePicker.addView(groupLabel("Position"))
            listOf("auto", "manual", "significant", "walk", "move", "rapid").forEach { mode ->
                modePicker.addView(modeChoice(mode, !spoofing && mode == current) {
                    closeModeMenu()
                    if (spoofing) {
                        confirmChoice("Turn off location spoof?") {
                            logSpoof(false)
                            removeSpoofMarker()
                            setPositionMode(mode)
                        }
                    } else {
                        setPositionMode(mode)
                    }
                })
            }
            if (superuser) {
                modePicker.addView(modeChoice("spoof", spoofing) {
                    closeModeMenu()
                    if (spoofing) return@modeChoice
                    confirmChoice("Turn on location spoof?") {
                        logSpoof(true)
                        refreshModeButton()
                        addSpoofMarker()
                        if (!prefs.getString("url", "").isNullOrBlank()) startTrackingFromPrefs()
                        sendSpoofNow()
                    }
                })
            }
        } else {
            modePicker.addView(groupLabel("Sharing"))
            modePicker.addView(modeChoice("quiet", !sharingOn) {
                closeModeMenu()
                SharingControl.set(this, false)
                refreshModeButton()
            })
            val chosen = SharingControl.savedDuration(this)
            listOf(
                2L * 60 * 60 * 1000 to "t2",
                6L * 60 * 60 * 1000 to "t6",
                24L * 60 * 60 * 1000 to "t1d",
                4L * 24 * 60 * 60 * 1000 to "t4d"
            ).forEach { (ms, mode) ->
                modePicker.addView(modeChoice(mode, timing && chosen == ms) {
                    closeModeMenu()
                    SharingControl.startTimer(this, ms)
                    refreshModeButton()
                    refreshMapBanner()
                })
            }
            modePicker.addView(modeChoice("on", sharingOn && !timing) {
                closeModeMenu()
                SharingControl.set(this, true, clearTimer = true)
                refreshModeButton()
            })
        }
        modePicker.visibility = View.VISIBLE
        modePicker.bringToFront()
        btnPositionMode.bringToFront()
        btnSharing.bringToFront()
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

    private fun ownsSession(session: JSONObject): Boolean {
        val owner = session.optString("owner").trim()
        if (owner.isBlank()) return true
        val user = prefs.getString("nc_user", "")?.trim().orEmpty()
        return owner.equals(user, ignoreCase = true)
    }

    private fun sendSpoofNow() {
        val lat = prefs.getString("spoof_lat", null)?.toDoubleOrNull()
        val lon = prefs.getString("spoof_lon", null)?.toDoubleOrNull()
        if (lat == null || lon == null) return
        val url = prefs.getString("url", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        if (!isValidBaseUrl(url) || token.isBlank()) return
        val device = prefs.getString("nc_user", "")?.trim().orEmpty()
            .ifEmpty { prefs.getString("device", "Android") ?: "Android" }
        try {
            ContextCompat.startForegroundService(this, Intent(this, LocationPublishService::class.java).apply {
                action = LocationPublishService.ACTION_PUBLISH_ONCE
                putExtra("url", url)
                putExtra("token", token)
                putExtra("device", device)
            })
        } catch (e: Exception) {
            LogManager.add("Could not send spoof: ${e.message}")
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

    private fun logSpoof(on: Boolean) {
        val was = prefs.getBoolean("spoof_enabled", false)
        if (was == on) return
        prefs.edit().putBoolean("spoof_enabled", on).apply()
        LogManager.modeChange("Spoof", if (was) "On" else "Off", if (on) "On" else "Off")
    }

    private fun setPositionMode(mode: String) {
        if (mode == "quiet") return
        val previous = currentPositionMode()
        prefs.edit().putString("position_mode", mode).putString("share_mode", mode).apply()
        LogManager.modeChange("Position", modeLabel(previous), modeLabel(mode))
        LocationPublishService.syncAutoPermissionNotice(this)
        refreshModeButton()
        if (!prefs.getString("url", "").isNullOrBlank()) startTrackingFromPrefs()
    }

    private fun groupLabel(text: String): TextView {
        val pad = (10 * resources.displayMetrics.density).toInt()
        return TextView(this).apply {
            this.text = text
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(pad, pad, pad, pad / 4)
            setTextColor(Color.parseColor("#111111"))
        }
    }

    private fun ageText(timestampSec: Long): String {
        val sec = (System.currentTimeMillis() / 1000 - timestampSec).coerceAtLeast(0)
        val hours = sec / 3600
        val minutes = (sec % 3600) / 60
        return "${hours}h ${minutes}m"
    }

    private fun sendCurrentPosition() {
        if (positionUpdatesOff()) return
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

    private fun rememberOwn(lat: Double, lon: Double) {
        prefs.edit().putString("own_lat", lat.toString()).putString("own_lon", lon.toString()).apply()
    }

    private fun ownLastPoint(): GeoPoint? {
        val lat = prefs.getString("own_lat", null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString("own_lon", null)?.toDoubleOrNull() ?: return null
        return GeoPoint(lat, lon)
    }

    private fun isSelf(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val user = prefs.getString("nc_user", "")?.trim().orEmpty()
        val device = prefs.getString("device", "")?.trim().orEmpty()
        val posted = LocationPublishService.defaultPostedName(this)
        return name.equals(user, true) ||
            (device.isNotBlank() && name.equals(device, true)) ||
            name.equals(posted, true) ||
            loadSessions().any { it.optString("device").equals(name, true) }
    }

    private fun cycleFollow() {
        val targets = followTargets()
        if (targets.isEmpty()) {
            Toast.makeText(this, "unknown", Toast.LENGTH_SHORT).show()
            return
        }
        val current = if (mapFollow == MapFollow.USER) trackedUser else null
        val index = targets.indexOfFirst { name ->
            name.equals(current, true) || (current != null && isSelf(name) && isSelf(current))
        }
        val next = if (index < 0) targets.first() else targets[(index + 1) % targets.size]
        val point = lastPoints[next] ?: if (isSelf(next)) ownLastPoint() else null
        if (point == null) {
            Toast.makeText(this, "unknown", Toast.LENGTH_SHORT).show()
            return
        }
        allMode = false
        mapFollow = MapFollow.USER
        trackedUser = next
        refreshZoomKeys()
        centerAboveBanner(point)
    }

    private fun followTargets(): List<String> {
        val names = lastPoints.keys.toList()
        val self = names.firstOrNull { isSelf(it) }
            ?: run {
                val own = prefs.getString("nc_user", "")?.trim().orEmpty().ifBlank { deviceName() }
                if (ownLastPoint() != null) own else null
            }
        val others = names.filter { self == null || !it.equals(self, true) && !isSelf(it) }.sorted()
        return listOfNotNull(self) + others
    }

    private fun deviceName(): String {
        return prefs.getString("device", "")?.trim().orEmpty().ifBlank { "Android" }
    }

    private fun zoomToAll() {
        allMode = true
        mapFollow = MapFollow.GROUP
        trackedUser = null
        refreshZoomKeys()
        fitActiveUsers()
    }

    private fun fitActiveUsers(attempt: Int = 0) {
        if (!::map.isInitialized) return
        val points = lastPoints.values.toList()
        if (points.isEmpty()) return
        if (map.width < 2 || map.height < 2) {
            if (attempt < 5) map.post { fitActiveUsers(attempt + 1) }
            return
        }
        if (points.size == 1) {
            val here = map.mapCenter
            val same = kotlin.math.abs(here.latitude - points[0].latitude) < 0.00001 &&
                kotlin.math.abs(here.longitude - points[0].longitude) < 0.00001
            if (same && kotlin.math.abs(map.zoomLevelDouble - 16.0) < 0.2) return
            map.controller.setZoom(16.0)
            centerAboveBanner(points[0])
            return
        }
        val pad = (56 * resources.displayMetrics.density).toInt()
        if (pointsInWindow(points, pad)) return
        map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), false, pad)
        val lift = mapBottomInset() / 2
        if (lift > 0) map.controller.scrollBy(0, lift)
        map.post { keepPointsInWindow(points, pad, 8) }
    }

    private fun pointsInWindow(points: List<GeoPoint>, pad: Int): Boolean {
        if (map.zoomLevelDouble <= 0.0) return false
        return points.all { point ->
            val pixel = map.projection.toPixels(point, null)
            pixel.x >= pad && pixel.y >= pad && pixel.x <= map.width - pad && pixel.y <= map.height - pad - mapBottomInset()
        }
    }

    private fun keepPointsInWindow(points: List<GeoPoint>, pad: Int, steps: Int) {
        if (steps <= 0 || map.zoomLevelDouble <= 3.0) return
        val outside = points.any { point ->
            val pixel = map.projection.toPixels(point, null)
            pixel.x < pad || pixel.y < pad || pixel.x > map.width - pad || pixel.y > map.height - pad - mapBottomInset()
        }
        if (!outside) return
        map.controller.setZoom(map.zoomLevelDouble - 0.5)
        map.post { keepPointsInWindow(points, pad, steps - 1) }
    }

    private fun activateSpoof(lat: Double, lon: Double) {
        logSpoof(true)
        prefs.edit()
            .putString("spoof_lat", lat.toString())
            .putString("spoof_lon", lon.toString())
            .apply()
        enterManual()
        refreshModeButton()
        addSpoofMarker()
        map.invalidate()
        if (!prefs.getString("url", "").isNullOrBlank()) startTrackingFromPrefs()
        sendSpoofNow()
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
        if (zoomControlActive) return
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
        lastPoints[name]?.let { centerAboveBanner(it) }
    }

    private fun enterGroupView() {
        allMode = false
        mapFollow = MapFollow.GROUP
        trackedUser = null
        refreshZoomKeys()
        applyMapFollow(lastPoints.values.toList())
    }

    private fun refreshZoomKeys() {
        val mapContext = !::statusPanel.isInitialized || statusPanel.visibility != View.VISIBLE
        mapControls.visibility = if (mapContext) View.VISIBLE else View.GONE
        btnZoomIn.visibility = if (mapContext) View.VISIBLE else View.GONE
        btnZoomOut.visibility = if (mapContext) View.VISIBLE else View.GONE
        if (::zoomSlide.isInitialized) {
            zoomSlide.visibility = if (mapContext) View.VISIBLE else View.GONE
            zoomSlide.setZoom(map.zoomLevelDouble)
        }
        btnGroupView.visibility = if (mapContext) View.VISIBLE else View.GONE
        btnGroupView.alpha = if (mapFollow == MapFollow.GROUP) 0.35f else 1f
        if (::btnReticle.isInitialized) btnReticle.visibility = if (mapContext) View.VISIBLE else View.GONE
        if (::mapControls.isInitialized && mapContext) mapControls.bringToFront()
        findViewById<android.view.View>(R.id.mapBanners).bringToFront()
        if (::btnPositionMode.isInitialized && mapContext) btnPositionMode.bringToFront()
        if (::btnSharing.isInitialized && mapContext) btnSharing.bringToFront()
        if (::btnGroupView.isInitialized && mapContext) btnGroupView.bringToFront()
        if (::btnReticle.isInitialized && mapContext) btnReticle.bringToFront()
        refreshReticle()
        if (!mapContext && ::statusPanel.isInitialized && statusPanel.visibility == View.VISIBLE) statusPanel.bringToFront()
    }

    private fun refreshReticle() {
        if (!::btnReticle.isInitialized) return
        val name = trackedUser
        val following = mapFollow == MapFollow.USER && !name.isNullOrBlank()
        if (!following) {
            btnReticle.setImageResource(R.drawable.ic_reticle)
            return
        }
        val followed = name ?: return
        val color = shownColor[followed] ?: run {
            val token = prefs.getString("token", "") ?: ""
            val own = prefs.getString("device", "")?.trim().orEmpty()
                .ifBlank { prefs.getString("nc_user", "")?.trim().orEmpty() }
            val others = lastPoints.keys.filter { it != own }.sorted()
            personColor(token, followed, own, others)
        }
        btnReticle.imageTintList = null
        btnReticle.clearColorFilter()
        val size = (24 * resources.displayMetrics.density).toInt().coerceAtLeast(24)
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val base = ContextCompat.getDrawable(this, R.drawable.ic_reticle)?.mutate()
        base?.setBounds(0, 0, size, size)
        base?.draw(canvas)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawCircle(size / 2f, size / 2f, size * 0.18f, paint)
        btnReticle.setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, bitmap))
    }

    private fun applyMapFollow(points: List<GeoPoint>) {
        when (mapFollow) {
            MapFollow.MANUAL -> Unit
            MapFollow.USER -> {
                val point = lastPoints[trackedUser] ?: if (isSelf(trackedUser)) ownLastPoint() else null
                point?.let { centerAboveBanner(it) }
            }
            MapFollow.GROUP -> fitActiveUsers()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        LocationPublishService.syncAutoPermissionNotice(this)
        refreshModeButton()
        refreshMapBanner()
        if (logTimer == null) startLogTimer()
        refreshSettingsPermissions?.invoke()
        val url = prefs.getString("url", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        if (fetchTimer == null && url.isNotBlank() && token.isNotBlank() && activeSessionEnabled() &&
            (checkPermissions() || positionUpdatesOff())
        ) {
            startTrackingFromPrefs()
        }
    }
    override fun onPause() { super.onPause(); map.onPause(); stopLogTimer() }
    private val sharingChanged = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            if (isFinishing || isDestroyed) return
            refreshModeButton()
            refreshMapBanner()
            if (intent?.action != SharingControl.ACTION_TIMER_TICK) startTrackingFromPrefs()
            refreshSettingsSharing?.invoke()
            refreshLocationPage?.invoke()
        }
    }

    private fun lastSeenLabel(timestampSec: Long): String {
        if (timestampSec <= 0L) return ""
        val millis = timestampSec * 1000
        val age = System.currentTimeMillis() - millis
        val day = 24L * 60 * 60 * 1000
        val year = 365L * day
        val pattern = when {
            age < day -> "h:mm a"
            age < year -> "MMM d"
            else -> "yyyy"
        }
        return android.text.format.DateFormat.format(pattern, millis).toString()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::map.isInitialized) map.invalidate()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(sharingChanged)
        } catch (_: Exception) {
        }
        if (::map.isInitialized) map.onDetach()
        fetchTimer?.cancel()
        fetchTimer = null
        super.onDestroy()
    }
}

private enum class MapFollow { GROUP, USER, MANUAL }

private data class FriendLoad(
    val points: Map<String, GeoPoint>,
    val seen: Map<String, Long>,
    val reachable: Boolean
)
