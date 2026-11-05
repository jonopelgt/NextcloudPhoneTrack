package com.example.ncphonetrack

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LauncherIcon.noteLaunch(intent?.component)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
