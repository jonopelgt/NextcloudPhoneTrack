package com.example.ncphonetrack

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
        if (!prefs.getBoolean("location_sharing", true)) return false
        val mode = prefs.getString("position_mode", "move") ?: "move"
        return mode != "quiet" || prefs.getBoolean("spoof_enabled", false)
    }

    fun set(context: Context, on: Boolean) {
        val prefs = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        val mode = prefs.getString("position_mode", "move") ?: "move"
        val edit = prefs.edit().putBoolean("location_sharing", on)
        if (on) {
            if (mode == "quiet") {
                val resume = prefs.getString("share_mode", "move")?.takeIf { it.isNotBlank() && it != "quiet" } ?: "move"
                edit.putString("position_mode", resume)
            }
        } else {
            if (mode != "quiet") edit.putString("share_mode", mode)
            edit.putString("position_mode", "quiet")
            edit.putBoolean("spoof_enabled", false)
            edit.putString("logging_state", "off")
        }
        edit.apply()
        applyService(context)
        refreshSurfaces(context)
    }

    private fun applyService(context: Context) {
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
}
