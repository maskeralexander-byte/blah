package com.freetv.live.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialAndGuideTest {
    @Test
    fun officialServicesAreRecognised() {
        assertTrue(isOfficialStream("https://jmp2.uk/plu-62ba60f059624e000781c436.m3u8"))
        assertTrue(isOfficialStream("https://cdn-uw2-prod.tsv2.amagi.tv/linear/amg00001/playlist.m3u8"))
        assertTrue(isOfficialStream("https://dai.google.com/linear/hls/event/abc/master.m3u8"))
    }

    @Test
    fun unverifiedHostsAreNotOfficial() {
        assertFalse(isOfficialStream("http://57.128.231.171:8080/293/index.m3u8"))
        assertFalse(isOfficialStream("http://tvsen7.aynascope.net/cartoonnetwork/index.m3u8"))
        // A known domain on a custom port is still treated as unverified.
        assertFalse(isOfficialStream("http://foo.cloudfront.net:8000/live.m3u8"))
        assertFalse(isOfficialStream("not a url"))
    }

    @Test
    fun guideKeysMatchPlutoIdsAndNames() {
        val c = Channel(
            url = "https://jmp2.uk/plu-5ba3fb9c4b078e0f37ad34e8.m3u8",
            name = "Pluto TV Spotlight (720p)", logo = null, group = "Movies", country = "US",
            tvgId = null, userAgent = null, referrer = null, source = "t",
        )
        assertEquals(listOf("id:5ba3fb9c4b078e0f37ad34e8", "name:spotlight"), Guide.keysFor(c))
        assertEquals("spotlight", Guide.normalizeName("Spotlight"))
    }

    @Test
    fun nowAndNextPicksCurrentAndFollowingShow() {
        val c = Channel("https://x/a.m3u8", "News Now", null, "News", null, null, null, null, "t")
        val guide = Guide(
            mapOf(
                "name:newsnow" to listOf(
                    Programme("Old", 0, 100, null),
                    Programme("Current", 100, 200, null),
                    Programme("Later", 200, 300, null),
                )
            )
        )
        val (now, next) = guide.nowAndNext(c, now = 150)
        assertEquals("Current", now?.title)
        assertEquals("Later", next?.title)
    }
}
