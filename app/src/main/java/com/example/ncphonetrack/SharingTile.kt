package com.example.ncphonetrack

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class SharingTile : TileService() {
    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        SharingControl.set(this, !SharingControl.isOn(this))
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = SharingControl.isOn(this)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Location sharing"
        tile.icon = Icon.createWithResource(this, SharingControl.iconRes(this))
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = SharingControl.statusLabel(this)
        tile.updateTile()
    }
}

class SharingRepost : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!SharingControl.isOn(context)) return
        SharingControl.repost(context)
    }
}
