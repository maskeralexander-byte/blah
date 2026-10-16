package com.freetv.live.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * Reads the channel list out of Pluto TV's US XMLTV guide, so every Pluto US channel can be offered
 * even when the community playlists skip it. Streams use the same jmp2.uk redirect as the playlists'
 * own Pluto entries. Mirrors roku/source/Lineup.brs.
 */
object PlutoChannels {
    const val GUIDE_URL = "https://raw.githubusercontent.com/matthuisman/i.mjh.nz/master/PlutoTV/us.xml.gz"
    private val idInUrl = Regex("""jmp2\.uk/plu-([0-9a-f]{24})""")

    fun idOf(url: String): String? = idInUrl.find(url)?.groupValues?.get(1)

    fun parse(input: InputStream): List<Channel> {
        data class Meta(val name: String, val logo: String?, var category: String? = null)
        val meta = LinkedHashMap<String, Meta>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)

        var channelId: String? = null
        var name: String? = null
        var logo: String? = null
        var programmeChannel: String? = null
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "channel" -> { channelId = p.getAttributeValue(null, "id"); name = null; logo = null }
                    "display-name" -> if (channelId != null && name == null) name = p.nextText()
                    "icon" -> if (channelId != null && logo == null) logo = p.getAttributeValue(null, "src")
                    "programme" -> programmeChannel = p.getAttributeValue(null, "channel")
                    // A channel's category comes from its programmes (the generic "Series" is skipped).
                    "category" -> {
                        val m = programmeChannel?.let { meta[it] }
                        val text = p.nextText()
                        if (m != null && m.category == null && text.isNotBlank() && text != "Series") m.category = text
                    }
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "channel" -> {
                        val id = channelId
                        if (id != null && !name.isNullOrBlank()) meta[id] = Meta(name!!, logo)
                        channelId = null
                    }
                    "programme" -> programmeChannel = null
                }
            }
        }
        return meta.map { (id, m) ->
            Channel(
                url = "https://jmp2.uk/plu-$id.m3u8",
                name = m.name,
                logo = m.logo,
                group = categoryLabel(m.category),
                country = "US",
                tvgId = null,
                userAgent = null,
                referrer = null,
                source = "Pluto TV",
                english = true,
                official = true,
            )
        }
    }

    fun categoryLabel(cat: String?): String = when (cat) {
        null, "" -> "Entertainment"
        "News & Information" -> "News"
        "Children" -> "Kids"
        "Animated" -> "Animation"
        "Movie" -> "Movies"
        "Human Interest" -> "Lifestyle"
        "Sports event", "Sports non-event" -> "Sports"
        else -> cat
    }
}
