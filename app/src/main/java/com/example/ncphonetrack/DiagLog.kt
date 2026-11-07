package com.example.ncphonetrack

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.BatteryManager
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagLog {
    data class Sample(val time: Long, val activity: String, val mode: String, val charge: Int)

    private const val KEY = "diag_samples"
    private const val MAX = 800
    const val HOUR_MS = 60L * 60L * 1000L

    fun outputMode(context: Context): String {
        val prefs = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        if (!SharingControl.isOn(context)) return "Off"
        val stored = prefs.getString("position_mode", "move") ?: "move"
        val mode = if (stored == "auto") prefs.getString("auto_effective", "move") ?: "move" else stored
        return when (mode) {
            "significant" -> "Low power"
            "walk" -> "Walk"
            "rapid" -> "Rapid"
            "manual" -> "Manual"
            "quiet" -> "Off"
            "auto" -> "Auto"
            else -> "Move"
        }
    }

    fun noteActivity(context: Context, activity: String) {
        context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
            .edit().putString("diag_activity", activity).apply()
        add(context, activity, outputMode(context))
    }

    fun noteMode(context: Context) {
        val prefs = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        val activity = prefs.getString("diag_activity", "Unknown") ?: "Unknown"
        add(context, activity, outputMode(context))
    }

    fun samples(context: Context): List<Sample> {
        val raw = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
        val out = ArrayList<Sample>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            out.add(Sample(item.optLong("t"), item.optString("a", "Unknown"), item.optString("m", "Off"), item.optInt("b", -1)))
        }
        return out
    }

    private fun add(context: Context, activity: String, mode: String) {
        val prefs = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        val arr = try {
            JSONArray(prefs.getString(KEY, "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        val now = System.currentTimeMillis()
        val charge = batteryCharge(context)
        val last = if (arr.length() == 0) null else arr.optJSONObject(arr.length() - 1)
        if (last != null && last.optString("a") == activity && last.optString("m") == mode &&
            last.optInt("b", -1) == charge && now - last.optLong("t") < 60_000L
        ) return
        arr.put(JSONObject().put("t", now).put("a", activity).put("m", mode).put("b", charge))
        while (arr.length() > 0) {
            val oldest = arr.optJSONObject(0)?.optLong("t") ?: break
            if (now - oldest <= HOUR_MS) break
            arr.remove(0)
        }
        while (arr.length() > MAX) arr.remove(0)
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun batteryCharge(context: Context): Int {
        val pct = context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: return -1
        return if (pct in 0..100) pct else -1
    }
}

class DiagChart(context: Context) : View(context) {
    var samples: List<DiagLog.Sample> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val activityOrder = listOf("Unknown", "Still", "Walking", "In vehicle")
    private val modeOrder = listOf("Off", "Manual", "Low power", "Walk", "Move", "Rapid", "Auto")
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#90A4AE"); strokeWidth = 2f }
    private val activityPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1565C0"); strokeWidth = 4f; style = Paint.Style.STROKE
    }
    private val modePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EF6C00"); strokeWidth = 4f; style = Paint.Style.STROKE
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1565C0"); textSize = 42f }
    private val modeLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EF6C00"); textSize = 42f }
    private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1565C0"); textSize = 28f }
    private val chargePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E7D32"); strokeWidth = 4f; style = Paint.Style.STROKE
    }
    private val clock = SimpleDateFormat("h:mm a", Locale.getDefault())

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.currentTimeMillis()
        val start = now - DiagLog.HOUR_MS
        val window = samples.filter { it.time >= start }
        val left = 250f
        val right = width - 230f
        val top = 24f
        val bottom = height - 56f
        canvas.drawColor(Color.WHITE)
        if (window.isEmpty() || right <= left) {
            canvas.drawText("No diagnostics yet", 24f, height / 2f, label)
            return
        }
        val end = now
        fun x(time: Long) = left + (right - left) * (time - start).toFloat() / (end - start).toFloat()
        fun yActivity(index: Int) = bottom - (bottom - top) * index / (activityOrder.size - 1).coerceAtLeast(1)
        fun yMode(index: Int) = bottom - (bottom - top) * index / (modeOrder.size - 1).coerceAtLeast(1)
        canvas.drawLine(left, top, left, bottom, axis)
        canvas.drawLine(right, top, right, bottom, axis)
        canvas.drawLine(left, bottom, right, bottom, axis)
        drawStep(canvas, window.map { x(it.time) to yActivity(activityOrder.indexOf(it.activity).coerceAtLeast(0)) }, activityPaint)
        drawStep(canvas, window.map { x(it.time) to yMode(modeOrder.indexOf(it.mode).coerceAtLeast(0)) }, modePaint)
        drawCharge(canvas, window.mapNotNull { sample ->
            if (sample.charge < 0) null else x(sample.time) to (bottom - (bottom - top) * sample.charge / 100f)
        })
        activityOrder.forEachIndexed { index, name -> canvas.drawText(name, 8f, yActivity(index) + 8f, label) }
        modeOrder.forEachIndexed { index, name -> canvas.drawText(name, right + 8f, yMode(index) + 8f, modeLabelPaint) }
        canvas.drawText(clock.format(Date(start)), left, height - 12f, clockPaint)
        val endText = clock.format(Date(end))
        canvas.drawText(endText, right - clockPaint.measureText(endText), height - 12f, clockPaint)
    }

    private fun drawStep(canvas: Canvas, points: List<Pair<Float, Float>>, paint: Paint) {
        if (points.size < 2) return
        for (i in 1 until points.size) {
            val previous = points[i - 1]
            val current = points[i]
            canvas.drawLine(previous.first, previous.second, current.first, previous.second, paint)
            canvas.drawLine(current.first, previous.second, current.first, current.second, paint)
        }
    }

    private fun drawCharge(canvas: Canvas, points: List<Pair<Float, Float>>) {
        if (points.size < 2) return
        for (i in 1 until points.size) {
            val previous = points[i - 1]
            val current = points[i]
            canvas.drawLine(previous.first, previous.second, current.first, current.second, chargePaint)
        }
    }
}
