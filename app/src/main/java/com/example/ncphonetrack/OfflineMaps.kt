package com.example.ncphonetrack

import android.content.Context
import android.content.SharedPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

data class UsState(
    val name: String,
    val abbr: String,
    val north: Double,
    val south: Double,
    val west: Double,
    val east: Double
)

data class BaseMap(
    val id: String,
    val label: String,
    val ext: String,
    val arcgis: Boolean,
    val url: (Int, Int, Int) -> String
)

object OfflineMaps {
    const val FRESH_MS = 30L * 24 * 60 * 60 * 1000
    private const val MIN_ZOOM = 6
    private const val MAX_ZOOM = 12
    private const val MAX_TILES_PER_ZOOM = 4000

    val bases = listOf(
        BaseMap("organic", "Organic Maps", ".png", false) { z, x, y ->
            "https://tile.openstreetmap.org/$z/$x/$y.png"
        },
        BaseMap("osmand", "OsmAnd", ".png", false) { z, x, y ->
            "https://tile.osmand.net/hd/$z/$x/$y.png"
        },
        BaseMap("magic", "Magic Earth", ".png", false) { z, x, y ->
            "https://basemaps.cartocdn.com/rastertiles/voyager/$z/$x/$y.png"
        },
        BaseMap("openandro", "OpenAndroMaps", ".png", false) { z, x, y ->
            "https://tile.opentopomap.org/$z/$x/$y.png"
        },
        BaseMap("usgs", "USGS topo", ".jpg", true) { z, x, y ->
            "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/$z/$y/$x"
        }
    )

    val states = listOf(
        UsState("Alabama", "AL", 35.01, 30.14, -88.47, -84.89),
        UsState("Alaska", "AK", 71.39, 51.21, -179.15, -129.98),
        UsState("Arizona", "AZ", 37.00, 31.33, -114.82, -109.04),
        UsState("Arkansas", "AR", 36.50, 33.00, -94.62, -89.64),
        UsState("California", "CA", 42.01, 32.53, -124.48, -114.13),
        UsState("Colorado", "CO", 41.00, 36.99, -109.06, -102.04),
        UsState("Connecticut", "CT", 42.05, 40.98, -73.73, -71.79),
        UsState("Delaware", "DE", 39.84, 38.45, -75.79, -75.05),
        UsState("District of Columbia", "DC", 38.99, 38.79, -77.12, -76.91),
        UsState("Florida", "FL", 31.00, 24.40, -87.63, -80.03),
        UsState("Georgia", "GA", 35.00, 30.36, -85.61, -80.84),
        UsState("Hawaii", "HI", 22.24, 18.91, -160.25, -154.81),
        UsState("Idaho", "ID", 49.00, 41.99, -117.24, -111.04),
        UsState("Illinois", "IL", 42.51, 36.97, -91.51, -87.50),
        UsState("Indiana", "IN", 41.76, 37.77, -88.10, -84.78),
        UsState("Iowa", "IA", 43.50, 40.38, -96.64, -90.14),
        UsState("Kansas", "KS", 40.00, 36.99, -102.05, -94.59),
        UsState("Kentucky", "KY", 39.15, 36.50, -89.57, -81.96),
        UsState("Louisiana", "LA", 33.02, 28.93, -94.04, -88.82),
        UsState("Maine", "ME", 47.46, 42.97, -71.08, -66.95),
        UsState("Maryland", "MD", 39.72, 37.91, -79.49, -75.05),
        UsState("Massachusetts", "MA", 42.89, 41.24, -73.51, -69.93),
        UsState("Michigan", "MI", 48.30, 41.70, -90.42, -82.41),
        UsState("Minnesota", "MN", 49.38, 43.50, -97.24, -89.49),
        UsState("Mississippi", "MS", 35.00, 30.17, -91.66, -88.10),
        UsState("Missouri", "MO", 40.61, 35.99, -95.77, -89.10),
        UsState("Montana", "MT", 49.00, 44.36, -116.05, -104.04),
        UsState("Nebraska", "NE", 43.00, 39.99, -104.05, -95.31),
        UsState("Nevada", "NV", 42.00, 35.00, -120.01, -114.04),
        UsState("New Hampshire", "NH", 45.31, 42.70, -72.56, -70.61),
        UsState("New Jersey", "NJ", 41.36, 38.93, -75.56, -73.89),
        UsState("New Mexico", "NM", 37.00, 31.33, -109.05, -103.00),
        UsState("New York", "NY", 45.02, 40.50, -79.76, -71.86),
        UsState("North Carolina", "NC", 36.59, 33.84, -84.32, -75.46),
        UsState("North Dakota", "ND", 49.00, 45.94, -104.05, -96.55),
        UsState("Ohio", "OH", 42.33, 38.40, -84.82, -80.52),
        UsState("Oklahoma", "OK", 37.00, 33.62, -103.00, -94.43),
        UsState("Oregon", "OR", 46.29, 41.99, -124.57, -116.46),
        UsState("Pennsylvania", "PA", 42.27, 39.72, -80.52, -74.69),
        UsState("Rhode Island", "RI", 42.02, 41.15, -71.86, -71.12),
        UsState("South Carolina", "SC", 35.22, 32.05, -83.35, -78.54),
        UsState("South Dakota", "SD", 45.95, 42.48, -104.06, -96.44),
        UsState("Tennessee", "TN", 36.68, 34.98, -90.31, -81.65),
        UsState("Texas", "TX", 36.50, 25.84, -106.65, -93.51),
        UsState("Utah", "UT", 42.00, 36.99, -114.05, -109.04),
        UsState("Vermont", "VT", 45.02, 42.73, -73.44, -71.46),
        UsState("Virginia", "VA", 39.47, 36.54, -83.68, -75.24),
        UsState("Washington", "WA", 49.00, 45.54, -124.77, -116.92),
        UsState("West Virginia", "WV", 40.64, 37.20, -82.64, -77.72),
        UsState("Wisconsin", "WI", 47.08, 42.49, -92.89, -86.25),
        UsState("Wyoming", "WY", 45.01, 40.99, -111.06, -104.05)
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun base(id: String): BaseMap = bases.firstOrNull { it.id == id } ?: bases.first()

    fun tileSource(map: BaseMap): OnlineTileSourceBase {
        return object : OnlineTileSourceBase(map.id, 0, 19, 256, map.ext, arrayOf("https://localhost/")) {
            override fun getTileURLString(index: Long): String {
                return map.url(MapTileIndex.getZoom(index), MapTileIndex.getX(index), MapTileIndex.getY(index))
            }
        }
    }

    fun stateAt(lat: Double, lon: Double): UsState? {
        return states.filter { state ->
            lat <= state.north && lat >= state.south && lon >= state.west && lon <= state.east
        }.minByOrNull { (it.north - it.south) * (it.east - it.west) }
    }

    fun search(query: String): List<UsState> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return states.filter {
            it.name.contains(q, true) || it.abbr.equals(q, true)
        }.take(8)
    }

    fun downloaded(prefs: SharedPreferences): List<Pair<UsState, Long>> {
        val root = catalog(prefs)
        return states.mapNotNull { state ->
            val at = root.optJSONObject(state.abbr)?.optLong("at", 0L) ?: 0L
            if (at <= 0L) null else state to at
        }
    }

    fun stale(prefs: SharedPreferences): List<UsState> {
        val now = System.currentTimeMillis()
        return downloaded(prefs).filter { now - it.second > FRESH_MS }.map { it.first }
    }

    fun isFresh(at: Long): Boolean = System.currentTimeMillis() - at <= FRESH_MS

    data class MapJob(
        val abbr: String,
        val name: String,
        val queued: Boolean,
        val done: Int,
        val total: Int,
        val rateBps: Long
    )

    private val pending = mutableListOf<Pair<UsState, Boolean>>()
    @Volatile private var current: MapJob? = null
    private val running = AtomicBoolean(false)
    private var byteCount = 0L
    private var rateStart = 0L

    fun rateText(bps: Long): String {
        val kb = bps / 1000.0
        return if (kb >= 1000) String.format(Locale.US, "%.1f MB/s", kb / 1000.0)
        else String.format(Locale.US, "%.0f KB/s", kb)
    }

    fun queue(): List<MapJob> = synchronized(pending) {
        val list = mutableListOf<MapJob>()
        current?.let { list.add(it) }
        pending.forEach { list.add(MapJob(it.first.abbr, it.first.name, true, 0, 0, 0L)) }
        list
    }

    fun enqueue(
        context: Context,
        prefs: SharedPreferences,
        states: List<UsState>,
        force: Boolean,
        onStatus: (String) -> Unit
    ) {
        if (states.isEmpty()) return
        val start = synchronized(pending) {
            states.forEach { state ->
                if (current?.abbr == state.abbr) return@forEach
                if (pending.any { it.first.abbr == state.abbr }) return@forEach
                pending.add(state to force)
            }
            if (running.get()) false else {
                running.set(true)
                true
            }
        }
        onStatus("queued")
        if (start) pump(context.applicationContext, prefs, onStatus)
    }

    private fun pump(context: Context, prefs: SharedPreferences, onStatus: (String) -> Unit) {
        Thread {
            try {
                while (true) {
                    val job = synchronized(pending) {
                        if (pending.isEmpty()) {
                            running.set(false)
                            if (pending.isEmpty()) null else {
                                running.set(true)
                                pending.removeAt(0)
                            }
                        } else {
                            pending.removeAt(0)
                        }
                    } ?: break
                    val total = tileTotal(job.first)
                    byteCount = 0L
                    rateStart = System.currentTimeMillis()
                    synchronized(pending) { current = MapJob(job.first.abbr, job.first.name, false, 0, total, 0L) }
                    onStatus("${job.first.name} started")
                    val error = download(context, job.first, job.second, total, onStatus)
                    synchronized(pending) { current = null }
                    if (error == null) {
                        mark(prefs, job.first)
                        onStatus("${job.first.name} downloaded")
                        LogManager.add("${job.first.name}: offline maps downloaded")
                    } else {
                        onStatus(error)
                        LogManager.add("${job.first.name}: $error")
                    }
                }
            } finally {
                synchronized(pending) {
                    if (pending.isEmpty() && current == null) running.set(false)
                }
            }
        }.start()
    }

    private fun tileTotal(state: UsState): Int {
        var total = 0
        for (zoom in zoomsFor(state)) total += tileCount(state, zoom)
        return total * bases.size
    }

    private fun zoomsFor(state: UsState): List<Int> {
        val zooms = mutableListOf<Int>()
        var zoom = MIN_ZOOM
        while (zoom <= MAX_ZOOM) {
            val count = tileCount(state, zoom)
            if (zoom > MIN_ZOOM && count > MAX_TILES_PER_ZOOM) break
            zooms.add(zoom)
            zoom++
        }
        return zooms
    }

    private fun download(
        context: Context,
        state: UsState,
        force: Boolean,
        total: Int,
        onStatus: (String) -> Unit
    ): String? {
        var attempted = 0
        var failed = 0
        for (map in bases) {
            for (zoom in zoomsFor(state)) {
                val tiles = tilesAt(state, zoom)
                for ((x, y) in tiles) {
                    attempted++
                    val dest = File(tileCache(context), "${map.id}/$zoom/$x/$y${map.ext}")
                    if (!force && dest.isFile && dest.length() > 100) {
                        // already saved
                    } else {
                        val wrote = saveTile(map.url(zoom, x, y), dest)
                        if (wrote < 0) failed++ else byteCount += wrote
                    }
                    if (attempted == 1 || attempted % 10 == 0) {
                        noteProgress(state, attempted, total)
                        onStatus("${state.name} $attempted/$total")
                    }
                }
            }
        }
        noteProgress(state, total, total)
        if (attempted > 20 && failed * 5 > attempted) {
            return "${state.name} download failed ($failed tiles)"
        }
        return null
    }

    private fun noteProgress(state: UsState, done: Int, total: Int) {
        val elapsed = (System.currentTimeMillis() - rateStart).coerceAtLeast(1)
        val bps = byteCount * 1000L / elapsed
        synchronized(pending) { current = MapJob(state.abbr, state.name, false, done, total, bps) }
    }

    private fun saveTile(url: String, dest: File): Long {
        return try {
            val request = Request.Builder().url(url).header("User-Agent", "NextcloudPhoneTrack").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return -1L
                val body = response.body ?: return -1L
                dest.parentFile?.mkdirs()
                val tmp = File(dest.absolutePath + ".part")
                body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val size = tmp.length()
                if (size < 100) {
                    tmp.delete()
                    -1L
                } else {
                    if (dest.exists()) dest.delete()
                    if (tmp.renameTo(dest)) size else -1L
                }
            }
        } catch (_: Exception) {
            -1L
        }
    }

    private fun tileCount(state: UsState, zoom: Int): Int {
        val x1 = xTile(state.west, zoom)
        val x2 = xTile(state.east, zoom)
        val y1 = yTile(state.north, zoom)
        val y2 = yTile(state.south, zoom)
        return (x2 - x1 + 1) * (y2 - y1 + 1)
    }

    private fun tilesAt(state: UsState, zoom: Int): List<Pair<Int, Int>> {
        val x1 = xTile(state.west, zoom)
        val x2 = xTile(state.east, zoom)
        val y1 = yTile(state.north, zoom)
        val y2 = yTile(state.south, zoom)
        val out = ArrayList<Pair<Int, Int>>((x2 - x1 + 1) * (y2 - y1 + 1))
        for (x in x1..x2) for (y in y1..y2) out.add(x to y)
        return out
    }

    private fun xTile(lon: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return ((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    private fun yTile(lat: Double, zoom: Int): Int {
        val n = 1 shl zoom
        val rad = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        val y = (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / Math.PI) / 2.0 * n
        return y.toInt().coerceIn(0, n - 1)
    }

    fun tileCache(context: Context) = File(context.filesDir, "osmdroid/tiles")

    private fun catalog(prefs: SharedPreferences): JSONObject {
        return try {
            JSONObject(prefs.getString("offline_maps", "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun mark(prefs: SharedPreferences, state: UsState) {
        val root = catalog(prefs)
        root.put(state.abbr, JSONObject().put("at", System.currentTimeMillis()).put("name", state.name))
        prefs.edit().putString("offline_maps", root.toString()).apply()
    }
}
