package com.example.ncphonetrack

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object LauncherIcon {
    private const val ON = "com.example.ncphonetrack.LauncherOn"
    private const val TIMER = "com.example.ncphonetrack.LauncherTimer"
    private const val OFF = "com.example.ncphonetrack.LauncherOff"

    fun apply(context: Context) {
        val target = when {
            !SharingControl.isOn(context) -> OFF
            SharingControl.remaining(context) > 0L -> TIMER
            else -> ON
        }
        val pm = context.packageManager
        val order = listOf(target) + listOf(ON, TIMER, OFF).filter { it != target }
        order.forEach { name ->
            val component = ComponentName(context.packageName, name)
            val enable = name == target
            val current = pm.getComponentEnabledSetting(component)
            val enabledNow = when (current) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
                else -> name == ON
            }
            if (enabledNow != enable) {
                pm.setComponentEnabledSetting(
                    component,
                    if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        }
    }
}
