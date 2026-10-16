package com.freetv.live.data

data class Channel(
    val url: String,
    val name: String,
    val logo: String?,
    val group: String,
    val country: String?,
    val tvgId: String?,
    val userAgent: String?,
    val referrer: String?,
    val source: String,
)
