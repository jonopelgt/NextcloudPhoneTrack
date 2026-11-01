package com.example.ncphonetrack

object PhoneTrackUrl {
    data class Parsed(val base: String, val token: String?)

    fun parse(raw: String): Parsed {
        val trimmed = raw.trim().removeSuffix("/")
        val marker = listOf("/index.php/apps/phonetrack", "/apps/phonetrack")
            .firstOrNull { trimmed.contains(it) } ?: return Parsed(trimmed, null)
        val cut = trimmed.indexOf(marker)
        val base = trimmed.substring(0, cut).removeSuffix("/")
        val rest = trimmed.substring(cut + marker.length).trim('/')
        val parts = rest.split('/').filter { it.isNotEmpty() }
        val token = when (parts.firstOrNull()) {
            "publicWebLog", "logPost", "logGet" -> parts.getOrNull(1)
            else -> null
        }
        return Parsed(if (base.isBlank()) trimmed else base, token)
    }
}
