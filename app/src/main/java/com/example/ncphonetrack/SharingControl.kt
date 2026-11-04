package com.example.ncphonetrack

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import org.json.JSONArray

object SharingControl {
    fun isOn(context: Context): Boolean {
        val prefs = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        val end = prefs.getLong("share_timer_end", 0L)
        if (end > 0L && System.currentTimeMillis() >= end) return false
        if (!prefs.getBoolean("location_sharing", true)) return false
        return true
    }

    fun remaining(context: Context): Long {
        val end = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE).getLong("share_timer_end", 0L)
        if (end <= 0L) return 0L
        return (end - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun statusLabel(context: Context): String {
        if (expireIfDue(context)) return "Off"
        val left = remaining(context)
        if (left > 0L) return remainingLabel(left)
        return if (isOn(context)) "On" else "Off"
    }

    fun startTimer(context: Context, durationMs: Long) {
        val duration = DURATIONS.minByOrNull { kotlin.math.abs(it - durationMs) } ?: DEFAULT_MS
        val prefs = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        val mode = prefs.getString("position_mode", "move") ?: "move"
        val edit = prefs.edit()
            .putBoolean("location_sharing", true)
            .putLong("share_timer_ms", duration)
            .putLong("share_timer_end", System.currentTimeMillis() + duration)
        if (mode == "quiet") {
            val resume = prefs.getString("share_mode", "move")?.takeIf { it.isNotBlank() && it != "quiet" } ?: "move"
            edit.putString("position_mode", resume)
        }
        edit.apply()
        scheduleAlarm(context)
        applyService(context)
        refreshSurfaces(context)
        context.sendBroadcast(Intent(ACTION_SHARING_CHANGED).setPackage(context.packageName))
    }

    fun expireIfDue(context: Context): Boolean {
        val prefs = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        val end = prefs.getLong("share_timer_end", 0L)
        if (end <= 0L || System.currentTimeMillis() < end) return false
        expire(context)
        return true
    }

    fun tick(context: Context) {
        refreshSurfaces(context)
        context.sendBroadcast(Intent(ACTION_TIMER_TICK).setPackage(context.packageName))
    }

    fun set(context: Context, on: Boolean, clearTimer: Boolean = false) {
        val prefs = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val end = prefs.getLong("share_timer_end", 0L)
        val edit = prefs.edit().putBoolean("location_sharing", on)
        if (clearTimer || (end > 0L && now >= end)) edit.putLong("share_timer_end", 0L)
        if (on) {
            val mode = prefs.getString("position_mode", "move") ?: "move"
            if (mode == "quiet") {
                val resume = prefs.getString("share_mode", "move")?.takeIf { it.isNotBlank() && it != "quiet" } ?: "move"
                edit.putString("position_mode", resume)
            }
        } else {
            edit.putString("logging_state", "off")
        }
        edit.apply()
        val kept = prefs.getLong("share_timer_end", 0L)
        if (kept > now) scheduleAlarm(context) else cancelAlarm(context)
        applyService(context)
        refreshSurfaces(context)
        context.sendBroadcast(Intent(ACTION_SHARING_CHANGED).setPackage(context.packageName))
    }

    fun repost(context: Context) {
        if (!isOn(context)) return
        applyService(context)
    }

    private fun applyService(context: Context) {
        try {
            val prefs = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
            if (!isOn(context) || !hasEnabledSession(prefs.getString("sessions", "[]") ?: "[]")) {
                context.stopService(Intent(context, LocationPublishService::class.java))
                return
            }
            val url = prefs.getString("url", "") ?: ""
            val token = prefs.getString("token", "") ?: ""
            if (!url.startsWith("http") || token.isBlank()) return
            val device = prefs.getString("nc_user", "")?.trim().orEmpty()
                .ifEmpty { prefs.getString("device", "Android") ?: "Android" }
            ContextCompat.startForegroundService(context, Intent(context, LocationPublishService::class.java).apply {
                putExtra("url", url)
                putExtra("token", token)
                putExtra("device", device)
            })
        } catch (e: Exception) {
            LogManager.add("Could not change location sharing: ${e.message}")
        }
    }

    private fun hasEnabledSession(raw: String): Boolean {
        val arr = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return false
        }
        for (i in 0 until arr.length()) {
            val session = arr.optJSONObject(i) ?: continue
            if (session.optBoolean("enabled", true) &&
                session.optString("url").isNotBlank() &&
                session.optString("token").isNotBlank()
            ) return true
        }
        return false
    }

    private fun refreshSurfaces(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, SharingWidget::class.java))
        if (ids.isNotEmpty()) {
            context.sendBroadcast(Intent(context, SharingWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }
        TileService.requestListeningState(context, ComponentName(context, SharingTile::class.java))
    }

    const val ACTION_SHARING_CHANGED = "com.example.ncphonetrack.SHARING_CHANGED"
    const val ACTION_TIMER_TICK = "com.example.ncphonetrack.TIMER_TICK"
    const val DEFAULT_MS = 4L * 60 * 60 * 1000
    val DURATIONS = longArrayOf(
        15L * 60 * 1000,
        30L * 60 * 1000,
        60L * 60 * 1000,
        2L * 60 * 60 * 1000,
        4L * 60 * 60 * 1000,
        6L * 60 * 60 * 1000,
        8L * 60 * 60 * 1000,
        12L * 60 * 60 * 1000,
        24L * 60 * 60 * 1000,
        2L * 24 * 60 * 60 * 1000,
        4L * 24 * 60 * 60 * 1000
    )

    fun durationLabel(ms: Long): String {
        val minutes = ms / 60000
        return when {
            minutes < 60 -> "$minutes min"
            minutes < 24 * 60 && minutes % 60 == 0L -> {
                val hours = minutes / 60
                if (hours == 1L) "1 hour" else "$hours hours"
            }
            minutes % (24 * 60) == 0L -> {
                val days = minutes / (24 * 60)
                if (days == 1L) "1 day" else "$days days"
            }
            else -> remainingLabel(ms)
        }
    }

    fun remainingLabel(ms: Long): String {
        val totalMin = (ms / 60000).coerceAtLeast(1)
        val days = totalMin / (60 * 24)
        val hours = (totalMin / 60) % 24
        val mins = totalMin % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${mins}m"
            else -> "${mins}m"
        }
    }

    fun untilClock(context: Context): String {
        val end = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE).getLong("share_timer_end", 0L)
        if (end <= 0L) return ""
        val time = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(end))
        val now = java.util.Calendar.getInstance()
        val then = java.util.Calendar.getInstance().apply { timeInMillis = end }
        val today = now.get(java.util.Calendar.YEAR) to now.get(java.util.Calendar.DAY_OF_YEAR)
        val stop = then.get(java.util.Calendar.YEAR) to then.get(java.util.Calendar.DAY_OF_YEAR)
        val tomorrow = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, 1) }
        val next = tomorrow.get(java.util.Calendar.YEAR) to tomorrow.get(java.util.Calendar.DAY_OF_YEAR)
        val day = then.getDisplayName(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.LONG, java.util.Locale.getDefault())
        return when (stop) {
            today -> time
            next -> "tomorrow $time"
            else -> "$day $time"
        }
    }

    fun remainingWithUntil(context: Context): String {
        val left = hourMinuteLabel(remaining(context))
        val until = untilClock(context)
        return if (until.isBlank()) left else "$left, until $until"
    }

    fun hourMinuteLabel(ms: Long): String {
        val totalMin = (ms / 60000L).coerceAtLeast(0L)
        return "${totalMin / 60}h ${totalMin % 60}m"
    }

    fun countdownLabel(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val days = total / 86_400
        val hours = (total % 86_400) / 3_600
        val mins = (total % 3_600) / 60
        val secs = total % 60
        val sec = secs.toString().padStart(2, '0')
        val min = mins.toString().padStart(2, '0')
        return when {
            days > 0 -> "${days}d ${hours}h ${min}m ${sec}s"
            hours > 0 -> "${hours}h ${min}m ${sec}s"
            else -> "${mins}m ${sec}s"
        }
    }

    fun savedDuration(context: Context): Long {
        val saved = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE).getLong("share_timer_ms", DEFAULT_MS)
        return if (saved in DURATIONS) saved else DEFAULT_MS
    }

    private fun expire(context: Context) {
        val prefs = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("location_sharing", false)
            .putLong("share_timer_end", 0L)
            .putString("logging_state", "off")
            .apply()
        cancelAlarm(context)
        applyService(context)
        refreshSurfaces(context)
        context.sendBroadcast(Intent(ACTION_SHARING_CHANGED).setPackage(context.packageName))
    }

    private fun scheduleAlarm(context: Context) {
        val end = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE).getLong("share_timer_end", 0L)
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = timerIntent(context)
        if (end <= 0L) {
            alarm.cancel(pending)
            return
        }
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pending)
    }

    private fun cancelAlarm(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(timerIntent(context))
    }

    private fun timerIntent(context: Context): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            3,
            Intent(context, SharingTimer::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
