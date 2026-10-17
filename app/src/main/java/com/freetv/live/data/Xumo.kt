package com.freetv.live.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Xumo Play (Comcast's free streaming service): channel list, schedule, and stream lookup.
 * Channels use "xumo://<channel id>" as their URL; the real stream is looked up when tuning in,
 * because Xumo's stream links are per session. Mirrors roku/source/Xumo.brs.
 */
object Xumo {
    private const val BASE = "https://valencia-app-mds.xumo.com/v2/"
    const val SCHEME = "xumo://"

    fun idOf(url: String): String? = if (url.startsWith(SCHEME)) url.removePrefix(SCHEME) else null

    fun channels(): List<Channel> {
        val data = JSONObject(get(BASE + "channels/list/10006.json?sort=hybrid&geoId=unknown"))
        val items = data.getJSONObject("channel").getJSONArray("item")
        return (0 until items.length()).mapNotNull { i ->
            val item = items.getJSONObject(i)
            val live = item.optJSONObject("properties")?.optString("is_live") == "true"
            val id = item.optJSONObject("guid")?.optString("value").orEmpty()
            val title = item.optString("title")
            if (!live || id.isEmpty() || title.isEmpty()) return@mapNotNull null
            val genre = item.optJSONArray("genre")?.optJSONObject(0)?.optString("value").orEmpty()
            Channel(
                url = SCHEME + id,
                name = M3uParser.cleanName(title),
                logo = "https://image.xumo.com/v1/channels/channel/$id/168x168.png?type=color_onBlack",
                group = genre.removeSuffix(" TV").ifEmpty { "Entertainment" },
                country = "US",
                tvgId = null,
                userAgent = null,
                referrer = null,
                source = "Xumo Play",
                english = true,
                official = true,
            )
        }
    }

    /** The next three hours of listings, keyed "xumo:<id>". */
    fun schedule(now: Long = System.currentTimeMillis()): Map<String, List<Programme>> {
        val out = HashMap<String, MutableMap<Long, Programme>>()
        val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val hourFormat = SimpleDateFormat("H", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
        for (hour in 0..2) {
            val t = now + hour * 3_600_000L
            var offset = 0
            var total = 1
            while (offset < total) {
                val page = runCatching {
                    JSONObject(get(BASE + "epg/10006/${dayFormat.format(t)}/${hourFormat.format(t)}.json" +
                        "?limit=50&offset=$offset&f=asset.title&f=asset.descriptions"))
                }.getOrNull() ?: break
                total = page.optInt("totalChannels", 0)
                val assets = page.optJSONObject("assets") ?: JSONObject()
                val chans = page.optJSONArray("channels") ?: break
                for (i in 0 until chans.length()) {
                    val ch = chans.getJSONObject(i)
                    val key = "xumo:" + ch.optLong("channelId")
                    val slots = ch.optJSONArray("schedule") ?: continue
                    for (j in 0 until slots.length()) {
                        val slot = slots.getJSONObject(j)
                        val start = runCatching { iso.parse(slot.getString("start"))!!.time }.getOrNull() ?: continue
                        val stop = runCatching { iso.parse(slot.getString("end"))!!.time }.getOrNull() ?: continue
                        val asset = assets.optJSONObject(slot.optString("assetId"))
                        val desc = asset?.optJSONObject("descriptions")?.let { it.optString("medium").ifEmpty { it.optString("small") } }
                        out.getOrPut(key) { HashMap() }[start] =
                            Programme(asset?.optString("title")?.ifEmpty { null } ?: "Live programming", start, stop, desc?.ifEmpty { null })
                    }
                }
                offset += 50
            }
        }
        return out.mapValues { (_, v) -> v.values.filter { it.stop > now }.sortedBy { it.start }.take(8) }
    }

    /** Looks up the stream for a Xumo channel right now. */
    fun resolve(id: String): String {
        val now = System.currentTimeMillis()
        val hour = SimpleDateFormat("H", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)
        val broadcast = JSONObject(get(BASE + "channels/channel/$id/broadcast.json?hour=$hour"))
        val assets = broadcast.getJSONArray("assets")
        var assetId = assets.getJSONObject(0).getString("id")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val ts = a.optJSONObject("timestamps") ?: continue
            if (ts.optLong("start") * 1000 <= now && ts.optLong("end") * 1000 > now) assetId = a.getString("id")
        }
        val providers = JSONObject(get(BASE + "assets/asset/$assetId.json?f=providers")).getJSONArray("providers")
        for (i in 0 until providers.length()) {
            val sources = providers.getJSONObject(i).optJSONArray("sources") ?: continue
            for (j in 0 until sources.length()) {
                val src = sources.getJSONObject(j)
                val uri = src.optString("uri")
                if (uri.contains(".m3u8") || src.optString("produces").contains("mpegURL")) {
                    return uri.replace("[PLATFORM]", "web")
                }
            }
        }
        throw IllegalStateException("No stream for this channel right now")
    }

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "LiveTV/1.0")
        }
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
