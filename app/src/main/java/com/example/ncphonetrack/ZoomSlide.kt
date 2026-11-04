package com.example.ncphonetrack

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

class ZoomSlide(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var onZoom: ((Double) -> Unit)? = null
    var limits: () -> Pair<Double, Double> = { 0.0 to 21.0 }

    private var shown = 12.0
    private var startZoom = 12.0
    private var startY = 0f
    private val travel: Float
        get() = 320f * resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#111111") }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#111111")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }

    fun setZoom(zoom: Double) {
        if (abs(shown - zoom) < 0.01) return
        shown = zoom
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height / 2f
        val handleH = 7f * d
        canvas.drawRoundRect(0f, cy - handleH / 2f, width.toFloat(), cy + handleH / 2f, handleH, handleH, fill)
        canvas.drawRoundRect(0f, cy - handleH / 2f, width.toFloat(), cy + handleH / 2f, handleH, handleH, shadow)
        val frameW = 40f * d
        val frameH = 48f * d
        val left = cx - frameW / 2f
        val top = cy - frameH / 2f
        canvas.drawRoundRect(left, top, left + frameW, top + frameH, 8f * d, 8f * d, fill)
        canvas.drawRoundRect(left, top, left + frameW, top + frameH, 8f * d, 8f * d, shadow)
        val tri = 5f * d
        val up = Path().apply {
            moveTo(cx, top + 5f * d)
            lineTo(cx - tri, top + 5f * d + tri)
            lineTo(cx + tri, top + 5f * d + tri)
            close()
        }
        val down = Path().apply {
            moveTo(cx, top + frameH - 5f * d)
            lineTo(cx - tri, top + frameH - 5f * d - tri)
            lineTo(cx + tri, top + frameH - 5f * d - tri)
            close()
        }
        canvas.drawPath(up, ink)
        canvas.drawPath(down, ink)
        text.textSize = 14f * d
        val label = zoomLabel(shown)
        val baseline = cy - (text.ascent() + text.descent()) / 2f
        canvas.drawText(label, cx, baseline, text)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = event.rawY
                startZoom = shown
                parent?.requestDisallowInterceptTouchEvent(true)
                animate().cancel()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = (event.rawY - startY).coerceIn(-travel, travel)
                translationY = dy
                val next = zoomFor(dy)
                shown = next
                onZoom?.invoke(next)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                animate().translationY(0f).setDuration(160).start()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun zoomFor(dy: Float): Double {
        val (minZoom, maxZoom) = limits()
        val start = startZoom.coerceIn(minZoom, maxZoom)
        return if (dy <= 0f) {
            val t = (-dy / travel).coerceIn(0f, 1f)
            start + (maxZoom - start) * t
        } else {
            val t = (dy / travel).coerceIn(0f, 1f)
            start - (start - minZoom) * t
        }
    }

    private fun zoomLabel(zoom: Double): String {
        val tenth = round(zoom * 10.0) / 10.0
        val whole = round(tenth)
        return if (abs(tenth - whole) < 0.05) whole.toInt().toString()
        else String.format(Locale.US, "%.1f", tenth)
    }
}
