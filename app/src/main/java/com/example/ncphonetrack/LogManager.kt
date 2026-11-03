object LogManager {
    private data class Entry(val text: String, val detail: Int)
    private val logs = mutableListOf<Entry>()
    private const val MAX_LOGS = 300

    fun add(message: String, detail: Int = -1) {
        synchronized(logs) {
            logs.add(Entry("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $message", detail))
            if (logs.size > MAX_LOGS) logs.removeAt(0)
        }
    }

    fun lines(): List<String> = synchronized(logs) { logs.map { it.text } }

    fun entries(): List<Pair<String, Int>> = synchronized(logs) { logs.map { it.text to it.detail } }

    fun getLogs(): String = synchronized(logs) { logs.joinToString("\n") { it.text } }

    fun clear() = synchronized(logs) { logs.clear() }
}