package com.freetv.live.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket

/**
 * A tiny HTTP server on the local network that serves the channel lineup (with guide data) to the
 * Roku receiver, so the TV can change channels and show the guide with its own remote.
 */
class LineupServer(
    private val ipAddress: () -> String? = ::localIp,
    private val body: () -> String,
) {
    private var socket: ServerSocket? = null
    private var job: Job? = null

    /** Starts the server if needed and returns the lineup URL, or null without a LAN address. */
    fun start(scope: CoroutineScope): String? {
        val ip = ipAddress() ?: return null
        // A fixed port keeps the address the TV saved valid across app restarts.
        val server = socket ?: (runCatching { ServerSocket(PORT) }.getOrNull() ?: ServerSocket(0)).also { socket = it }
        if (job?.isActive != true) {
            job = scope.launch(Dispatchers.IO) {
                while (isActive && !server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    launch {
                        client.use { c ->
                            c.soTimeout = 5_000
                            val reader = c.getInputStream().bufferedReader()
                            // Read and ignore the request; there's only one resource.
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                            }
                            val bytes = runCatching { body() }.getOrDefault("{\"channels\":[]}").toByteArray()
                            c.getOutputStream().apply {
                                write(
                                    ("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
                                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()
                                )
                                write(bytes)
                                flush()
                            }
                        }
                    }
                }
            }
        }
        return "http://$ip:${server.localPort}/lineup.json"
    }

    fun stop() {
        job?.cancel()
        runCatching { socket?.close() }
        socket = null
    }

    private companion object {
        const val PORT = 8765
    }
}

/** This phone's IPv4 address on the local network, preferring Wi-Fi. */
private fun localIp(): String? = NetworkInterface.getNetworkInterfaces().toList()
    .filter { it.isUp && !it.isLoopback }
    .sortedByDescending { it.name.startsWith("wlan") }
    .flatMap { it.inetAddresses.toList() }
    .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    ?.hostAddress
