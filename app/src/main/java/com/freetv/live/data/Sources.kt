package com.freetv.live.data

data class PlaylistSource(val id: String, val name: String, val url: String, val builtIn: Boolean)

/**
 * Community-maintained lists of publicly available, free-to-air streams
 * (broadcasters' own streams and free ad-supported services like Pluto TV).
 */
val BuiltInSources = listOf(
    PlaylistSource(
        id = "iptv-org",
        name = "iptv-org (worldwide)",
        url = "https://iptv-org.github.io/iptv/index.m3u",
        builtIn = true,
    ),
    PlaylistSource(
        id = "free-tv",
        name = "Free-TV",
        url = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8",
        builtIn = true,
    ),
)
