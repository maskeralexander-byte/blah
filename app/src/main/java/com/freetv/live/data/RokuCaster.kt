package com.freetv.live.data

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

data class RokuDevice(val host: String, val name: String)

/**
 * Casts streams to Roku TVs and players using Roku's External Control Protocol (ECP):
 * SSDP to find devices on the local network, then an HTTP call on port 8060 that opens the
 * stream in Roku's built-in media player.
 */
class RokuCaster(private val context: Context) {

    /** Finds Rokus on the local network. Takes about [timeoutMs]. */
    suspend fun discover(timeoutMs: Int = 3_000): List<RokuDevice> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("roku-discovery").apply { setReferenceCounted(false); acquire() }
        val hosts = LinkedHashSet<String>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 500
                val search = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: roku:ecp\r\n\r\n"
                    ).toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                // UDP is lossy, so send the search a few times.
                repeat(3) { socket.send(DatagramPacket(search, search.size, group, 1900)) }

                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length)
                    val location = text.lineSequence()
                        .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim()
                    val host = location?.let { Uri.parse(it).host } ?: packet.address.hostAddress
                    if (host != null) hosts += host
                }
            }
        } finally {
            lock.release()
        }
        hosts.map { RokuDevice(it, deviceName(it) ?: "Roku ($it)") }
    }

    /** Checks a manually entered IP address; returns null if no Roku answers there. */
    suspend fun probe(host: String): RokuDevice? = withContext(Dispatchers.IO) {
        deviceName(host.trim())?.let { RokuDevice(host.trim(), it) }
    }

    /** Opens [channel]'s stream on the Roku. Throws with a readable message on failure. */
    suspend fun play(device: RokuDevice, channel: Channel) = withContext(Dispatchers.IO) {
        val query = listOf(
            "t" to "v",
            "u" to channel.url,
            "k" to "(null)",
            "videoName" to channel.name,
            "videoFormat" to formatOf(channel.url),
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        // 15985 is Roku's built-in "Play on Roku" media player.
        post(device, "/input/15985?$query")
    }

    /** Returns the Roku to its home screen, which stops playback. */
    suspend fun stop(device: RokuDevice) = withContext(Dispatchers.IO) { post(device, "/keypress/Home") }

    private fun post(device: RokuDevice, path: String) {
        val conn = (URL("http://${device.host}:8060$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 4_000
            readTimeout = 6_000
            doOutput = true
            setFixedLengthStreamingMode(0)
        }
        try {
            conn.outputStream.close()
            when (val code = conn.responseCode) {
                in 200..299 -> Unit
                403 -> throw IllegalStateException(
                    "The Roku refused the request. On the Roku, go to Settings → System → Advanced system " +
                        "settings → Control by mobile apps and set Network access to Default or Permissive."
                )
                else -> throw IllegalStateException("The Roku answered HTTP $code.")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun deviceName(host: String): String? = try {
        val conn = (URL("http://$host:8060/query/device-info").openConnection() as HttpURLConnection).apply {
            connectTimeout = 2_500
            readTimeout = 2_500
        }
        try {
            val xml = conn.inputStream.bufferedReader().use { it.readText() }
            listOf("user-device-name", "friendly-device-name", "friendly-model-name")
                .firstNotNullOfOrNull { tag ->
                    Regex("<$tag>([^<]+)</$tag>").find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                } ?: "Roku"
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    private fun formatOf(url: String): String {
        val path = Uri.parse(url).path.orEmpty().lowercase()
        return when {
            path.endsWith(".mp4") || path.endsWith(".m4v") -> "mp4"
            path.endsWith(".mkv") -> "mkv"
            path.endsWith(".mpd") -> "dash"
            else -> "hls"
        }
    }
}
