package com.freetv.live.data

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

data class Programme(val title: String, val start: Long, val stop: Long, val description: String?)

/** Now/next listings, looked up by Pluto channel id or by normalised channel name. */
class Guide(private val byKey: Map<String, List<Programme>>) {
    val isEmpty get() = byKey.isEmpty()

    fun programmesFor(channel: Channel, now: Long = System.currentTimeMillis()): List<Programme> {
        val list = keysFor(channel).firstNotNullOfOrNull { byKey[it] } ?: return emptyList()
        return list.filter { it.stop > now }
    }

    fun nowAndNext(channel: Channel, now: Long = System.currentTimeMillis()): Pair<Programme?, Programme?> {
        val upcoming = programmesFor(channel, now)
        val current = upcoming.firstOrNull { it.start <= now }
        val next = upcoming.firstOrNull { it.start > now }
        return current to next
    }

    companion object {
        val Empty = Guide(emptyMap())

        private val plutoId = Regex("""(?:jmp2\.uk/plu-|pluto\.tv/.*?/channel/|/stitch/hls/channel/)([0-9a-f]{24})""")

        fun keysFor(channel: Channel): List<String> = listOfNotNull(
            Xumo.idOf(channel.url)?.let { "xumo:$it" },
            plutoId.find(channel.url)?.groupValues?.get(1)?.let { "id:$it" },
            "name:" + normalizeName(channel.name),
        )

        /** "Pluto TV Spotlight (720p)" and "Spotlight" both become "spotlight". */
        fun normalizeName(name: String): String = name
            .replace(Regex("""\(.*?\)|\[.*?]"""), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("""^(pluto tv|samsung|plex)\s+"""), "")
            .replace(Regex("[^a-z0-9]"), "")
    }
}

/**
 * Downloads XMLTV guides for the big free streaming services (Pluto TV, Samsung TV Plus, Plex),
 * published by i.mjh.nz, and keeps the next day of listings.
 */
class GuideRepository(context: Context) {
    private val dir = File(context.filesDir, "guide").apply { mkdirs() }

    private data class Feed(val id: String, val url: String, val keyById: Boolean)

    private val feeds = listOf(
        Feed("pluto", "$BASE/PlutoTV/all.xml.gz", keyById = true),
        Feed("samsung", "$BASE/SamsungTVPlus/us.xml.gz", keyById = false),
        Feed("plex", "$BASE/Plex/us.xml.gz", keyById = false),
    )

    suspend fun load(forceRefresh: Boolean): Guide = withContext(Dispatchers.IO) {
        val merged = HashMap<String, List<Programme>>()
        for (feed in feeds) {
            val file = File(dir, "${feed.id}.xml.gz")
            val stale = !file.exists() || System.currentTimeMillis() - file.lastModified() > CACHE_MS
            if (forceRefresh || stale) runCatching { download(feed.url, file) }
            if (!file.exists()) continue
            runCatching {
                GZIPInputStream(file.inputStream().buffered()).use { parse(it, feed.keyById) }
            }.getOrNull()?.forEach { (k, v) -> merged.putIfAbsent(k, v) }
        }
        runCatching { Xumo.schedule() }.getOrNull()?.forEach { (k, v) -> merged.putIfAbsent(k, v) }
        Guide(merged)
    }

    private fun download(url: String, dest: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "FreeLiveTV/1.0")
        }
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            val tmp = File(dest.path + ".part")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(dest)
        } finally {
            conn.disconnect()
        }
    }

    /** Streams the XMLTV file, keeping programmes that end after now and start within [WINDOW_MS]. */
    private fun parse(input: InputStream, keyById: Boolean): Map<String, List<Programme>> {
        val now = System.currentTimeMillis()
        val until = now + WINDOW_MS
        val names = HashMap<String, String>()
        val out = HashMap<String, MutableList<Programme>>()
        val timeFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)

        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)

        var channelId: String? = null
        var progChannel: String? = null
        var start = 0L
        var stop = 0L
        var title: String? = null
        var desc: String? = null

        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "channel" -> channelId = p.getAttributeValue(null, "id")
                    "display-name" -> if (channelId != null && channelId !in names) {
                        names[channelId!!] = p.nextText()
                    }
                    "programme" -> {
                        progChannel = p.getAttributeValue(null, "channel")
                        start = parseTime(timeFormat, p.getAttributeValue(null, "start"))
                        stop = parseTime(timeFormat, p.getAttributeValue(null, "stop"))
                        title = null
                        desc = null
                    }
                    "title" -> if (progChannel != null) title = p.nextText()
                    "desc" -> if (progChannel != null) desc = p.nextText()
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "channel" -> channelId = null
                    "programme" -> {
                        val ch = progChannel
                        if (ch != null && title != null && stop > now && start < until) {
                            val key = if (keyById) "id:$ch" else names[ch]?.let { "name:" + Guide.normalizeName(it) }
                            if (key != null) out.getOrPut(key) { ArrayList() } += Programme(title!!, start, stop, desc)
                        }
                        progChannel = null
                    }
                }
            }
        }
        out.values.forEach { list -> list.sortBy { it.start } }
        return out
    }

    private fun parseTime(f: SimpleDateFormat, s: String?): Long =
        s?.let { runCatching { f.parse(it)?.time }.getOrNull() } ?: 0L

    private companion object {
        const val BASE = "https://raw.githubusercontent.com/matthuisman/i.mjh.nz/master"
        const val CACHE_MS = 6L * 60 * 60 * 1000
        const val WINDOW_MS = 24L * 60 * 60 * 1000
    }
}
