package com.example.ncphonetrack

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.IFilesystemCache
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
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
    val maxZoom: Int,
    val url: (Int, Int, Int) -> String
)

object OfflineMaps {
    const val FRESH_MS = 30L * 24 * 60 * 60 * 1000
    private const val MIN_ZOOM = 6
    private const val MAX_ZOOM = 12
    private const val MAX_TILES_PER_ZOOM = 4000

    val bases = listOf(
        BaseMap("organic", "Organic Maps", ".png", false, 19) { z, x, y ->
            "https://tile.openstreetmap.org/$z/$x/$y.png"
        },
        BaseMap("osmand", "OsmAnd", ".png", false, 19) { z, x, y ->
            "https://tile.osmand.net/hd/$z/$x/$y.png"
        },
        BaseMap("magic", "Magic Earth", ".png", false, 19) { z, x, y ->
            "https://basemaps.cartocdn.com/rastertiles/voyager/$z/$x/$y.png"
        },
        BaseMap("openandro", "OpenAndroMaps", ".png", false, 17) { z, x, y ->
            "https://tile.opentopomap.org/$z/$x/$y.png"
        },
        BaseMap("usgs", "USGS topo", ".jpg", true, 19) { z, x, y ->
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
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()
    private val downloadClient = client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 1 })
        .build()

    fun base(id: String): BaseMap = bases.firstOrNull { it.id == id } ?: bases.first()

    fun tileSource(map: BaseMap): OnlineTileSourceBase {
        val ceiling = if (map.maxZoom < 19) 21 else map.maxZoom
        return object : OnlineTileSourceBase(map.id, 0, ceiling, 256, map.ext, arrayOf("https://localhost/")) {
            override fun getTileURLString(index: Long): String {
                val zoom = MapTileIndex.getZoom(index)
                if (zoom > map.maxZoom) return "http://127.0.0.1/overzoom-blocked"
                return map.url(zoom, MapTileIndex.getX(index), MapTileIndex.getY(index))
            }
        }
    }

    @Volatile
    var scaleOnly: Boolean = false

    @Volatile
    var tileEpoch: Int = 0

    @Volatile
    var viewedSize: Long = -1L

    private val mapRef = java.util.concurrent.atomic.AtomicReference<java.lang.ref.WeakReference<MapView>>()
    private val refreshScheduled = AtomicBoolean(false)
    private val refreshing = ConcurrentHashMap.newKeySet<String>()
    private val refresher = Executors.newFixedThreadPool(2)

    fun installBase(context: Context, mapView: MapView, base: BaseMap) {
        mapRef.set(java.lang.ref.WeakReference(mapView))
        mapView.setTileProvider(OfflineTileProvider(context, tileSource(base), base))
        mapView.maxZoomLevel = if (base.maxZoom < 19) 21.0 else 19.0
        mapView.setUseDataConnection(true)
        mapView.invalidate()
    }

    fun viewedRoot(context: Context) = File(context.filesDir, "osmdroid/viewed")

    fun viewedFile(context: Context, base: BaseMap, zoom: Int, x: Int, y: Int) =
        File(viewedRoot(context), "${base.id}/$zoom/$x/$y${base.ext}")

    fun cachedTile(context: Context, base: BaseMap, zoom: Int, x: Int, y: Int): File? {
        val state = File(tileCache(context), "${base.id}/$zoom/$x/$y${base.ext}")
        if (state.isFile && state.length() > 100) return state
        val viewed = viewedFile(context, base, zoom, x, y)
        if (viewed.isFile && viewed.length() > 100) return viewed
        return null
    }

    fun measureViewed(context: Context): Long {
        val root = viewedRoot(context)
        if (!root.isDirectory) {
            viewedSize = 0L
            return 0L
        }
        var total = 0L
        root.walkTopDown().forEach { if (it.isFile) total += it.length() }
        viewedSize = total
        return total
    }

    fun clearViewed(context: Context) {
        viewedRoot(context).deleteRecursively()
        viewedSize = 0L
    }

    private fun considerRefresh(file: File, base: BaseMap, zoom: Int, x: Int, y: Int) {
        if (scaleOnly) return
        if (System.currentTimeMillis() - file.lastModified() <= FRESH_MS) return
        if (refreshing.size > 80) return
        val key = "${base.id}/$zoom/$x/$y"
        if (!refreshing.add(key)) return
        refresher.execute {
            try {
                val tmp = File(file.parentFile, file.name + ".new")
                val wrote = saveTile(base.url(zoom, x, y), tmp)
                if (wrote > 0) {
                    if (file.exists()) file.delete()
                    if (tmp.renameTo(file)) pokeMap() else tmp.delete()
                } else {
                    tmp.delete()
                }
            } finally {
                refreshing.remove(key)
            }
        }
    }

    private fun pokeMap() {
        if (!refreshScheduled.compareAndSet(false, true)) return
        val map = mapRef.get()?.get() ?: run {
            refreshScheduled.set(false)
            return
        }
        map.postDelayed({
            refreshScheduled.set(false)
            if (scaleOnly) return@postDelayed
            map.tileProvider.clearTileCache()
            map.invalidate()
        }, 800)
    }

    private class ViewedTileWriter(
        private val context: Context,
        private val base: BaseMap
    ) : IFilesystemCache {
        override fun saveFile(source: ITileSource, index: Long, stream: InputStream, expiration: Long?): Boolean {
            val dest = viewedFile(context, base, MapTileIndex.getZoom(index), MapTileIndex.getX(index), MapTileIndex.getY(index))
            return try {
                dest.parentFile?.mkdirs()
                val tmp = File(dest.absolutePath + ".part")
                tmp.outputStream().use { stream.copyTo(it) }
                if (tmp.length() < 100) {
                    tmp.delete()
                    false
                } else {
                    if (dest.exists()) dest.delete()
                    val ok = tmp.renameTo(dest)
                    if (ok) viewedSize = -1L
                    ok
                }
            } catch (_: Exception) {
                false
            }
        }

        override fun exists(source: ITileSource, index: Long): Boolean {
            val dest = viewedFile(context, base, MapTileIndex.getZoom(index), MapTileIndex.getX(index), MapTileIndex.getY(index))
            return dest.isFile && dest.length() > 100
        }

        override fun getExpirationTimestamp(source: ITileSource, index: Long): Long? {
            val dest = viewedFile(context, base, MapTileIndex.getZoom(index), MapTileIndex.getX(index), MapTileIndex.getY(index))
            if (!dest.isFile) return null
            return dest.lastModified() + FRESH_MS
        }

        override fun remove(source: ITileSource, index: Long): Boolean {
            return viewedFile(context, base, MapTileIndex.getZoom(index), MapTileIndex.getX(index), MapTileIndex.getY(index)).delete()
        }

        override fun loadTile(source: ITileSource, index: Long): Drawable? {
            val dest = viewedFile(context, base, MapTileIndex.getZoom(index), MapTileIndex.getX(index), MapTileIndex.getY(index))
            if (!dest.isFile || dest.length() <= 100) return null
            val bitmap = BitmapFactory.decodeFile(dest.path) ?: return null
            return BitmapDrawable(context.resources, bitmap)
        }

        override fun onDetach() = Unit
    }

    private class OfflineTileProvider(
        context: Context,
        source: ITileSource,
        base: BaseMap
    ) : MapTileProviderBasic(context, source, ViewedTileWriter(context, base)) {
        init {
            mTileProviderList.add(0, LocalTileModule(context, base))
            if (base.maxZoom < 19) {
                val index = mTileProviderList.indexOfFirst { it.usesDataConnection }.coerceAtLeast(0)
                mTileProviderList.add(index, OverzoomModule(context, base))
            }
        }
    }

    private class LocalTileModule(
        private val context: Context,
        private val base: BaseMap
    ) : MapTileModuleProviderBase(2, 80) {
        private val parents = LruCache<String, Bitmap>(48)
        private val scaled = LruCache<Long, Bitmap>(80)

        override fun getUsesDataConnection(): Boolean = false
        override fun getMinimumZoomLevel(): Int = 0
        override fun getMaximumZoomLevel(): Int = 21
        override fun setTileSource(tileSource: ITileSource?) = Unit
        override fun getName(): String = "local-${base.id}"
        override fun getThreadGroupName(): String = "localtiles"
        override fun getTileLoader(): TileLoader = object : TileLoader() {
            override fun loadTile(pMapTileIndex: Long): Drawable? {
                val epoch = tileEpoch
                val zoom = MapTileIndex.getZoom(pMapTileIndex)
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                if (scaleOnly) {
                    if (epoch != tileEpoch) return null
                    scaled.get(pMapTileIndex)?.let { return BitmapDrawable(context.resources, it) }
                    return scaledParent(x, y, zoom)?.also { scaled.put(pMapTileIndex, it) }
                        ?.let { BitmapDrawable(context.resources, it) }
                }
                val exact = cachedTile(context, base, zoom, x, y)
                if (exact != null) {
                    if (epoch != tileEpoch) return null
                    considerRefresh(exact, base, zoom, x, y)
                    val bitmap = BitmapFactory.decodeFile(exact.path) ?: return null
                    return BitmapDrawable(context.resources, bitmap)
                }
                val (lat, lon) = tileLatLon(zoom, x, y)
                val ceiling = storedCeiling(context, lat, lon)
                if (ceiling != null && zoom > ceiling) {
                    if (epoch != tileEpoch) return null
                    val bitmap = scaledParent(x, y, zoom) ?: return null
                    return BitmapDrawable(context.resources, bitmap)
                }
                return null
            }
        }

        private fun scaledParent(x: Int, y: Int, zoom: Int): Bitmap? {
            var diff = 1
            var parent: Bitmap? = null
            while (zoom - diff >= MIN_ZOOM && diff <= 8) {
                parent = parentBitmap(x shr diff, y shr diff, zoom - diff)
                if (parent != null) break
                diff++
            }
            if (parent == null) return null
            val found = parent
            return scaleTileQuadrant(found, x, y, diff)
        }

        private fun parentBitmap(x: Int, y: Int, zoom: Int): Bitmap? {
            val file = cachedTile(context, base, zoom, x, y) ?: return null
            considerRefresh(file, base, zoom, x, y)
            val key = "${base.id}/$zoom/$x/$y"
            parents.get(key)?.let { return it }
            val bitmap = BitmapFactory.decodeFile(file.path) ?: return null
            parents.put(key, bitmap)
            return bitmap
        }
    }

    private class OverzoomModule(
        private val context: Context,
        private val base: BaseMap
    ) : MapTileModuleProviderBase(2, 40) {
        private val parents = LruCache<String, Bitmap>(24)

        override fun getUsesDataConnection(): Boolean = true
        override fun getMinimumZoomLevel(): Int = base.maxZoom + 1
        override fun getMaximumZoomLevel(): Int = 21
        override fun setTileSource(tileSource: ITileSource?) = Unit
        override fun getName(): String = "overzoom"
        override fun getThreadGroupName(): String = "overzoom"
        override fun getTileLoader(): TileLoader = object : TileLoader() {
            override fun loadTile(pMapTileIndex: Long): Drawable? {
                val zoom = MapTileIndex.getZoom(pMapTileIndex)
                val diff = zoom - base.maxZoom
                if (diff <= 0 || diff > 4) return null
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                val parent = parentBitmap(x shr diff, y shr diff) ?: return null
                val scaled = scaleQuadrant(parent, x, y, diff)
                return BitmapDrawable(context.resources, scaled)
            }
        }

        private fun parentBitmap(x: Int, y: Int): Bitmap? {
            val key = "$x/$y"
            parents.get(key)?.let { return it }
            val cached = cachedTile(context, base, base.maxZoom, x, y)
            val bitmap = if (cached != null) {
                considerRefresh(cached, base, base.maxZoom, x, y)
                BitmapFactory.decodeFile(cached.path)
            } else {
                fetchBitmap(base.url(base.maxZoom, x, y), x, y)
            } ?: return null
            parents.put(key, bitmap)
            return bitmap
        }

        private fun scaleQuadrant(src: Bitmap, x: Int, y: Int, diff: Int): Bitmap = scaleTileQuadrant(src, x, y, diff)

        private fun fetchBitmap(url: String, x: Int, y: Int): Bitmap? {
            return try {
                val request = Request.Builder().url(url).header("User-Agent", "NextcloudPhoneTrack").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return null
                    val bytes = response.body?.bytes() ?: return null
                    val dest = viewedFile(context, base, base.maxZoom, x, y)
                    dest.parentFile?.mkdirs()
                    dest.writeBytes(bytes)
                    viewedSize = -1L
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            } catch (_: Exception) {
                null
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

    private fun scaleTileQuadrant(src: Bitmap, x: Int, y: Int, diff: Int): Bitmap {
        val parts = 1 shl diff
        val cellW = (src.width / parts).coerceAtLeast(1)
        val cellH = (src.height / parts).coerceAtLeast(1)
        val left = (x and (parts - 1)) * cellW
        val top = (y and (parts - 1)) * cellH
        val crop = Bitmap.createBitmap(src, left, top, cellW, cellH)
        val scaled = Bitmap.createScaledBitmap(crop, src.width, src.height, true)
        if (crop != scaled) crop.recycle()
        return scaled
    }

    fun storedZoom(prefs: SharedPreferences, abbr: String): Int {
        val zoom = catalog(prefs).optJSONObject(abbr)?.optInt("zoom", MAX_ZOOM) ?: MAX_ZOOM
        return zoom.coerceIn(MIN_ZOOM, 16)
    }

    private fun storedCeiling(context: Context, lat: Double, lon: Double): Int? {
        val state = stateAt(lat, lon) ?: return null
        val prefs = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        val item = catalog(prefs).optJSONObject(state.abbr) ?: return null
        if (item.optLong("at", 0L) <= 0L) return null
        return item.optInt("zoom", MAX_ZOOM).coerceIn(MIN_ZOOM, 16)
    }

    private fun tileLatLon(zoom: Int, x: Int, y: Int): Pair<Double, Double> {
        val n = 1 shl zoom.coerceIn(0, 30)
        val lon = (x + 0.5) / n * 360.0 - 180.0
        val lat = Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(Math.PI * (1 - 2.0 * (y + 0.5) / n))))
        return lat to lon
    }

    fun estimateBytes(state: UsState, maxZoom: Int): Long {
        var tiles = 0
        for (zoom in MIN_ZOOM..maxZoom.coerceIn(MIN_ZOOM, 16)) tiles += tileCount(state, zoom)
        return tiles.toLong() * bases.size * 20_000L
    }

    fun estimateMapZoom(state: UsState, zoom: Int): Long {
        return tileCount(state, zoom.coerceIn(6, 19)).toLong() * 20_000L
    }

    data class StoredMap(
        val state: UsState,
        val mapId: String,
        val zoom: Int,
        val at: Long,
        val bytes: Long
    )

    fun hasPiece(prefs: SharedPreferences, abbr: String, mapId: String, zoom: Int): Boolean {
        val item = catalog(prefs).optJSONObject(abbr) ?: return false
        val piece = item.optJSONObject("maps")?.optJSONObject(mapId)?.optJSONObject(zoom.toString())
        if (piece != null && piece.optLong("at", 0L) > 0L) return true
        val legacyAt = item.optLong("at", 0L)
        val legacyZoom = item.optInt("zoom", 0)
        return legacyAt > 0L && mapId.isNotBlank() && zoom <= legacyZoom
    }

    fun storedMaps(prefs: SharedPreferences): List<StoredMap> {
        val root = catalog(prefs)
        val out = mutableListOf<StoredMap>()
        states.forEach { state ->
            val item = root.optJSONObject(state.abbr) ?: return@forEach
            val maps = item.optJSONObject("maps")
            if (maps != null) {
                val ids = maps.keys()
                while (ids.hasNext()) {
                    val mapId = ids.next()
                    val zooms = maps.optJSONObject(mapId) ?: continue
                    val keys = zooms.keys()
                    while (keys.hasNext()) {
                        val zoomKey = keys.next()
                        val zoom = zoomKey.toIntOrNull() ?: continue
                        val piece = zooms.optJSONObject(zoomKey) ?: continue
                        val at = piece.optLong("at", 0L)
                        if (at <= 0L) continue
                        out.add(StoredMap(state, mapId, zoom, at, piece.optLong("bytes", -1L)))
                    }
                }
            }
            val at = item.optLong("at", 0L)
            if (at > 0L) {
                out.add(StoredMap(state, "", item.optInt("zoom", MAX_ZOOM), at, item.optLong("bytes", -1L)))
            }
        }
        return out
    }

    fun downloaded(prefs: SharedPreferences): List<Pair<UsState, Long>> {
        val root = catalog(prefs)
        return states.mapNotNull { state ->
            val at = root.optJSONObject(state.abbr)?.optLong("at", 0L) ?: 0L
            if (at <= 0L) null else state to at
        }
    }

    fun storedBytes(prefs: SharedPreferences, abbr: String): Long {
        val item = catalog(prefs).optJSONObject(abbr) ?: return -1L
        return if (item.has("bytes")) item.optLong("bytes", 0L) else -1L
    }

    fun storedTotal(prefs: SharedPreferences): Long {
        return storedMaps(prefs).sumOf { if (it.bytes > 0L) it.bytes else 0L }
    }

    fun statesMissingSize(prefs: SharedPreferences): List<UsState> {
        return downloaded(prefs).filter { storedBytes(prefs, it.first.abbr) < 0L }.map { it.first }
    }

    fun saveBytes(prefs: SharedPreferences, state: UsState, bytes: Long) {
        val root = catalog(prefs)
        val item = root.optJSONObject(state.abbr) ?: return
        item.put("bytes", bytes)
        root.put(state.abbr, item)
        prefs.edit().putString("offline_maps", root.toString()).apply()
    }

    fun measureState(context: Context, state: UsState, maxZoom: Int = MAX_ZOOM): Long {
        var total = 0L
        for (map in bases) {
            for (zoom in zoomsThrough(maxZoom)) {
                for ((x, y) in tilesAt(state, zoom)) {
                    val file = File(tileCache(context), "${map.id}/$zoom/$x/$y${map.ext}")
                    if (file.isFile) total += file.length()
                }
            }
        }
        return total
    }

    fun stale(prefs: SharedPreferences): List<UsState> {
        val now = System.currentTimeMillis()
        return downloaded(prefs).filter { now - it.second > FRESH_MS }.map { it.first }
    }

    fun isFresh(at: Long): Boolean = System.currentTimeMillis() - at <= FRESH_MS

    data class MapPick(val state: UsState, val mapId: String, val zoom: Int)

    data class MapJob(
        val key: String,
        val abbr: String,
        val name: String,
        val queued: Boolean,
        val done: Int,
        val total: Int,
        val rateBps: Long
    )

    private data class PendingMap(
        val state: UsState,
        val mapId: String,
        val force: Boolean,
        val zoom: Int
    ) {
        val key: String get() = "${state.abbr}|$mapId|$zoom"
        fun label(): String {
            val map = bases.firstOrNull { it.id == mapId }?.label ?: "Maps"
            return "${state.name} · $map z$zoom"
        }
    }
    private val pending = mutableListOf<PendingMap>()
    @Volatile private var current: MapJob? = null
    private val running = AtomicBoolean(false)
    private var byteCount = 0L
    private val rateSamples = ArrayDeque<Triple<Long, Long, Long>>()
    @Volatile private var cancelKey: String? = null

    fun rateText(bps: Long): String {
        val kb = bps / 1000.0
        return if (kb >= 1000) String.format(Locale.US, "%.1f MB/s", kb / 1000.0)
        else String.format(Locale.US, "%.0f KB/s", kb)
    }

    fun isQueued(abbr: String): Boolean = synchronized(pending) {
        current?.abbr == abbr || pending.any { it.state.abbr == abbr }
    }

    fun cancel(key: String) {
        synchronized(pending) {
            pending.removeAll { it.key == key }
            if (current?.key == key) cancelKey = key
        }
    }

    private fun cancelState(abbr: String) {
        synchronized(pending) {
            pending.removeAll { it.state.abbr == abbr }
            if (current?.abbr == abbr) cancelKey = current?.key
        }
    }

    fun remove(context: Context, prefs: SharedPreferences, state: UsState) {
        cancelState(state.abbr)
        invalidateDiskUse()
        val root = catalog(prefs)
        root.remove(state.abbr)
        prefs.edit().putString("offline_maps", root.toString()).apply()
        Thread {
            for (map in bases) {
                for (zoom in MIN_ZOOM..16) {
                    for ((x, y) in tilesAt(state, zoom)) {
                        File(tileCache(context), "${map.id}/$zoom/$x/$y${map.ext}").delete()
                    }
                }
            }
        }.start()
    }

    fun queue(): List<MapJob> = synchronized(pending) {
        val list = mutableListOf<MapJob>()
        current?.let { list.add(it) }
        pending.forEach { list.add(MapJob(it.key, it.state.abbr, it.label(), true, 0, 0, 0L)) }
        list
    }

    fun enqueue(
        context: Context,
        prefs: SharedPreferences,
        picks: List<MapPick>,
        force: Boolean,
        onStatus: (String) -> Unit
    ) {
        if (picks.isEmpty()) return
        val start = synchronized(pending) {
            picks.forEach { pick ->
                val map = bases.firstOrNull { it.id == pick.mapId } ?: return@forEach
                val zoom = pick.zoom.coerceIn(6, 19)
                if (zoom > map.maxZoom) return@forEach
                val key = "${pick.state.abbr}|${pick.mapId}|$zoom"
                if (current?.key == key) return@forEach
                if (pending.any { it.key == key }) return@forEach
                pending.add(PendingMap(pick.state, pick.mapId, force, zoom))
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
                    val map = bases.firstOrNull { it.id == job.mapId } ?: bases.first()
                    val total = tileCount(job.state, job.zoom).coerceAtLeast(1)
                    byteCount = 0L
                    rateSamples.clear()
                    synchronized(pending) { current = MapJob(job.key, job.state.abbr, job.label(), false, 0, total, 0L) }
                    onStatus("${job.label()} started")
                    val error = download(context, job, map, total, onStatus)
                    synchronized(pending) { current = null }
                    if (error == null) {
                        markPiece(prefs, job.state, job.mapId, job.zoom, byteCount)
                        onStatus("${job.label()} downloaded")
                        LogManager.add("${job.label()}: offline map downloaded")
                    } else if (error == "cancelled") {
                        onStatus("${job.label()} cancelled")
                    } else {
                        onStatus(error)
                        LogManager.add("${job.label()}: $error")
                    }
                }
            } finally {
                synchronized(pending) {
                    if (pending.isEmpty() && current == null) running.set(false)
                }
            }
        }.start()
    }

    private fun tileTotal(state: UsState, maxZoom: Int): Int {
        var total = 0
        for (zoom in zoomsThrough(maxZoom)) total += tileCount(state, zoom)
        return total * bases.size
    }

    private fun zoomsThrough(maxZoom: Int): List<Int> = (MIN_ZOOM..maxZoom.coerceIn(MIN_ZOOM, 16)).toList()

    private fun zoomsFor(state: UsState): List<Int> = zoomsThrough(MAX_ZOOM)

    private fun download(
        context: Context,
        job: PendingMap,
        map: BaseMap,
        total: Int,
        onStatus: (String) -> Unit
    ): String? {
        var attempted = 0
        var failed = 0
        val tiles = tilesAt(job.state, job.zoom)
        for ((x, y) in tiles) {
            if (cancelKey == job.key) {
                cancelKey = null
                return "cancelled"
            }
            attempted++
            val dest = File(tileCache(context), "${map.id}/${job.zoom}/$x/$y${map.ext}")
            if (!job.force && dest.isFile && dest.length() > 100) {
                // already saved
            } else {
                if (attempted == 1 || attempted % 5 == 0) {
                    noteProgress(job, attempted, total)
                    onStatus("${job.label()} $attempted/$total")
                }
                val started = System.currentTimeMillis()
                val wrote = saveTile(map.url(job.zoom, x, y), dest)
                if (wrote < 0) failed++ else {
                    byteCount += wrote
                    val now = System.currentTimeMillis()
                    rateSamples.addLast(Triple(now, (now - started).coerceAtLeast(1L), wrote))
                    while (rateSamples.isNotEmpty() && now - rateSamples.first().first > 5_000L) rateSamples.removeFirst()
                }
            }
            if (attempted == 1 || attempted % 20 == 0) {
                noteProgress(job, attempted, total)
                onStatus("${job.label()} $attempted/$total")
            }
        }
        noteProgress(job, total, total)
        if (attempted > 20 && failed * 5 > attempted) {
            return "${job.label()} download failed ($failed tiles)"
        }
        return null
    }

    private fun noteProgress(job: PendingMap, done: Int, total: Int) {
        val now = System.currentTimeMillis()
        while (rateSamples.isNotEmpty() && now - rateSamples.first().first > 5_000L) rateSamples.removeFirst()
        val bytes = rateSamples.sumOf { it.third }
        val span = rateSamples.sumOf { it.second }.coerceAtLeast(1L)
        val bps = if (bytes <= 0L) 0L else bytes * 1000L / span
        synchronized(pending) { current = MapJob(job.key, job.state.abbr, job.label(), false, done, total, bps) }
    }

    private fun saveTile(url: String, dest: File): Long {
        return try {
            val request = Request.Builder().url(url).header("User-Agent", "NextcloudPhoneTrack").build()
            downloadClient.newCall(request).execute().use { response ->
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

    @Volatile private var diskCache: Pair<Long, Map<String, Long>>? = null

    fun cachedDiskUse(): Pair<Long, Map<String, Long>>? = diskCache

    fun invalidateDiskUse() {
        diskCache = null
    }

    fun diskUse(context: Context): Pair<Long, Map<String, Long>> {
        val measured = measureDisk(context)
        diskCache = measured
        return measured
    }

    private fun measureDisk(context: Context): Pair<Long, Map<String, Long>> {
        val root = tileCache(context)
        val byState = HashMap<String, Long>()
        var total = 0L
        if (!root.isDirectory) return total to byState
        root.listFiles()?.forEach { zoomRoot ->
            zoomRoot.listFiles()?.forEach { zoomDir ->
                val zoom = zoomDir.name.toIntOrNull() ?: return@forEach
                zoomDir.listFiles()?.forEach { xDir ->
                    val x = xDir.name.toIntOrNull() ?: return@forEach
                    xDir.listFiles()?.forEach { tile ->
                        if (!tile.isFile) return@forEach
                        val y = tile.name.substringBefore('.').toIntOrNull() ?: return@forEach
                        val size = tile.length()
                        total += size
                        states.forEach { state ->
                            if (covers(state, zoom, x, y)) {
                                byState[state.abbr] = (byState[state.abbr] ?: 0L) + size
                            }
                        }
                    }
                }
            }
        }
        return total to byState
    }

    fun compactSize(bytes: Long): String {
        val n = bytes.coerceAtLeast(0)
        val k = 1024.0
        return when {
            n < 1024 -> "${n}B"
            n < k * 1024 -> "${(n / k).toInt()}K"
            n < k * k * 1024 -> {
                val m = n / (k * k)
                if (m < 10) String.format(Locale.US, "%.1fM", m) else "${m.toInt()}M"
            }
            else -> {
                val g = n / (k * k * k)
                if (g < 10) String.format(Locale.US, "%.1fG", g) else "${g.toInt()}G"
            }
        }
    }

    private fun covers(state: UsState, zoom: Int, x: Int, y: Int): Boolean {
        val x1 = xTile(state.west, zoom)
        val x2 = xTile(state.east, zoom)
        val y1 = yTile(state.north, zoom)
        val y2 = yTile(state.south, zoom)
        return x in x1..x2 && y in y1..y2
    }

    fun tileCache(context: Context) = File(context.filesDir, "osmdroid/tiles")

    private fun catalog(prefs: SharedPreferences): JSONObject {
        return try {
            JSONObject(prefs.getString("offline_maps", "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun mark(prefs: SharedPreferences, state: UsState, bytes: Long, zoom: Int) {
        val root = catalog(prefs)
        root.put(state.abbr, JSONObject()
            .put("at", System.currentTimeMillis())
            .put("name", state.name)
            .put("bytes", bytes)
            .put("zoom", zoom))
        prefs.edit().putString("offline_maps", root.toString()).apply()
    }

    private fun markPiece(prefs: SharedPreferences, state: UsState, mapId: String, zoom: Int, bytes: Long) {
        val root = catalog(prefs)
        val item = root.optJSONObject(state.abbr) ?: JSONObject().put("name", state.name)
        val maps = item.optJSONObject("maps") ?: JSONObject()
        val zooms = maps.optJSONObject(mapId) ?: JSONObject()
        zooms.put(zoom.toString(), JSONObject().put("at", System.currentTimeMillis()).put("bytes", bytes))
        maps.put(mapId, zooms)
        item.put("maps", maps)
        item.put("name", state.name)
        root.put(state.abbr, item)
        prefs.edit().putString("offline_maps", root.toString()).apply()
    }

    fun removePiece(context: Context, prefs: SharedPreferences, state: UsState, mapId: String, zoom: Int) {
        cancel("${state.abbr}|$mapId|$zoom")
        val root = catalog(prefs)
        val item = root.optJSONObject(state.abbr) ?: return
        val maps = item.optJSONObject("maps") ?: return
        val zooms = maps.optJSONObject(mapId) ?: return
        zooms.remove(zoom.toString())
        if (zooms.length() == 0) maps.remove(mapId)
        item.put("maps", maps)
        root.put(state.abbr, item)
        prefs.edit().putString("offline_maps", root.toString()).apply()
        val map = bases.firstOrNull { it.id == mapId } ?: return
        Thread {
            tilesAt(state, zoom).forEach { (x, y) ->
                File(tileCache(context), "${map.id}/$zoom/$x/$y${map.ext}").delete()
            }
        }.start()
    }
}
