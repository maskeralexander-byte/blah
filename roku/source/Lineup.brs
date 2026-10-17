' Channel lineup and guide, built on the TV itself.

function playlistUrl() as String
    return "https://iptv-org.github.io/iptv/languages/eng.m3u"
end function

' Guide feeds: Pluto TV matches by channel id, Samsung TV Plus by channel name.
function guideFeeds() as Object
    base = "https://raw.githubusercontent.com/matthuisman/i.mjh.nz/master/"
    return [
        { url: base + "PlutoTV/us.xml", byId: true, addChannels: true },
        { url: base + "SamsungTVPlus/us.xml", byId: false },
    ]
end function

' Bump when the cached lineup's shape changes, so copies written by older versions are ignored
' (version 1.3 and earlier cached the phone's list, without fields this version relies on).
function lineupFormat() as Integer
    return 4
end function

function readLineupCache() as Dynamic
    text = ReadAsciiFile(cachePath())
    if text = invalid or text = "" then return invalid
    lineup = ParseJson(text)
    if lineup = invalid or lineup.channels = invalid then return invalid
    if lineup.format = invalid then return invalid
    if lineup.format <> lineupFormat() then return invalid
    return lineup
end function

function cachePath() as String
    return "cachefs:/lineup.json"
end function

' Builds the lineup in two steps and publishes each one through out.lineup, so channels can play
' while the guide is still loading. out.progress says what it's doing, for the loading screen.
sub buildLineup(force as Boolean, out as Object)
    now = CreateObject("roDateTime").AsSeconds()
    cached = readLineupCache()
    playlistAt = 0
    guideAt = 0
    if cached <> invalid
        if cached.playlistAt <> invalid then playlistAt = cached.playlistAt
        if cached.guideAt <> invalid then guideAt = cached.guideAt
    end if

    ' 1. Channel list (daily).
    channels = invalid
    playlistFresh = false
    useCached = false
    if not force and cached <> invalid
        if cached.channels.Count() > 0 and now - playlistAt < 24 * 3600 then useCached = true
    end if
    if useCached
        channels = cached.channels
    else
        out.progress = "Downloading channel list…"
        text = download(playlistUrl())
        if text <> invalid
            out.progress = "Reading channel list…"
            channels = parseM3u(text)
            text = invalid
            addXumoChannels(channels, out)
            channels.SortBy("name", "i")
        end if
        if channels <> invalid and channels.Count() > 0
            playlistFresh = true
            playlistAt = now
        else if cached <> invalid
            channels = cached.channels
        else
            out.progress = "Couldn't download the channel list. Check the TV's internet connection."
            out.lineup = { format: lineupFormat(), channels: [], playlistAt: 0, guideAt: 0, builtAt: now, failed: true }
            return
        end if
    end if

    ' Publish the channels right away; the guide follows.
    lineup = { format: lineupFormat(), channels: channels, playlistAt: playlistAt, guideAt: guideAt, builtAt: now }
    out.lineup = lineup

    ' 2. Guide (every 3 hours).
    if force or playlistFresh or now - guideAt > 3 * 3600
        attachGuide(channels, now, out)
        lineup = { format: lineupFormat(), channels: channels, playlistAt: playlistAt, guideAt: now, builtAt: now }
        out.lineup = lineup
    end if
    out.progress = ""
    WriteAsciiFile(cachePath(), FormatJson(lineup))
end sub

' GET with a time limit; returns invalid on failure.
function download(url as String) as Dynamic
    port = CreateObject("roMessagePort")
    xfer = CreateObject("roUrlTransfer")
    xfer.SetMessagePort(port)
    xfer.SetUrl(url)
    xfer.SetCertificatesFile("common:/certs/ca-bundle.crt")
    xfer.InitClientCertificates()
    xfer.EnableEncodings(true)
    xfer.AddHeader("User-Agent", "LiveTV-Roku/1.4")
    if not xfer.AsyncGetToString() then return invalid
    msg = Wait(60000, port)
    if Type(msg) <> "roUrlEvent"
        xfer.AsyncCancel()
        return invalid
    end if
    if msg.GetResponseCode() <> 200 then return invalid
    text = msg.GetString()
    if text = invalid or text = "" then return invalid
    return text
end function

' ---- Playlist ----

function parseM3u(text as String) as Object
    attrRe = CreateObject("roRegex", "([\w-]+)=" + Chr(34) + "([^" + Chr(34) + "]*)" + Chr(34), "")
    nameRe = CreateObject("roRegex", "^.*" + Chr(34) + "\s*,(.*)$", "")
    plainNameRe = CreateObject("roRegex", "^#EXTINF:-?\d+[^,]*,(.*)$", "")
    out = []
    seen = {}
    info = invalid
    ua = ""
    ref = ""
    for each raw in text.Split(Chr(10))
        line = raw.Trim()
        if line = ""
            ' skip
        else if Left(line, 7) = "#EXTINF"
            info = line
            ua = ""
            ref = ""
        else if Left(line, 10) = "#EXTVLCOPT"
            opt = Mid(line, 12)
            if LCase(Left(opt, 16)) = "http-user-agent=" then ua = Mid(opt, 17)
            if LCase(Left(opt, 14)) = "http-referrer=" then ref = Mid(opt, 15)
        else if Left(line, 1) = "#"
            ' other directive
        else if info <> invalid
            if not seen.DoesExist(line)
                seen[line] = true
                out.Push(makeChannel(info, line, ua, ref, attrRe, nameRe, plainNameRe))
            end if
            info = invalid
        end if
    end for
    return out
end function

function makeChannel(info as String, url as String, ua as String, ref as String, attrRe as Object, nameRe as Object, plainNameRe as Object) as Object
    attrs = {}
    for each mt in attrRe.MatchAll(info)
        attrs[LCase(mt[1])] = mt[2]
    end for
    name = ""
    nameMatch = nameRe.Match(info)
    if nameMatch.Count() > 1
        name = nameMatch[1]
    else
        nameMatch = plainNameRe.Match(info)
        if nameMatch.Count() > 1 then name = nameMatch[1]
    end if
    name = cleanName(name)
    if name = "" and attrs["tvg-name"] <> invalid then name = attrs["tvg-name"]

    group = "Other"
    if attrs["group-title"] <> invalid and attrs["group-title"] <> ""
        group = attrs["group-title"].Split(";")[0]
        if LCase(group) = "undefined" then group = "Other"
    end if
    if ua = "" and attrs["http-user-agent"] <> invalid then ua = attrs["http-user-agent"]
    if ref = "" and attrs["http-referrer"] <> invalid then ref = attrs["http-referrer"]

    ch = { name: name, url: url, g: group, format: formatOf(url), official: isOfficial(url) }
    if attrs["tvg-logo"] <> invalid and attrs["tvg-logo"] <> "" then ch.logo = attrs["tvg-logo"]
    if ua <> "" then ch.ua = ua
    if ref <> "" then ch.ref = ref
    return ch
end function

function cleanName(raw as String) as String
    re = regexes()
    s = re.resolution.ReplaceAll(raw, "")
    s = re.brackets.ReplaceAll(s, "")
    s = re.spaces.ReplaceAll(s, " ")
    return s.Trim()
end function

' Compiled once per thread and reused.
function regexes() as Object
    if m.lineupRegexes = invalid
        m.lineupRegexes = {
            resolution: CreateObject("roRegex", "\s*\(\d{3,4}[pi]\)", ""),
            brackets: CreateObject("roRegex", "\s*\[[^\]]*\]", ""),
            spaces: CreateObject("roRegex", "\s{2,}", ""),
            host: CreateObject("roRegex", "^https?://([^/:?#]+)(:\d+)?", "i"),
            parens: CreateObject("roRegex", "\(.*?\)|\[.*?\]", ""),
            prefix: CreateObject("roRegex", "^(pluto tv|samsung|plex)\s+", ""),
            nonAlnum: CreateObject("roRegex", "[^a-z0-9]", ""),
        }
    end if
    return m.lineupRegexes
end function

function formatOf(url as String) as String
    path = LCase(url.Split("?")[0])
    if Right(path, 4) = ".mp4" or Right(path, 4) = ".m4v" then return "mp4"
    if Right(path, 4) = ".mkv" then return "mkv"
    if Right(path, 4) = ".mpd" then return "dash"
    return "hls"
end function

' Same list as the phone app's Official.kt: free streaming services, broadcasters and major CDNs.
function isOfficial(url as String) as Boolean
    mt = regexes().host.Match(url)
    if mt.Count() < 2 then return false
    if mt.Count() > 2 and mt[2] <> "" then return false
    host = LCase(mt[1])
    domains = ["jmp2.uk", "pluto.tv", "samsung.com", "plex.tv", "roku.com", "xumo.com", "tubi.video",
        "amagi.tv", "wurl.tv", "wurl.com", "frequency.stream", "stingray.com", "getpublica.com",
        "klowdtv.com", "rakuten.tv", "redbox.com", "vizio.com", "trace.plus",
        "pbs.org", "cbsnews.com", "cbs.com", "nbcuni.com", "foxnews.com", "bloomberg.com", "nasa.gov",
        "warnermediacdn.com", "cablecast.tv", "telvue.com", "swagit.com", "streamhoster.com",
        "sinclairstoryline.com",
        "akamaized.net", "akamaihd.net", "edgesuite.net", "cloudfront.net", "llnwd.net",
        "uplynk.com", "brightcove.com", "dai.google.com", "fastly.net"]
    for each d in domains
        if host = d then return true
        if Len(host) > Len(d) and Right(host, Len(d) + 1) = "." + d then return true
    end for
    return false
end function

' ---- Guide ----

sub attachGuide(channels as Object, now as Integer, out as Object)
    until = now + 8 * 3600
    byKey = {}
    plutoChannels = invalid
    feeds = guideFeeds()
    for i = 0 to feeds.Count() - 1
        feed = feeds[i]
        out.progress = "Downloading guide " + (i + 1).ToStr() + " of " + feeds.Count().ToStr() + "…"
        text = download(feed.url)
        if text <> invalid
            out.progress = "Reading guide " + (i + 1).ToStr() + " of " + feeds.Count().ToStr() + "…"
            parsed = parseXmltv(text, feed.byId, now, until)
            text = invalid
            for each k in parsed.programmes
                if not byKey.DoesExist(k) then byKey[k] = parsed.programmes[k]
            end for
            if feed.addChannels = true then plutoChannels = parsed.channels
        end if
    end for

    xumo = xumoSchedule(now, out)

    plutoRe = CreateObject("roRegex", "jmp2\.uk/plu-([0-9a-f]{24})", "")
    havePluto = {}
    for each ch in channels
        ch.Delete("p")
        list = invalid
        mt = plutoRe.Match(ch.url)
        if mt.Count() > 1
            havePluto[mt[1]] = true
            list = byKey["id:" + mt[1]]
        end if
        if list = invalid and ch.xumo <> invalid then list = xumo["xumo:" + ch.xumo]
        if list = invalid then list = byKey["name:" + normalizeName(ch.name)]
        ' Each channel gets its own copy: FormatJson refuses objects shared between channels.
        if list <> invalid then ch.p = copyProgrammes(list)
    end for

    ' Every channel in Pluto TV's US guide that the playlist doesn't have yet. They use the same
    ' jmp2.uk redirect as the playlist's own Pluto entries.
    if plutoChannels <> invalid
        for each id in plutoChannels
            if not havePluto.DoesExist(id)
                meta = plutoChannels[id]
                ch = {
                    name: meta.name,
                    url: "https://jmp2.uk/plu-" + id + ".m3u8",
                    g: categoryLabel(meta.cat),
                    format: "hls",
                    official: true,
                }
                if meta.logo <> invalid then ch.logo = meta.logo
                list = byKey["id:" + id]
                if list <> invalid then ch.p = copyProgrammes(list)
                channels.Push(ch)
            end if
        end for
        channels.SortBy("name", "i")
    end if
end sub

' Pluto's programme categories, shortened for the guide menu.
function categoryLabel(cat as Dynamic) as String
    if cat = invalid or cat = "" then return "Entertainment"
    map = { "News & Information": "News", "Children": "Kids", "Animated": "Animation", "Movie": "Movies",
        "Human Interest": "Lifestyle", "Sports event": "Sports", "Sports non-event": "Sports" }
    if map.DoesExist(cat) then return map[cat]
    return cat
end function

function copyProgrammes(list as Object) as Object
    out = []
    for each p in list
        item = { t: p.t, s: p.s, e: p.e }
        if p.d <> invalid then item.d = p.d
        out.Push(item)
    end for
    return out
end function

function normalizeName(name as String) as String
    re = regexes()
    s = LCase(re.parens.ReplaceAll(name, ""))
    s = re.prefix.ReplaceAll(s, "")
    return re.nonAlnum.ReplaceAll(s, "")
end function

' Parses XMLTV by splitting on </programme>, so every search runs on a small string.
' Returns { programmes: { key: [ {t, s, e, d} ] }, channels: { id: {name, logo, cat} } }.
function parseXmltv(text as String, byId as Boolean, now as Integer, until as Integer) as Object
    out = {}
    info = {}
    result = { programmes: out, channels: info }
    chunks = text.Split("</programme>")
    if chunks.Count() = 0 then return result

    names = {}
    for each chunk in chunks
        ' Channel definitions can come all at the top (Pluto) or before each channel's shows (Samsung).
        if Instr(1, chunk, "<channel id=") > 0 then collectChannels(chunk, names, info)
        p = Instr(1, chunk, "<programme ")
        if p > 0
            head = Mid(chunk, p, Instr(p, chunk, ">") - p)
            ch = attrOf(head, "channel")
            s = xmltvTime(attrOf(head, "start"))
            e = xmltvTime(attrOf(head, "stop"))
            ' Remember a category per channel for the guide menu (skip the generic "Series").
            meta = info[ch]
            if meta <> invalid and meta.cat = invalid
                cat = unescapeXml(tagText(chunk, "category"))
                if cat <> "" and cat <> "Series" then meta.cat = cat
            end if
            if e > now and s < until and ch <> ""
                key = invalid
                if byId then key = "id:" + ch else key = names[ch]
                if key <> invalid
                    list = out[key]
                    if list = invalid
                        list = []
                        out[key] = list
                    end if
                    if list.Count() < 8
                        item = { t: unescapeXml(tagText(chunk, "title")), s: s, e: e }
                        if list.Count() < 3
                            d = tagText(chunk, "desc")
                            if d <> "" then item.d = Left(unescapeXml(d), 180)
                        end if
                        list.Push(item)
                    end if
                end if
            end if
        end if
    end for
    return result
end function

sub collectChannels(text as String, names as Object, info as Object)
    for each part in text.Split("</channel>")
        idPos = Instr(1, part, "<channel id=" + Chr(34))
        if idPos > 0
            idStart = idPos + 13
            idEnd = Instr(idStart, part, Chr(34))
            id = Mid(part, idStart, idEnd - idStart)
            dn = unescapeXml(tagText(part, "display-name"))
            if dn <> ""
                names[id] = "name:" + normalizeName(dn)
                meta = { name: dn }
                logo = attrOf(part, "src")
                if logo <> "" then meta.logo = logo
                info[id] = meta
            end if
        end if
    end for
end sub

function attrOf(head as String, name as String) as String
    key = name + "=" + Chr(34)
    a = Instr(1, head, key)
    if a = 0 then return ""
    a = a + Len(key)
    b = Instr(a, head, Chr(34))
    return Mid(head, a, b - a)
end function

function tagText(s as String, tag as String) as String
    a = Instr(1, s, "<" + tag)
    if a = 0 then return ""
    gt = Instr(a, s, ">")
    b = Instr(gt, s, "</" + tag + ">")
    if gt = 0 or b = 0 then return ""
    return Mid(s, gt + 1, b - gt - 1)
end function

' Decodes XML entities. Straight double quotes become typographic ones so the text can't upset
' the JSON cache, whatever the JSON writer does with quotes.
function unescapeXml(s as String) as String
    s = s.Replace("&lt;", "<").Replace("&gt;", ">").Replace("&quot;", Chr(8221)).Replace(Chr(34), Chr(8221))
    s = s.Replace("&apos;", "'").Replace("&#39;", "'")
    ' Line breaks and tabs in descriptions become spaces, so the text stays on one JSON line.
    s = s.Replace(Chr(13), " ").Replace(Chr(10), " ").Replace(Chr(9), " ")
    return s.Replace("&amp;", "&")
end function

' "20261016044300 +0000" -> seconds since 1970 (UTC).
function xmltvTime(s as String) as Integer
    if Len(s) < 14 then return 0
    y = Val(Mid(s, 1, 4))
    mo = Val(Mid(s, 5, 2))
    d = Val(Mid(s, 7, 2))
    h = Val(Mid(s, 9, 2))
    mi = Val(Mid(s, 11, 2))
    se = Val(Mid(s, 13, 2))
    offset = 0
    if Len(s) >= 20
        sign = Mid(s, 16, 1)
        offset = Val(Mid(s, 17, 2)) * 3600 + Val(Mid(s, 19, 2)) * 60
        if sign = "-" then offset = -offset
    end if
    return daysFromCivil(y, mo, d) * 86400 + h * 3600 + mi * 60 + se - offset
end function

' Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm).
function daysFromCivil(y as Integer, mon as Integer, d as Integer) as Integer
    if mon <= 2 then y = y - 1
    if y >= 0 then era = y \ 400 else era = (y - 399) \ 400
    yoe = y - era * 400
    if mon > 2 then mp = mon - 3 else mp = mon + 9
    doy = (153 * mp + 2) \ 5 + d - 1
    doe = yoe * 365 + yoe \ 4 - yoe \ 100 + doy
    return era * 146097 + doe - 719468
end function
