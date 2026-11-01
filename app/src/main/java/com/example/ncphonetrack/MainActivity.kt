package com.example.ncphonetrack

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
    private lateinit var etSessionName: EditText
    private lateinit var etUrl: EditText
    private lateinit var etToken: EditText
    private lateinit var etDevice: EditText
    private lateinit var tvStatus: TextView
    private lateinit var prefs: SharedPreferences
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var formPanel: LinearLayout
    private lateinit var statusPanel: LinearLayout
    private lateinit var tvStatusUrl: TextView
    private lateinit var tvStatusToken: TextView
    private lateinit var tvCertStatus: TextView
    private lateinit var btnProbeCert: Button
    private lateinit var btnAcceptCert: Button
    private lateinit var tvLog: TextView
    private val LOCATION_PERMISSION_REQUEST = 1001
    private var logTimer: Timer? = null
    private var autoCenter = true
    private var fitAll = false
    private var pendingCertHash: String? = null

    private fun getAppVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) { "unknown" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rev = getAppVersion()
        Configuration.getInstance().userAgentValue = "NextcloudPhoneTrack/$rev (cyberchilli LLC)"
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        drawerLayout = findViewById(R.id.drawer_layout)
        formPanel = findViewById(R.id.form_panel)
        statusPanel = findViewById(R.id.status_panel)
        etSessionName = findViewById(R.id.etSessionName)
        etUrl = findViewById(R.id.etUrl)
        etToken = findViewById(R.id.etToken)
        etDevice = findViewById(R.id.etDevice)
        tvStatus = findViewById(R.id.tvStatus)
        tvStatusUrl = findViewById(R.id.tvStatusUrl)
        tvStatusToken = findViewById(R.id.tvStatusToken)
        tvCertStatus = findViewById(R.id.tvCertStatus)
        btnProbeCert = findViewById(R.id.btnProbeCert)
        btnAcceptCert = findViewById(R.id.btnAcceptCert)
        tvLog = findViewById(R.id.tvLog)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnMenu = findViewById<Button>(R.id.btnMenu)
        val navView: NavigationView = findViewById(R.id.nav_view)

        etSessionName.setText(prefs.getString("session_name", ""))
        etUrl.setText(prefs.getString("url", "https://"))
        etToken.setText(prefs.getString("token", ""))
        etDevice.setText(prefs.getString("device", android.os.Build.MODEL))

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
        map.controller.setCenter(GeoPoint(42.7654, -71.4676)) // Nashua, NH

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

        btnProbeCert.setOnClickListener {
            val url = prefs.getString("url", "") ?: ""
            if (url.isBlank()) {
                Toast.makeText(this, "Set URL first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            tvCertStatus.text = "Cert: Probing..."
            CertHelper.probeCertHash(url) { hash ->
                runOnUiThread {
                    pendingCertHash = hash
                    if (hash != null) {
                        val accepted = CertHelper.getAcceptedHashes(prefs)
                        val status = if (hash in accepted) "Accepted" else "Untrusted"
                        tvCertStatus.text = "Cert: $status ($hash)"
                        btnAcceptCert.isEnabled = hash !in accepted
                    } else {
                        tvCertStatus.text = "Cert: Probe failed"
                        btnAcceptCert.isEnabled = false
                    }
                }
            }
        }

        btnAcceptCert.setOnClickListener {
            pendingCertHash?.let { hash ->
                CertHelper.addAcceptedHash(prefs, hash)
                tvCertStatus.text = "Cert: Accepted ($hash)"
                btnAcceptCert.isEnabled = false
                Toast.makeText(this, "Certificate accepted", Toast.LENGTH_SHORT).show()
            }
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
                    val version = getAppVersion()
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
            val name = etSessionName.text.toString().trim().ifEmpty { "Unnamed" }
            val url = etUrl.text.toString().trim().removeSuffix("/")
            val token = etToken.text.toString().trim()
            val device = etDevice.text.toString().trim().ifEmpty { "Android" }
            if (url.isBlank() || token.isBlank()) {
                Toast.makeText(this, "URL and token required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit()
                .putString("session_name", name)
                .putString("url", url)
                .putString("token", token)
                .putString("device", device)
                .apply()
            saveSession(name, url, token, device)
            formPanel.visibility = View.GONE
            LogManager.add("Configured name=$name URL=$url token=$token device=$device")
            if (checkPermissions()) {
                startLocationService(url, token, device)
                startFetchingPositions(url, token, device)
                updateStatusText("Tracking started")
            } else {
                requestPermissions()
            }
        }

        val savedUrl = prefs.getString("url", null)
        val savedToken = prefs.getString("token", null)
        if (!savedUrl.isNullOrBlank() && !savedToken.isNullOrBlank() && checkPermissions()) {
            val device = prefs.getString("device", "Android")!!
            startLocationService(savedUrl, savedToken, device)
            startFetchingPositions(savedUrl, savedToken, device)
            updateStatusText("Auto-started tracking")
        }
    }

    private fun showStatusPanel() {
        formPanel.visibility = View.GONE
        statusPanel.visibility = View.VISIBLE
        val url = prefs.getString("url", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        tvStatusUrl.text = "URL: $url"
        tvStatusToken.text = "Token: $token"
        val accepted = CertHelper.getAcceptedHashes(prefs)
        tvCertStatus.text = if (accepted.isEmpty()) "Cert: None accepted" else "Cert: ${accepted.size} accepted"
        btnAcceptCert.isEnabled = false
        pendingCertHash = null
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

    private fun saveSession(name: String, url: String, token: String, device: String) {
        val sessions = loadSessions().toMutableList()
        val obj = JSONObject().apply {
            put("name", name)
            put("url", url)
            put("token", token)
            put("device", device)
        }
        sessions.add(obj)
        prefs.edit().putString("sessions", JSONArray(sessions).toString()).apply()
    }

    private fun loadSessions(): List<JSONObject> {
        val raw = prefs.getString("sessions", "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun showSessions() {
        val sessions = loadSessions()
        val items = if (sessions.isEmpty()) arrayOf("No sessions yet") else sessions.map { 
            val name = it.optString("name", it.optString("device", "Unnamed"))
            "$name @ ${it.getString("url")}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Session Configs")
            .setItems(items) { _, which ->
                if (sessions.isNotEmpty()) {
                    val s = sessions[which]
                    etSessionName.setText(s.optString("name", ""))
                    etUrl.setText(s.getString("url"))
                    etToken.setText(s.getString("token"))
                    etDevice.setText(s.getString("device"))
                    formPanel.visibility = View.VISIBLE
                    statusPanel.visibility = View.GONE
                    Toast.makeText(this, "Opened session config", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Add New") { _, _ ->
                etSessionName.setText("")
                etUrl.setText("https://")
                etToken.setText("")
                etDevice.setText(android.os.Build.MODEL)
                formPanel.visibility = View.VISIBLE
                statusPanel.visibility = View.GONE
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun checkPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            LOCATION_PERMISSION_REQUEST
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            val url = prefs.getString("url", "")!!
            val token = prefs.getString("token", "")!!
            val device = prefs.getString("device", "Android")!!
            if (url.isNotBlank() && token.isNotBlank()) {
                startLocationService(url, token, device)
                startFetchingPositions(url, token, device)
                updateStatusText("Tracking started after permission")
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
        val timer = Timer()
        timer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                runOnUiThread { updateMapWithPositions(url, token, device) }
            }
        }, 0, 30000)
    }

    private fun updateMapWithPositions(url: String, token: String, ownDevice: String) {
        Thread {
            val positions = PhoneTrackApi.getLastPositions(this, url, token)
            runOnUiThread {
                map.overlays.clear()
                val points = mutableListOf<GeoPoint>()
                var ownPoint: GeoPoint? = null
                positions?.forEach { (name, data) ->
                    val point = GeoPoint(data.lat, data.lon)
                    points.add(point)
                    val marker = Marker(map)
                    marker.position = point
                    marker.title = name
                    marker.snippet = "Acc: ${data.accuracy}m | Age: ${(System.currentTimeMillis()/1000 - data.timestamp)}s"
                    map.overlays.add(marker)
                    if (name == ownDevice) ownPoint = point
                }
                if (autoCenter && ownPoint != null) {
                    map.controller.setCenter(ownPoint)
                } else if (fitAll && points.isNotEmpty()) {
                    val bb = BoundingBox.fromGeoPoints(points)
                    map.zoomToBoundingBox(bb, false)
                }
                map.invalidate()
                updateStatusText("Map updated: ${positions?.size ?: 0} devices")
            }
        }.start()
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause(); stopLogTimer() }
}
