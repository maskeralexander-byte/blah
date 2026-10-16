sub init()
    m.video = m.top.FindNode("video")
    m.title = m.top.FindNode("title")
    m.status = m.top.FindNode("status")
    m.banner = m.top.FindNode("banner")
    m.bannerNumber = m.top.FindNode("bannerNumber")
    m.bannerName = m.top.FindNode("bannerName")
    m.bannerNow = m.top.FindNode("bannerNow")
    m.bannerNext = m.top.FindNode("bannerNext")
    m.guide = m.top.FindNode("guide")
    m.guideList = m.top.FindNode("guideList")
    m.bannerTimer = m.top.FindNode("bannerTimer")
    m.refreshTimer = m.top.FindNode("refreshTimer")

    m.registry = CreateObject("roRegistrySection", "freetv")
    m.channels = []
    m.index = 0
    m.pendingIndex = invalid
    m.currentUrl = ""
    m.lineupUri = ""
    m.lineupVersion = ""
    m.guideBuiltAt = 0

    m.video.ObserveField("state", "onVideoState")
    m.guideList.ObserveField("itemSelected", "onGuideSelected")
    m.bannerTimer.ObserveField("fire", "hideBanner")
    m.refreshTimer.ObserveField("fire", "refreshLineup")
    m.refreshTimer.control = "start"
    m.top.SetFocus(true)
end sub

' ---- Requests from the phone (or a launch from the Roku home screen) ----

sub onRequest()
    req = m.top.request
    if req = invalid then return

    if req.resume <> invalid
        uri = m.registry.Read("lineup")
        saved = m.registry.Read("index")
        if saved <> "" then m.pendingIndex = saved.ToInt()
        m.lineupUri = uri
        showStatus("Free Live TV", "Loading channels…")
        loadLineup(uri)
        return
    end if

    if req.index <> invalid then m.pendingIndex = req.index.ToInt()

    reloading = false
    if req.lineup <> invalid and req.lineup <> ""
        version = valueOr(req.v, "")
        if req.lineup <> m.lineupUri or version <> m.lineupVersion or m.channels.Count() = 0
            reloading = true
            m.lineupUri = req.lineup
            m.lineupVersion = version
            m.registry.Write("lineup", req.lineup)
            m.registry.Flush()
            loadLineup(req.lineup)
        end if
    end if

    if req.contentId <> invalid and req.contentId <> ""
        ' With a new lineup on its way, the index refers to that list, not the one loaded now.
        if not reloading and m.pendingIndex <> invalid and m.pendingIndex >= 0 and m.pendingIndex < m.channels.Count()
            ' Lineup already loaded: play by index so the banner and guide line up.
            idx = m.pendingIndex
            m.pendingIndex = invalid
            playIndex(idx)
        else
            playStream(req.contentId, valueOr(req.title, ""), valueOr(req.format, "hls"), valueOr(req.ua, ""), valueOr(req.ref, ""))
            showBannerText("", valueOr(req.title, ""), "", "")
        end if
    end if
end sub

sub loadLineup(uri as String)
    task = CreateObject("roSGNode", "LineupTask")
    task.uri = uri
    task.ObserveField("lineup", "onLineup")
    task.control = "RUN"
    m.task = task
end sub

sub refreshLineup()
    if m.lineupUri <> "" then loadLineup(m.lineupUri)
end sub

sub onLineup()
    lineup = m.task.lineup
    if lineup = invalid or lineup.channels = invalid then return
    m.channels = lineup.channels
    m.guideBuiltAt = 0
    if m.guide.visible then buildGuide()

    if m.channels.Count() = 0
        showStatus("Free Live TV", "Open Free Live TV on your phone, pick a channel and tap the cast icon.")
        return
    end if

    if m.pendingIndex <> invalid
        idx = m.pendingIndex
        m.pendingIndex = invalid
        if idx < 0 or idx >= m.channels.Count() then idx = 0
        if m.channels[idx].url <> m.currentUrl
            playIndex(idx)
        else
            m.index = idx
            showBanner()
        end if
    end if
end sub

' ---- Playback ----

sub playIndex(i as Integer)
    n = m.channels.Count()
    if n = 0 then return
    i = ((i MOD n) + n) MOD n
    ch = m.channels[i]
    m.index = i
    m.registry.Write("index", i.ToStr())
    m.registry.Flush()
    playStream(ch.url, valueOr(ch.name, ""), valueOr(ch.format, "hls"), valueOr(ch.ua, ""), valueOr(ch.ref, ""))
    showBanner()
end sub

sub playStream(url as String, title as String, format as String, ua as String, ref as String)
    content = CreateObject("roSGNode", "ContentNode")
    content.url = url
    content.title = title
    content.streamFormat = format
    content.live = true

    ' Some streams only play with the same headers the phone app would send.
    headers = []
    if ua <> "" then headers.Push("User-Agent:" + ua)
    if ref <> "" then headers.Push("Referer:" + ref)
    if headers.Count() > 0 then content.HttpHeaders = headers

    m.currentUrl = url
    showStatus(title, "Loading…")
    m.video.control = "stop"
    m.video.content = content
    m.video.control = "play"
end sub

sub onVideoState()
    state = m.video.state
    if state = "playing"
        m.title.visible = false
        m.status.visible = false
    else if state = "buffering"
        m.status.text = "Loading…"
        m.status.visible = true
    else if state = "error"
        hint = "Pick another one on your phone."
        if m.channels.Count() > 0 then hint = "Press Up or Down for another channel."
        showStatus(m.title.text, "This channel isn't working right now. " + hint)
    end if
end sub

sub showStatus(title as String, text as String)
    m.title.text = title
    m.status.text = text
    m.title.visible = true
    m.status.visible = true
end sub

' ---- Banner ----

sub showBanner()
    if m.channels.Count() = 0 then return
    ch = m.channels[m.index]
    nn = nowNext(ch)
    nowText = ""
    nextText = ""
    if nn.now <> invalid then nowText = "Now: " + nn.now.t + "  ·  until " + fmtTime(nn.now.e)
    if nn.next <> invalid then nextText = "Next: " + nn.next.t + " at " + fmtTime(nn.next.s)
    showBannerText((m.index + 1).ToStr(), valueOr(ch.name, ""), nowText, nextText)
end sub

sub showBannerText(number as String, name as String, nowText as String, nextText as String)
    if m.guide.visible then return
    m.bannerNumber.text = number
    m.bannerName.text = name
    m.bannerNow.text = nowText
    m.bannerNext.text = nextText
    m.banner.visible = true
    m.bannerTimer.control = "stop"
    m.bannerTimer.control = "start"
end sub

sub hideBanner()
    m.banner.visible = false
end sub

' ---- Guide ----

sub openGuide()
    if m.channels.Count() = 0 then return
    now = CreateObject("roDateTime").AsSeconds()
    if now - m.guideBuiltAt > 60 then buildGuide()
    m.banner.visible = false
    m.guide.visible = true
    m.guideList.jumpToItem = m.index
    m.guideList.SetFocus(true)
end sub

sub closeGuide()
    m.guide.visible = false
    m.top.SetFocus(true)
end sub

sub buildGuide()
    now = CreateObject("roDateTime").AsSeconds()
    root = CreateObject("roSGNode", "ContentNode")
    for i = 0 to m.channels.Count() - 1
        ch = m.channels[i]
        item = root.CreateChild("ContentNode")
        item.title = (i + 1).ToStr() + "    " + valueOr(ch.name, "")
        nn = nowNext(ch)
        progress = 0.0
        if nn.now <> invalid
            item.description = "Now: " + nn.now.t + "  ·  until " + fmtTime(nn.now.e)
            span = nn.now.e - nn.now.s
            if span > 0 then progress = (now - nn.now.s) / span
        else
            item.description = ""
        end if
        if nn.next <> invalid
            item.shortDescriptionLine2 = "Next: " + nn.next.t + " at " + fmtTime(nn.next.s)
        else
            item.shortDescriptionLine2 = ""
        end if
        item.AddFields({ progress: progress })
    end for
    m.guideList.content = root
    m.guideBuiltAt = now
end sub

sub onGuideSelected()
    idx = m.guideList.itemSelected
    closeGuide()
    if idx <> m.index or m.video.state = "error" then playIndex(idx) else showBanner()
end sub

' ---- Remote ----

function onKeyEvent(key as String, press as Boolean) as Boolean
    if not press then return false

    if m.guide.visible
        if key = "back" or key = "left"
            closeGuide()
            return true
        end if
        return false
    end if

    if key = "up"
        playIndex(m.index + 1)
        return true
    else if key = "down"
        playIndex(m.index - 1)
        return true
    else if key = "OK"
        openGuide()
        return true
    else if key = "left" or key = "right" or key = "options"
        showBanner()
        return true
    end if
    return false
end function

' ---- Helpers ----

function nowNext(ch as Object) as Object
    result = { now: invalid, next: invalid }
    if ch.p = invalid then return result
    now = CreateObject("roDateTime").AsSeconds()
    for each p in ch.p
        if p.e > now
            if p.s <= now and result.now = invalid
                result.now = p
            else if p.s > now and result.next = invalid
                result.next = p
            end if
        end if
    end for
    return result
end function

function fmtTime(secs as Integer) as String
    dt = CreateObject("roDateTime")
    dt.FromSeconds(secs)
    dt.ToLocalTime()
    h = dt.GetHours()
    mins = dt.GetMinutes()
    suffix = "am"
    if h >= 12 then suffix = "pm"
    h12 = h MOD 12
    if h12 = 0 then h12 = 12
    mm = mins.ToStr()
    if mins < 10 then mm = "0" + mm
    return h12.ToStr() + ":" + mm + suffix
end function

function valueOr(v as Dynamic, fallback as String) as String
    if v = invalid then return fallback
    if Type(v) <> "String" and Type(v) <> "roString" then return fallback
    if v = "" then return fallback
    return v
end function
