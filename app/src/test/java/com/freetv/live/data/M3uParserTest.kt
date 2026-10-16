package com.freetv.live.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {
    @Test
    fun parsesIptvOrgEntryWithVlcOptions() {
        val text = """
            #EXTM3U x-tvg-url="https://example.com/guide.xml.gz"
            #EXTINF:-1 tvg-id="1Plus1International.ua@SD" tvg-logo="https://i.imgur.com/LOY0rtp.png" http-user-agent="UA-attr" group-title="General",1+1 International
            #EXTVLCOPT:http-user-agent=Mozilla/5.0 Test
            #EXTVLCOPT:http-referrer=https://ref.example/
            https://stream.example/live.m3u8
        """.trimIndent()

        val c = M3uParser.parse(text, "test").single()
        assertEquals("1+1 International", c.name)
        assertEquals("General", c.group)
        assertEquals("UA", c.country)
        assertEquals("Mozilla/5.0 Test", c.userAgent)
        assertEquals("https://ref.example/", c.referrer)
        assertEquals("https://stream.example/live.m3u8", c.url)
    }

    @Test
    fun usesTvgCountryAndStripsMarkers() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-name="Kanali 7" tvg-id="Kanali7.al" tvg-country="AL" group-title="Albania",Kanali 7 Ⓢ
            https://fe.example/105.m3u8
        """.trimIndent()

        val c = M3uParser.parse(text, "test").single()
        assertEquals("Kanali 7", c.name)
        assertEquals("AL", c.country)
        assertEquals("Albania", c.group)
    }

    @Test
    fun handlesCommasInsideAttributesAndMissingGroup() {
        val text = """
            #EXTINF:-1 tvg-name="News, Weather",Local News, Weather
            http://x.example/a.ts
        """.trimIndent()

        val c = M3uParser.parse(text, "test").single()
        assertEquals("Local News, Weather", c.name)
        assertEquals("Other", c.group)
        assertNull(c.country)
    }

    @Test
    fun takesFirstOfMultipleGroups() {
        val text = "#EXTINF:-1 group-title=\"Movies;Series\",Flix\nhttp://x/a.m3u8"
        assertEquals("Movies", M3uParser.parse(text, "t").single().group)
    }
}
