package com.freetv.live.data

import java.net.URI

/**
 * Hosts used by official free streaming services and broadcasters. Streams served from anywhere
 * else (bare IP addresses, custom ports, unknown hosts) are treated as unverified, since that's
 * where unofficial rebroadcasts live.
 */
private val OfficialDomains = listOf(
    // Free ad-supported services and their delivery partners.
    "jmp2.uk", "pluto.tv", "samsung.com", "plex.tv", "roku.com", "xumo.com", "tubi.video",
    "amagi.tv", "wurl.tv", "wurl.com", "frequency.stream", "stingray.com", "getpublica.com",
    "klowdtv.com", "rakuten.tv", "redbox.com", "vizio.com", "trace.plus",
    // Broadcasters and public channels.
    "pbs.org", "cbsnews.com", "cbs.com", "nbcuni.com", "foxnews.com", "bloomberg.com", "nasa.gov",
    "warnermediacdn.com", "cablecast.tv", "telvue.com", "swagit.com", "streamhoster.com",
    "sinclairstoryline.com",
    // Major commercial CDNs that broadcasters use directly.
    "akamaized.net", "akamaihd.net", "edgesuite.net", "cloudfront.net", "llnwd.net",
    "uplynk.com", "brightcove.com", "dai.google.com", "fastly.net",
)

fun isOfficialStream(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    if (uri.port != -1) return false
    val host = uri.host?.lowercase() ?: return false
    return OfficialDomains.any { host == it || host.endsWith(".$it") }
}
