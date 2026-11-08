package com.example.ncphonetrack

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class LowPowerWake : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        ContextCompat.startForegroundService(context, Intent(context, LocationPublishService::class.java).apply {
            action = LocationPublishService.ACTION_LOW_POWER_WAKE
        })
    }
}
