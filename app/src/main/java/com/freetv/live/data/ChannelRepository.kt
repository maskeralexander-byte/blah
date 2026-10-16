package com.freetv.live.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

data class LoadResult(val channels: List<Channel>, val errors: List<String>)

class ChannelRepository(context: Context) {
    private val prefs = context.getSharedPreferences("freetv", Context.MODE_PRIVATE)
    private val cacheDir = File(context.filesDir, "playlists").apply { mkdirs() }

    // ---- Sources ----

    fun customSources(): List<PlaylistSource> {
        val arr = JSONArray(prefs.getString(KEY_CUSTOM, "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            PlaylistSource(o.getString("id"), o.getString("name"), o.getString("url"), builtIn = false)
        }
    }

    fun allSources(): List<PlaylistSource> = BuiltInSources + customSources()

    fun disabledSources(): Set<String> = prefs.getStringSet(KEY_DISABLED, emptySet())!!.toSet()

    fun setSourceEnabled(id: String, enabled: Boolean) {
        val set = disabledSources().toMutableSet()
        if (enabled) set -= id else set += id
        prefs.edit().putStringSet(KEY_DISABLED, set).apply()
    }

    fun addCustomSource(name: String, url: String) {
        val list = customSources() + PlaylistSource("custom-${System.currentTimeMillis()}", name, url, false)
        saveCustom(list)
    }

    fun removeCustomSource(id: String) {
        saveCustom(customSources().filterNot { it.id == id })
        File(cacheDir, "$id.m3u").delete()
    }

    private fun saveCustom(list: List<PlaylistSource>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url)) }
        prefs.edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }

    // ---- Favorites / recents ----

    fun favorites(): Set<String> = prefs.getStringSet(KEY_FAVS, emptySet())!!.toSet()

    fun setFavorite(url: String, fav: Boolean) {
        val set = favorites().toMutableSet()
        if (fav) set += url else set -= url
        prefs.edit().putStringSet(KEY_FAVS, set).apply()
    }

    fun recents(): List<String> {
        val arr = JSONArray(prefs.getString(KEY_RECENTS, "[]"))
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun addRecent(url: String) {
        val list = (listOf(url) + recents().filterNot { it == url }).take(50)
        prefs.edit().putString(KEY_RECENTS, JSONArray(list).toString()).apply()
    }

    // ---- Channel health ----

    /** URL -> time it last failed to play. Entries older than [FAIL_TTL_MS] are dropped. */
    fun failures(): Map<String, Long> {
        val o = JSONObject(prefs.getString(KEY_FAILED, "{}")!!)
        val cutoff = System.currentTimeMillis() - FAIL_TTL_MS
        return o.keys().asSequence().associateWith { o.getLong(it) }.filterValues { it > cutoff }
    }

    fun setFailed(url: String, failed: Boolean) {
        val map = failures().toMutableMap()
        if (failed) map[url] = System.currentTimeMillis() else map -= url
        prefs.edit().putString(KEY_FAILED, JSONObject(map as Map<*, *>).toString()).apply()
    }

    var hideFailed: Boolean
        get() = prefs.getBoolean(KEY_HIDE_FAILED, true)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_FAILED, value).apply()

    // ---- Loading ----

    /** Loads every enabled source in parallel; falls back to the cached copy when offline. */
    suspend fun load(forceRefresh: Boolean): LoadResult = withContext(Dispatchers.IO) {
        val disabled = disabledSources()
        val sources = allSources().filterNot { it.id in disabled }
        val results = coroutineScope {
            sources.map { src -> async { loadSource(src, forceRefresh) } }.awaitAll()
        }
        val seen = HashSet<String>()
        val channels = results.flatMap { it.first }.filter { seen.add(it.url) }
        LoadResult(channels, results.mapNotNull { it.second })
    }

    private fun loadSource(src: PlaylistSource, forceRefresh: Boolean): Pair<List<Channel>, String?> {
        val cache = File(cacheDir, "${src.id}.m3u")
        val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < CACHE_MS
        var error: String? = null
        if (forceRefresh || !fresh) {
            try {
                cache.writeText(download(src.url))
            } catch (e: Exception) {
                error = "${src.name}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        val text = if (cache.exists()) cache.readText() else return emptyList<Channel>() to error
        return M3uParser.parse(text, src.name) to error
    }

    private fun download(url: String): String {
        var current = url
        repeat(5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "FreeLiveTV/1.0")
                setRequestProperty("Accept-Encoding", "gzip")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    // Follows https->http and cross-host redirects, which HttpURLConnection skips.
                    current = URL(URL(current), conn.getHeaderField("Location")).toString()
                    return@repeat
                }
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                val stream = if (conn.contentEncoding.equals("gzip", true) || current.endsWith(".gz")) {
                    GZIPInputStream(conn.inputStream)
                } else {
                    conn.inputStream
                }
                return stream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("Too many redirects")
    }

    private companion object {
        const val KEY_CUSTOM = "custom_sources"
        const val KEY_DISABLED = "disabled_sources"
        const val KEY_FAVS = "favorites"
        const val KEY_RECENTS = "recents"
        const val KEY_FAILED = "failed"
        const val KEY_HIDE_FAILED = "hide_failed"
        const val FAIL_TTL_MS = 24L * 60 * 60 * 1000
        const val CACHE_MS = 12L * 60 * 60 * 1000
    }
}
