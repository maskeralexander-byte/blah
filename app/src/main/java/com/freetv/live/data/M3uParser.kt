package com.freetv.live.data

import java.util.Locale

/** Parses extended M3U playlists (#EXTINF with tvg-* attributes, #EXTVLCOPT headers). */
object M3uParser {
    private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(text: String, sourceName: String): List<Channel> {
        val out = ArrayList<Channel>()
        var info: String? = null
        var userAgent: String? = null
        var referrer: String? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    info = line
                    userAgent = null
                    referrer = null
                }
                line.startsWith("#EXTVLCOPT", ignoreCase = true) -> {
                    val opt = line.substringAfter(':')
                    val key = opt.substringBefore('=').trim().lowercase(Locale.ROOT)
                    val value = opt.substringAfter('=', "").trim()
                    when (key) {
                        "http-user-agent" -> userAgent = value
                        "http-referrer", "http-referer" -> referrer = value
                    }
                }
                line.startsWith("#") -> Unit
                info != null -> {
                    out += buildChannel(info, line, userAgent, referrer, sourceName)
                    info = null
                }
            }
        }
        return out
    }

    private fun buildChannel(
        info: String,
        url: String,
        vlcUserAgent: String?,
        vlcReferrer: String?,
        sourceName: String,
    ): Channel {
        val attrs = attrRegex.findAll(info).associate { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2] }
        // The display name follows the first comma that sits outside of quoted attribute values.
        val name = displayName(info).ifBlank { attrs["tvg-name"].orEmpty() }.ifBlank { "Unknown" }
        val tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() }
        val group = attrs["group-title"]
            ?.split(';')?.firstOrNull { it.isNotBlank() }?.trim()
            ?.takeUnless { it.equals("Undefined", ignoreCase = true) }
            ?: "Other"
        return Channel(
            url = url,
            name = name,
            logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
            group = group,
            country = countryOf(attrs["tvg-country"], tvgId),
            tvgId = tvgId,
            userAgent = vlcUserAgent ?: attrs["http-user-agent"],
            referrer = vlcReferrer ?: attrs["http-referrer"],
            source = sourceName,
        )
    }

    private fun displayName(info: String): String {
        var inQuotes = false
        var nameStart = -1
        for (i in info.indices) {
            val ch = info[i]
            if (ch == '"') inQuotes = !inQuotes
            if (ch == ',' && !inQuotes) { nameStart = i; break }
        }
        if (nameStart < 0) return ""
        // Drop decorations like "Ⓢ" / "Ⓖ" that some playlists append.
        return info.substring(nameStart + 1).replace(Regex("[Ⓐ-ⓩ]"), "").trim()
    }

    /** Country code from tvg-country, or from an iptv-org style id like "CNN.us@SD". */
    internal fun countryOf(tvgCountry: String?, tvgId: String?): String? {
        tvgCountry?.split(';', ',')?.firstOrNull()?.trim()?.takeIf { it.length == 2 }?.let {
            return it.uppercase(Locale.ROOT)
        }
        val suffix = tvgId?.substringBefore('@')?.substringAfterLast('.', "") ?: return null
        return suffix.takeIf { it.length == 2 && it.all(Char::isLetter) }?.uppercase(Locale.ROOT)
    }
}
