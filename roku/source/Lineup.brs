' Channel lineup and guide, built on the TV itself.

function playlistUrl() as String
    return "https://iptv-org.github.io/iptv/languages/eng.m3u"
end function

' Guide feeds: Pluto TV matches by channel id, Samsung TV Plus by channel name.
function guideFeeds() as Object
    base = "https://raw.githubusercontent.com/matthuisman/i.mjh.nz/master/"
    return [
        { url: base + "PlutoTV/us.xml", byId: true },
        { url: base + "SamsungTVPlus/us.xml", byId: false },
    ]
end function

function readLineupCache() as Dynamic
    text = ReadAsciiFile("cachefs:/lineup.json")
    if text = invalid or text = "" then return invalid
    return ParseJson(text)
end function

function buildLineup(force as Boolean) as Object
    now = CreateObject("roDateTime").AsSeconds()
    cached = readLineupCache()

    channels = invalid
    playlistFresh = false
    if not force and cached <> invalid and cached.channels <> invalid and cached.channels.Count() > 0
        if now - cached.playlistAt < 24 * 3600 then channels = cached.channels
    end if
    playlistAt = now
    if channels = invalid
        text = download(playlistUrl())
        if text <> invalid then channels = parseM3u(text)
        playlistFresh = true
        if (channels = invalid or channels.Count() = 0) and cached <> invalid and cached.channels <> invalid
            channels = cached.channels
            playlistAt = cached.playlistAt
        end if
    else
        playlistAt = cached.playlistAt
    end if
    if channels = invalid then channels = []

    guideAt = 0
    if cached <> invalid and cached.guideAt <> invalid then guideAt = cached.guideAt
    if force or playlistFresh or now - guideAt > 3 * 3600
        attachGuide(channels, now)
        guideAt = now
    end if

    lineup = { channels: channels, playlistAt: playlistAt, guideAt: guideAt, builtAt: now }
    WriteAsciiFile("cachefs:/lineup.json", FormatJson(lineup))
    return lineup
end function

function download(url as String) as Dynamic
    xfer = CreateObject("roUrlTransfer")
    xfer.SetUrl(url)
    xfer.SetCertificatesFile("common:/certs/ca-bundle.crt")
    xfer.InitClientCertificates()
    xfer.EnableEncodings(true)
    xfer.AddHeader("User-Agent", "LiveTV-Roku/1.4")
    text = xfer.GetToString()
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
    s = CreateObject("roRegex", "\s*\(\d{3,4}[pi]\)", "").ReplaceAll(raw, "")
    s = CreateObject("roRegex", "\s*\[[^\]]*\]", "").ReplaceAll(s, "")
    s = CreateObject("roRegex", "\s{2,}", "").ReplaceAll(s, " ")
    return s.Trim()
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
    mt = CreateObject("roRegex", "^https?://([^/:?#]+)(:\d+)?", "i").Match(url)
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

sub attachGuide(channels as Object, now as Integer)
    until = now + 8 * 3600
    byKey = {}
    for each feed in guideFeeds()
        text = download(feed.url)
        if text <> invalid
            parsed = parseXmltv(text, feed.byId, now, until)
            text = invalid
            for each k in parsed
                if not byKey.DoesExist(k) then byKey[k] = parsed[k]
            end for
        end if
    end for

    plutoRe = CreateObject("roRegex", "jmp2\.uk/plu-([0-9a-f]{24})", "")
    for each ch in channels
        ch.Delete("p")
        list = invalid
        mt = plutoRe.Match(ch.url)
        if mt.Count() > 1 then list = byKey["id:" + mt[1]]
        if list = invalid then list = byKey["name:" + normalizeName(ch.name)]
        if list <> invalid then ch.p = list
    end for
end sub

function normalizeName(name as String) as String
    s = LCase(CreateObject("roRegex", "\(.*?\)|\[.*?\]", "").ReplaceAll(name, ""))
    s = CreateObject("roRegex", "^(pluto tv|samsung|plex)\s+", "").ReplaceAll(s, "")
    return CreateObject("roRegex", "[^a-z0-9]", "").ReplaceAll(s, "")
end function

' Parses XMLTV by splitting on </programme>, so every search runs on a small string.
function parseXmltv(text as String, byId as Boolean, now as Integer, until as Integer) as Object
    out = {}
    chunks = text.Split("</programme>")
    if chunks.Count() = 0 then return out

    names = {}
    for each chunk in chunks
        ' Channel definitions can come all at the top (Pluto) or before each channel's shows (Samsung).
        if not byId and Instr(1, chunk, "<channel id=") > 0 then collectChannelNames(chunk, names)
        p = Instr(1, chunk, "<programme ")
        if p > 0
            head = Mid(chunk, p, Instr(p, chunk, ">") - p)
            ch = attrOf(head, "channel")
            s = xmltvTime(attrOf(head, "start"))
            e = xmltvTime(attrOf(head, "stop"))
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
    return out
end function

sub collectChannelNames(text as String, names as Object)
    for each part in text.Split("</channel>")
        idPos = Instr(1, part, "<channel id=" + Chr(34))
        if idPos > 0
            idStart = idPos + 13
            idEnd = Instr(idStart, part, Chr(34))
            dn = tagText(part, "display-name")
            if dn <> "" then names[Mid(part, idStart, idEnd - idStart)] = "name:" + normalizeName(unescapeXml(dn))
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

function unescapeXml(s as String) as String
    s = s.Replace("&lt;", "<").Replace("&gt;", ">").Replace("&quot;", Chr(34))
    s = s.Replace("&apos;", "'").Replace("&#39;", "'")
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
