package com.example.ncphonetrack

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
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
    private val LOCATION_PERMISSION_REQUEST = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        drawerLayout = findViewById(R.id.drawer_layout)
        etUrl = findViewById(R.id.etUrl)
        etToken = findViewById(R.id.etToken)
        etDevice = findViewById(R.id.etDevice)
        tvStatus = findViewById(R.id.tvStatus)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val navView: NavigationView = findViewById(R.id.nav_view)

        etUrl.setText(prefs.getString("url", "https://"))
        etToken.setText(prefs.getString("token", ""))
        etDevice.setText(prefs.getString("device", android.os.Build.MODEL))

        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0)

        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_status -> {
                    updateStatusText("Connection Status: Logging active if configured")
                    drawerLayout.closeDrawers()
                }
                R.id.nav_sessions -> {
                    showSessions()
                    drawerLayout.closeDrawers()
                }
                R.id.nav_about -> {
                    Toast.makeText(this, "Nextcloud PhoneTrack\nRev 3\nLocation sharing via Nextcloud PhoneTrack", Toast.LENGTH_LONG).show()
                    drawerLayout.closeDrawers()
                }
                R.id.nav_map -> {
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

    private fun updateStatusText(msg: String) {
        tvStatus.text = msg
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
        val names = sessions.mapIndexed { i, s -> "${s.getString("device")} @ ${s.getString("url")}" }
        Toast.makeText(this, if (names.isEmpty()) "No sessions" else names.joinToString("\n"), Toast.LENGTH_LONG).show()
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
    override fun onPause() { super.onPause(); map.onPause() }
}
