package com.example.ncphonetrack

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object LauncherIcon {
    private const val ON = "com.example.ncphonetrack.LauncherOn"
    private const val TIMER = "com.example.ncphonetrack.LauncherTimer"
    private const val OFF = "com.example.ncphonetrack.LauncherOff"
    private val all = listOf(ON, TIMER, OFF)
    private var launchedAs: String? = null
    private var cleaned = false

    fun noteLaunch(component: ComponentName?) {
        val name = component?.className ?: return
        if (name in all && launchedAs == null) launchedAs = name
    }

    fun apply(context: Context) {
        enable(context, target(context))
    }

    fun onActivityLaunch(context: Context) {
        val keep = target(context)
        enable(context, keep)
        if (cleaned) return
        cleaned = true
        val started = launchedAs
        all.forEach { name ->
            if (name != keep && name != started) disable(context, name)
        }
    }

    private fun target(context: Context): String {
        return when {
            !SharingControl.isOn(context) -> OFF
            SharingControl.remaining(context) > 0L -> TIMER
            else -> ON
        }
    }

    private fun enable(context: Context, name: String) {
        set(context, name, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
    }

    private fun disable(context: Context, name: String) {
        set(context, name, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
    }

    private fun set(context: Context, name: String, state: Int) {
        val pm = context.packageManager
        val component = ComponentName(context.packageName, name)
        val current = pm.getComponentEnabledSetting(component)
        val enabledNow = when (current) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> name == ON
        }
        val enable = state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        if (enabledNow == enable) return
        pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
    }
}
