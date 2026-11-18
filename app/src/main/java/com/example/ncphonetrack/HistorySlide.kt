package com.example.ncphonetrack

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

class HistorySlide(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var onAgo: ((Int) -> Unit)? = null
    var onGesture: ((Boolean) -> Unit)? = null

    private var agoSec = 0
    private val spanSec = 8 * 60 * 60
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D0D0D0") }
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

    fun reset() {
        agoSec = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val cx = width / 2f
        val top = 28f * d
        val bottom = height - 28f * d
        canvas.drawRoundRect(cx - 2f * d, top, cx + 2f * d, bottom, 2f * d, 2f * d, track)
        val y = yFor(agoSec, top, bottom)
        val frameW = 48f * d
        val frameH = 36f * d
        val left = cx - frameW / 2f
        canvas.drawRoundRect(left, y - frameH / 2f, left + frameW, y + frameH / 2f, 8f * d, 8f * d, fill)
        canvas.drawRoundRect(left, y - frameH / 2f, left + frameW, y + frameH / 2f, 8f * d, 8f * d, shadow)
        text.textSize = 11f * d
        val label = label(agoSec)
        val baseline = y - (text.ascent() + text.descent()) / 2f
        canvas.drawText(label, cx, baseline, ink.apply { textSize = 11f * d; textAlign = Paint.Align.CENTER })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val d = resources.displayMetrics.density
                val top = 28f * d
                val bottom = height - 28f * d
                val y = event.y.coerceIn(top, bottom)
                val fraction = if (bottom <= top) 0f else (bottom - y) / (bottom - top)
                val next = (fraction * spanSec).roundToInt().coerceIn(0, spanSec)
                if (next != agoSec) {
                    agoSec = next
                    onAgo?.invoke(agoSec)
                    invalidate()
                }
                if (event.actionMasked == MotionEvent.ACTION_DOWN) onGesture?.invoke(true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onGesture?.invoke(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun yFor(seconds: Int, top: Float, bottom: Float): Float {
        val fraction = seconds.toFloat() / spanSec.toFloat()
        return bottom - fraction * (bottom - top)
    }

    private fun label(seconds: Int): String {
        if (seconds < 30) return "now"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) "${hours}h" else "${minutes}m"
    }
}
