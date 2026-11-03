package com.example.ncphonetrack

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SharingTimer : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        SharingControl.expireIfDue(context)
    }
}
