' Xumo Play (Comcast's free streaming service): channel list, schedule, and stream lookup.
' Channels use "xumo://<channel id>" as their URL; the real stream is looked up when tuning in.

function xumoBase() as String
    return "https://valencia-app-mds.xumo.com/v2/"
end function

' Adds Xumo's live channels to the lineup.
sub addXumoChannels(channels as Object, out as Object)
    out.progress = "Downloading Xumo channel list…"
    text = download(xumoBase() + "channels/list/10006.json?sort=hybrid&geoId=unknown")
    if text = invalid then return
    data = ParseJson(text)
    if data = invalid or data.channel = invalid or data.channel.item = invalid then return

    have = {}
    for each ch in channels
        have[ch.url] = true
    end for
    for each item in data.channel.item
        live = false
        if item.properties <> invalid and item.properties.is_live = "true" then live = true
        if live and item.guid <> invalid and item.guid.value <> invalid and item.title <> invalid
            id = item.guid.value
            url = "xumo://" + id
            if not have.DoesExist(url)
                genre = ""
                if item.genre <> invalid and item.genre.Count() > 0 and item.genre[0].value <> invalid then genre = item.genre[0].value
                channels.Push({
                    name: cleanName(unescapeXml(item.title)),
                    url: url,
                    xumo: id,
                    g: xumoGenre(genre),
                    format: "hls",
                    official: true,
                    logo: "https://image.xumo.com/v1/channels/channel/" + id + "/168x168.png?type=color_onBlack",
                })
            end if
        end if
    end for
end sub

function xumoGenre(genre as String) as String
    if genre = "" then return "Entertainment"
    if Right(genre, 3) = " TV" then genre = Left(genre, Len(genre) - 3)
    return genre
end function

' Schedule for the next three hours, as { "xumo:<id>": [ {t, s, e, d} ] }.
function xumoSchedule(now as Integer, out as Object) as Object
    result = {}
    seen = {}
    for hour = 0 to 2
        dt = CreateObject("roDateTime")
        dt.FromSeconds(now + hour * 3600)
        day = dt.GetYear().ToStr() + pad2(dt.GetMonth()) + pad2(dt.GetDayOfMonth())
        offset = 0
        total = 1
        while offset < total
            out.progress = "Downloading Xumo guide (" + (hour + 1).ToStr() + " of 3)…"
            text = download(xumoBase() + "epg/10006/" + day + "/" + dt.GetHours().ToStr() + ".json?limit=50&offset=" + offset.ToStr() + "&f=asset.title&f=asset.descriptions")
            if text = invalid then exit while
            page = ParseJson(text)
            if page = invalid or page.channels = invalid then exit while
            if page.totalChannels <> invalid then total = page.totalChannels
            assets = page.assets
            if assets = invalid then assets = {}
            for each ch in page.channels
                key = "xumo:" + Str(ch.channelId).Trim()
                list = result[key]
                if list = invalid
                    list = []
                    result[key] = list
                end if
                if ch.schedule <> invalid
                    for each slot in ch.schedule
                        s = isoTime(slot.start)
                        e = isoTime(slot["end"])
                        dedupe = key + "|" + s.ToStr()
                        if not seen.DoesExist(dedupe)
                            seen[dedupe] = true
                            asset = assets[slot.assetId]
                            item = { t: "Live programming", s: s, e: e }
                            if asset <> invalid
                                if asset.title <> invalid then item.t = unescapeXml(asset.title)
                                if asset.descriptions <> invalid
                                    d = asset.descriptions.medium
                                    if d = invalid then d = asset.descriptions.small
                                    if d <> invalid then item.d = Left(unescapeXml(d), 180)
                                end if
                            end if
                            list.Push(item)
                        end if
                    end for
                end if
            end for
            offset = offset + 50
        end while
    end for

    ' Keep what's on now and the next few, in order.
    for each key in result
        list = result[key]
        list.SortBy("s")
        keep = []
        for each p in list
            if p.e > now and keep.Count() < 8 then keep.Push(p)
        end for
        result[key] = keep
    end for
    return result
end function

' "2026-10-17T06:00:00+0000" -> seconds since 1970 (UTC).
function isoTime(s as Dynamic) as Integer
    if s = invalid or Len(s) < 19 then return 0
    compact = Mid(s, 1, 4) + Mid(s, 6, 2) + Mid(s, 9, 2) + Mid(s, 12, 2) + Mid(s, 15, 2) + Mid(s, 18, 2)
    zone = "+0000"
    if Len(s) >= 24 then zone = Mid(s, 20, 5)
    return xmltvTime(compact + " " + zone)
end function

function pad2(n as Integer) as String
    if n < 10 then return "0" + n.ToStr()
    return n.ToStr()
end function

' Looks up the stream for a Xumo channel right now. Returns "" on failure.
function resolveXumo(id as String) as String
    now = CreateObject("roDateTime")
    text = download(xumoBase() + "channels/channel/" + id + "/broadcast.json?hour=" + now.GetHours().ToStr())
    if text = invalid then return ""
    b = ParseJson(text)
    if b = invalid or b.assets = invalid or b.assets.Count() = 0 then return ""
    assetId = b.assets[0].id
    secs = now.AsSeconds()
    for each a in b.assets
        if a.timestamps <> invalid and a.timestamps["start"] <= secs and a.timestamps["end"] > secs then assetId = a.id
    end for
    text = download(xumoBase() + "assets/asset/" + assetId + ".json?f=providers")
    if text = invalid then return ""
    asset = ParseJson(text)
    if asset = invalid or asset.providers = invalid then return ""
    for each p in asset.providers
        if p.sources <> invalid
            for each src in p.sources
                uri = src.uri
                if uri <> invalid
                    hls = Instr(1, uri, ".m3u8") > 0
                    if src.produces <> invalid and Instr(1, src.produces, "mpegURL") > 0 then hls = true
                    if hls then return uri.Replace("[PLATFORM]", "web")
                end if
            end for
        end if
    end for
    return ""
end function
