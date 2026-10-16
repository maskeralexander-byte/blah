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
 * stream in the Free Live TV receiver channel (see /roku), or in the built-in player on older Rokus.
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
        val params = buildList {
            add("contentId" to channel.url)
            add("mediaType" to "live")
            add("title" to channel.name)
            add("format" to formatOf(channel.url))
            channel.userAgent?.let { add("ua" to it) }
            channel.referrer?.let { add("ref" to it) }
        }.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

        // 1. The Free Live TV receiver channel (sideloaded as "dev"): if it's already open,
        //    hand it the new channel; otherwise launch it.
        if (activeAppId(device) == "dev") {
            post(device, "/input?$params")
            return@withContext
        }
        if (post(device, "/launch/dev?$params", allowNotFound = true)) return@withContext

        // 2. Older Rokus have a built-in "Play on Roku" player (app 15985).
        val legacy = listOf(
            "t" to "v",
            "u" to channel.url,
            "k" to "(null)",
            "videoName" to channel.name,
            "videoFormat" to formatOf(channel.url),
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        if (post(device, "/input/15985?$legacy", allowNotFound = true)) return@withContext

        throw ReceiverMissingException()
    }

    /** Thrown when the Roku has neither the receiver channel nor the built-in player. */
    class ReceiverMissingException : IllegalStateException(
        "This Roku needs the Free Live TV receiver installed first (a one-time setup)."
    )

    private fun activeAppId(device: RokuDevice): String? = try {
        val conn = (URL("http://${device.host}:8060/query/active-app").openConnection() as HttpURLConnection).apply {
            connectTimeout = 2_500
            readTimeout = 2_500
        }
        try {
            val xml = conn.inputStream.bufferedReader().use { it.readText() }
            Regex("""<app[^>]*\bid="([^"]+)"""").find(xml)?.groupValues?.get(1)
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Installs the bundled receiver channel (assets/roku-receiver.zip) on a Roku that has
     * Developer Mode turned on, using the dev web installer and the password set when enabling it.
     */
    suspend fun installReceiver(device: RokuDevice, password: String) = withContext(Dispatchers.IO) {
        val zip = context.assets.open("roku-receiver.zip").use { it.readBytes() }
        val path = "/plugin_install"
        val url = URL("http://${device.host}$path")

        fun send(auth: String?): HttpURLConnection {
            val boundary = "----FreeLiveTV${System.nanoTime()}"
            val body = java.io.ByteArrayOutputStream().apply {
                fun field(name: String, value: String) = write(
                    "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray()
                )
                field("mysubmit", "Install")
                write(
                    ("--$boundary\r\nContent-Disposition: form-data; name=\"archive\"; filename=\"receiver.zip\"\r\n" +
                        "Content-Type: application/zip\r\n\r\n").toByteArray()
                )
                write(zip)
                write("\r\n--$boundary--\r\n".toByteArray())
            }.toByteArray()
            return (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 60_000
                doOutput = true
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                auth?.let { setRequestProperty("Authorization", it) }
                setFixedLengthStreamingMode(body.size)
                outputStream.use { it.write(body) }
            }
        }

        // The installer uses HTTP digest auth: the first request gets the challenge.
        val first = try {
            send(null)
        } catch (e: java.net.ConnectException) {
            throw IllegalStateException("Developer Mode isn't on for this Roku (nothing answered on port 80).")
        }
        val challenge = first.getHeaderField("WWW-Authenticate")
        val firstCode = first.responseCode
        first.disconnect()
        if (firstCode != 401 || challenge == null) throw IllegalStateException("Unexpected installer reply (HTTP $firstCode).")

        val conn = send(digestHeader(challenge, "rokudev", password, "POST", path))
        try {
            val code = conn.responseCode
            if (code == 401) throw IllegalStateException("Wrong Developer Mode password.")
            val html = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val ok = code in 200..299 &&
                (html.contains("Install Success", true) || html.contains("Identical to previous version", true) ||
                    html.contains("Received", true))
            if (!ok) throw IllegalStateException("The Roku didn't accept the install (HTTP $code).")
        } finally {
            conn.disconnect()
        }
    }

    private fun digestHeader(challenge: String, user: String, password: String, method: String, uri: String): String {
        val parts = Regex("""(\w+)="?([^",]*)"?""").findAll(challenge.substringAfter("Digest", challenge))
            .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        val realm = parts["realm"].orEmpty()
        val nonce = parts["nonce"].orEmpty()
        val qop = parts["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }
        val cnonce = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val nc = "00000001"
        val ha1 = md5("$user:$realm:$password")
        val ha2 = md5("$method:$uri")
        val response = if (qop != null) md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2") else md5("$ha1:$nonce:$ha2")
        return buildString {
            append("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\"")
            parts["opaque"]?.let { append(", opaque=\"$it\"") }
            if (qop != null) append(", qop=$qop, nc=$nc, cnonce=\"$cnonce\"")
        }
    }

    private fun md5(s: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Returns the Roku to its home screen, which stops playback. */
    suspend fun stop(device: RokuDevice) = withContext(Dispatchers.IO) { post(device, "/keypress/Home"); Unit }

    /** Returns false only for a 404 when [allowNotFound] is set; other failures throw. */
    private fun post(device: RokuDevice, path: String, allowNotFound: Boolean = false): Boolean {
        val conn = (URL("http://${device.host}:8060$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 4_000
            readTimeout = 6_000
            doOutput = true
            setFixedLengthStreamingMode(0)
        }
        try {
            conn.outputStream.close()
            return when (val code = conn.responseCode) {
                in 200..299 -> true
                404 -> if (allowNotFound) false else throw IllegalStateException("The Roku answered HTTP 404.")
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
