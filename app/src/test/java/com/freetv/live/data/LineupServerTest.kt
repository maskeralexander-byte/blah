package com.freetv.live.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class LineupServerTest {
    @Test
    fun servesLineupJsonOverHttp() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = LineupServer(ipAddress = { "127.0.0.1" }) { """{"channels":[{"name":"Spotlight"}]}""" }
        try {
            val url = server.start(scope)
            assumeNotNull(url)
            repeat(2) { // The TV fetches repeatedly; the server must keep answering.
                val conn = URL(url).openConnection() as HttpURLConnection
                assertEquals(200, conn.responseCode)
                assertEquals("application/json; charset=utf-8", conn.contentType)
                assertEquals("""{"channels":[{"name":"Spotlight"}]}""", conn.inputStream.bufferedReader().readText())
                conn.disconnect()
            }
        } finally {
            server.stop()
            scope.cancel()
        }
    }
}
