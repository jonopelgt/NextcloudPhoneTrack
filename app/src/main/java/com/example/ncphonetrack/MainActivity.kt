package com.example.ncphonetrack

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
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
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
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
    private lateinit var tvStatus: TextView
    private lateinit var prefs: SharedPreferences
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var formPanel: LinearLayout
    private lateinit var statusPanel: LinearLayout
    private lateinit var tvStatusUrl: TextView
    private lateinit var tvStatusToken: TextView
    private lateinit var tvLog: TextView
    private val LOCATION_PERMISSION_REQUEST = 1001
    private var logTimer: Timer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Avoid OSM block on com.example.* User-Agents
        Configuration.getInstance().userAgentValue = "NextcloudPhoneTrack/5 (cyberchilli LLC)"
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        drawerLayout = findViewById(R.id.drawer_layout)
        formPanel = findViewById(R.id.form_panel)
        statusPanel = findViewById(R.id.status_panel)
        etUrl = findViewById(R.id.etUrl)
        etToken = findViewById(R.id.etToken)
        etDevice = findViewById(R.id.etDevice)
        tvStatus = findViewById(R.id.tvStatus)
        tvStatusUrl = findViewById(R.id.tvStatusUrl)
        tvStatusToken = findViewById(R.id.tvStatusToken)
        tvLog = findViewById(R.id.tvLog)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnMenu = findViewById<Button>(R.id.btnMenu)
        val navView: NavigationView = findViewById(R.id.nav_view)

        etUrl.setText(prefs.getString("url", "https://"))
        etToken.setText(prefs.getString("token", ""))
        etDevice.setText(prefs.getString("device", android.os.Build.MODEL))

        map = findViewById(R.id.map)
        // Custom HTTPS Mapnik source to avoid HTTP blocks
        val httpsMapnik = object : OnlineTileSourceBase("MapnikHTTPS", 0, 19, 256, ".png", arrayOf("https://tile.openstreetmap.org/")) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                return baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex) + "/" + MapTileIndex.getY(pMapTileIndex) + imageFilenameEnding
            }
        }
        map.setTileSource(httpsMapnik)
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0)

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
                    AlertDialog.Builder(this)
                        .setTitle("About Nextcloud PhoneTrack")
                        .setMessage("cyberchilli LLC\nRevision 5\nLocation sharing via Nextcloud PhoneTrack")
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
            if (url.isBlank() || token.isBlank()) {
                Toast.makeText(this, "URL and token required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit()
                .putString("url", url)
                .putString("token", token)
                .putString("device", device)
                .apply()
            saveSession(url, token, device)
            formPanel.visibility = View.GONE
            LogManager.add("Configured URL=$url token=$token device=$device")
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
        val sessions = loadSessions().toMutableList()
        val obj = JSONObject().apply {
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
        if (sessions.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Saved Sessions")
                .setMessage("No sessions saved yet.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val items = sessions.map { "${it.getString("device")} @ ${it.getString("url")}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Saved Sessions")
            .setItems(items) { _, which ->
                val s = sessions[which]
                etUrl.setText(s.getString("url"))
                etToken.setText(s.getString("token"))
                etDevice.setText(s.getString("device"))
                formPanel.visibility = View.VISIBLE
                statusPanel.visibility = View.GONE
                Toast.makeText(this, "Loaded session", Toast.LENGTH_SHORT).show()
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
            val positions = PhoneTrackApi.getLastPositions(url, token)
            runOnUiThread {
                map.overlays.clear()
                positions?.forEach { (name, data) ->
                    val point = GeoPoint(data.lat, data.lon)
                    val marker = Marker(map)
                    marker.position = point
                    marker.title = name
                    marker.snippet = "Acc: ${data.accuracy}m | Age: ${(System.currentTimeMillis()/1000 - data.timestamp)}s"
                    map.overlays.add(marker)
                    if (name == ownDevice) map.controller.setCenter(point)
                }
                map.invalidate()
                updateStatusText("Map updated: ${positions?.size ?: 0} devices")
            }
        }.start()
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause(); stopLogTimer() }
}
