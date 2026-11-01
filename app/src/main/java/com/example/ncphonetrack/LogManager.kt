package com.example.ncphonetrack

object LogManager {
    private val logs = mutableListOf<String>()
    private const val MAX_LOGS = 100

    fun add(message: String) {
        synchronized(logs) {
            logs.add(0, "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $message")
            if (logs.size > MAX_LOGS) logs.removeAt(logs.size - 1)
        }
    }

    fun getLogs(): String = synchronized(logs) { logs.joinToString("\n") }

    fun clear() = synchronized(logs) { logs.clear() }
}
