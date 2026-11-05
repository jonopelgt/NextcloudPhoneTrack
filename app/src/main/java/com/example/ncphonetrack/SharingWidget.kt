package com.example.ncphonetrack

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class SharingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> manager.updateAppWidget(id, views(context)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) {
            SharingControl.set(context, !SharingControl.isOn(context))
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, SharingWidget::class.java))
            onUpdate(context, manager, ids)
            return
        }
        super.onReceive(context, intent)
    }

    private fun views(context: Context): RemoteViews {
        val on = SharingControl.isOn(context)
        val label = SharingControl.statusLabel(context)
        return RemoteViews(context.packageName, R.layout.widget_sharing).apply {
            setTextViewText(R.id.widgetState, label)
            setImageViewResource(R.id.widgetIcon, SharingControl.iconRes(context))
            setInt(R.id.widgetRoot, "setBackgroundColor", if (on || label != "Off") 0xFF0082C9.toInt() else 0xFF5F6368.toInt())
            setOnClickPendingIntent(R.id.widgetRoot, toggleIntent(context))
        }
    }

    private fun toggleIntent(context: Context): PendingIntent {
        val intent = Intent(context, SharingWidget::class.java).setAction(ACTION_TOGGLE)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val ACTION_TOGGLE = "com.example.ncphonetrack.TOGGLE_SHARING"
    }
}
