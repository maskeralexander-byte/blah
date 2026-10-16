' Live TV on Roku: plays channels, shows the guide, and takes commands from the phone app.
' The channel lineup and guide are built on the TV itself (see source/Lineup.brs).

sub init()
    m.video = m.top.FindNode("video")
    m.idleArt = m.top.FindNode("idleArt")
    m.title = m.top.FindNode("title")
    m.status = m.top.FindNode("status")
    m.banner = m.top.FindNode("banner")
    m.bannerNumber = m.top.FindNode("bannerNumber")
    m.bannerName = m.top.FindNode("bannerName")
    m.bannerNow = m.top.FindNode("bannerNow")
    m.bannerNext = m.top.FindNode("bannerNext")
    m.timecard = m.top.FindNode("timecard")
    m.toast = m.top.FindNode("toast")
    m.toastText = m.top.FindNode("toastText")
    m.digitsLabel = m.top.FindNode("digits")

    m.guideBg = m.top.FindNode("guideBg")
    m.guide = m.top.FindNode("guide")
    m.grid = m.top.FindNode("grid")
    m.menu = m.top.FindNode("menu")
    m.searchPanel = m.top.FindNode("searchPanel")
    m.searchText = m.top.FindNode("searchText")
    m.keyboard = m.top.FindNode("keyboard")
    m.emptyText = m.top.FindNode("emptyText")
    m.previewLogo = m.top.FindNode("previewLogo")
    m.previewLogoBig = m.top.FindNode("previewLogoBig")
    m.previewTitle = m.top.FindNode("previewTitle")
    m.previewChannel = m.top.FindNode("previewChannel")
    m.previewTime = m.top.FindNode("previewTime")
    m.previewDesc = m.top.FindNode("previewDesc")
    m.clock = m.top.FindNode("clock")

    m.bannerTimer = m.top.FindNode("bannerTimer")
    m.refreshTimer = m.top.FindNode("refreshTimer")
    m.clockTimer = m.top.FindNode("clockTimer")
    m.searchTimer = m.top.FindNode("searchTimer")
    m.programTimer = m.top.FindNode("programTimer")
    m.toastTimer = m.top.FindNode("toastTimer")
    m.digitsTimer = m.top.FindNode("digitsTimer")

    m.registry = CreateObject("roRegistrySection", "livetv")
    m.allChannels = []
    m.channels = []
    m.index = 0
    saved = m.registry.Read("index")
    if saved <> "" then m.index = saved.ToInt()
    m.officialOnly = m.registry.Read("official") <> "0"
    m.favs = readList("favs")
    m.recents = readList("recents")

    m.currentUrl = ""
    m.lastTitle = ""
    m.pendingUrl = invalid
    m.pendingTitle = ""
    m.resume = false
    m.cardKey = ""
    m.pendingCard = false
    m.digits = ""
    m.rowMap = []
    m.filter = { mode: "all" }
    m.menuFilters = []
    m.guideBuiltAt = 0

    m.video.ObserveField("state", "onVideoState")
    m.grid.ObserveField("programFocusedDetails", "onProgramFocused")
    m.grid.ObserveField("programSelected", "onProgramSelected")
    m.menu.ObserveField("itemSelected", "onMenuSelected")
    m.keyboard.ObserveField("text", "onSearchTyped")
    m.searchTimer.ObserveField("fire", "runSearch")
    m.clockTimer.ObserveField("fire", "updateClock")
    m.bannerTimer.ObserveField("fire", "hideBanner")
    m.refreshTimer.ObserveField("fire", "refreshLineup")
    m.programTimer.ObserveField("fire", "checkProgramChange")
    m.toastTimer.ObserveField("fire", "hideToast")
    m.digitsTimer.ObserveField("fire", "tuneDigits")
    m.refreshTimer.control = "start"
    m.programTimer.control = "start"
    m.top.SetFocus(true)

    print "[livetv] scene ready"
    ' Show the saved lineup right away, then refresh it in the background.
    showStatus("", "Loading channels…")
    m.cacheTask = startLineupTask("cache", false)
    m.refreshTask = startLineupTask("refresh", false)
end sub

' ---- Lineup ----

function startLineupTask(mode as String, force as Boolean) as Object
    task = CreateObject("roSGNode", "LineupTask")
    task.id = mode
    task.mode = mode
    task.force = force
    task.ObserveField("lineup", "onLineup")
    if mode = "refresh" then task.ObserveField("progress", "onProgress")
    task.control = "RUN"
    return task
end function

sub refreshLineup()
    m.refreshTask = startLineupTask("refresh", false)
end sub

' Loading progress and errors, shown until something is playing.
sub onProgress(event as Object)
    text = event.GetData()
    print "[livetv] progress: "; text
    if text = "" or m.currentUrl <> "" or m.guide.visible then return
    showStatus("", text)
end sub

sub onLineup(event as Object)
    lineup = event.GetData()
    if lineup = invalid or lineup.channels = invalid then return
    fromCache = event.GetNode() = "cache"
    print "[livetv] lineup from "; event.GetNode(); ": "; lineup.channels.Count(); " channels"
    ' A late cache result must not replace a fresher download.
    if fromCache and m.allChannels.Count() > 0 then return
    m.allChannels = lineup.channels
    applyFilter()

    if m.channels.Count() = 0
        ' A failed load leaves its own message on screen (see onProgress).
        if not fromCache and m.currentUrl = "" and lineup.failed = invalid and m.allChannels.Count() > 0
            showStatus("", "No official channels found. Press OK and switch the guide to All streams.")
        end if
        return
    end if

    if m.pendingUrl <> invalid
        url = m.pendingUrl
        m.pendingUrl = invalid
        playUrl(url, m.pendingTitle, "", "", "")
    else if m.currentUrl = ""
        if m.resume and m.registry.Read("index") <> ""
            playIndex(m.index)
        else
            ' First launch: open the guide with channel 1 playing in the preview box.
            openGuide()
            playIndex(m.index)
        end if
    end if
end sub

' Applies the Official / All streams choice and keeps the current channel's position.
sub applyFilter()
    list = []
    for each ch in m.allChannels
        if ch.official = true or not m.officialOnly then list.Push(ch)
    end for
    m.channels = list
    found = indexOfUrl(m.currentUrl)
    if found >= 0 then m.index = found
    if m.index >= m.channels.Count() then m.index = 0
    m.guideBuiltAt = 0
    if m.guide.visible then buildGuide(false)
end sub

function indexOfUrl(url as String) as Integer
    if url = "" then return -1
    for i = 0 to m.channels.Count() - 1
        if m.channels[i].url = url then return i
    end for
    return -1
end function

' ---- Requests from the phone app (and launches) ----

sub onRequest()
    req = m.top.request
    if req = invalid then return

    if req.resume <> invalid
        m.resume = true
        return
    end if

    command = valueOr(req.command, "")
    if command = "channel_up"
        playIndex(m.index + 1)
    else if command = "channel_down"
        playIndex(m.index - 1)
    else if command = "number" and req.n <> invalid
        n = req.n.ToInt()
        if n >= 1 and n <= m.channels.Count() then playIndex(n - 1)
    else if command = "guide"
        if m.guide.visible then closeGuide() else openGuide()
    else if command = "search"
        openSearch(valueOr(req.q, ""))
    else if command = "favorite"
        if m.channels.Count() > 0 then toggleFavorite(m.channels[m.index])
    else if req.contentId <> invalid and req.contentId <> ""
        if m.guide.visible then closeGuide()
        playUrl(req.contentId, valueOr(req.title, ""), valueOr(req.format, "hls"), valueOr(req.ua, ""), valueOr(req.ref, ""))
    end if
end sub

' Plays a stream the phone picked: by its place in the TV's lineup when it's there, so the
' banner, guide and channel up/down line up; otherwise straight from the URL.
sub playUrl(url as String, title as String, format as String, ua as String, ref as String)
    if m.allChannels.Count() = 0
        m.pendingUrl = url
        m.pendingTitle = title
        playStream(url, title, format, ua, ref)
        return
    end if
    idx = indexOfUrl(url)
    if idx >= 0
        playIndex(idx)
    else
        playStream(url, title, format, ua, ref)
        showBannerText("", title, "", "")
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
    addRecent(ch.url)
    m.registry.Flush()
    playStream(ch.url, valueOr(ch.name, ""), valueOr(ch.format, "hls"), valueOr(ch.ua, ""), valueOr(ch.ref, ""))
    showBanner()
end sub

sub playStream(url as String, title as String, format as String, ua as String, ref as String)
    if url = m.currentUrl and m.video.state = "playing" then return
    content = CreateObject("roSGNode", "ContentNode")
    content.url = url
    content.title = title
    content.streamFormat = format
    content.live = true

    ' Some streams only play with the same headers a browser would send.
    headers = []
    if ua <> "" then headers.Push("User-Agent:" + ua)
    if ref <> "" then headers.Push("Referer:" + ref)
    if headers.Count() > 0 then content.HttpHeaders = headers

    m.currentUrl = url
    m.lastTitle = title
    m.idleArt.visible = false
    m.pendingCard = true
    m.timecard.dismiss = true
    if not m.guide.visible then showStatus(title, "Loading…")
    m.video.control = "stop"
    m.video.content = content
    m.video.control = "play"
end sub

sub onVideoState()
    state = m.video.state
    if state = "playing"
        m.title.visible = false
        m.status.visible = false
        m.previewLogoBig.visible = false
        if m.pendingCard
            m.pendingCard = false
            showTimecard(true)
        end if
    else if state = "buffering"
        if not m.guide.visible
            m.status.text = "Loading…"
            m.status.visible = true
        end if
    else if state = "error"
        if not m.guide.visible
            showStatus(m.lastTitle, "This channel isn't working right now. Press Up or Down for another channel.")
        end if
    end if
end sub

sub showStatus(title as String, text as String)
    m.title.text = title
    m.status.text = text
    m.title.visible = title <> ""
    m.status.visible = true
end sub

' ---- Banner, toast, number entry ----

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

sub showToast(text as String)
    m.toastText.text = text
    m.toast.visible = true
    m.toastTimer.control = "stop"
    m.toastTimer.control = "start"
end sub

sub hideToast()
    m.toast.visible = false
end sub

sub typeDigit(d as String)
    m.digits = Right(m.digits + d, 4)
    m.digitsLabel.text = m.digits
    m.digitsLabel.visible = true
    m.digitsTimer.control = "stop"
    m.digitsTimer.control = "start"
end sub

sub tuneDigits()
    m.digitsLabel.visible = false
    n = m.digits.ToInt()
    m.digits = ""
    if n >= 1 and n <= m.channels.Count() then playIndex(n - 1)
end sub

' ---- Favorites and recents (kept on the TV) ----

function readList(key as String) as Object
    text = m.registry.Read(key)
    if text = "" then return []
    list = ParseJson(text)
    if list = invalid then list = []
    return list
end function

function isFavorite(url as String) as Boolean
    for each u in m.favs
        if u = url then return true
    end for
    return false
end function

sub toggleFavorite(ch as Object)
    if isFavorite(ch.url)
        list = []
        for each u in m.favs
            if u <> ch.url then list.Push(u)
        end for
        m.favs = list
        showToast("Removed from Favorites")
    else
        m.favs.Push(ch.url)
        showToast("★  Added to Favorites")
    end if
    m.registry.Write("favs", FormatJson(m.favs))
    m.registry.Flush()
    if m.guide.visible then buildGuide(false)
end sub

sub addRecent(url as String)
    list = [url]
    for each u in m.recents
        if u <> url and list.Count() < 30 then list.Push(u)
    end for
    m.recents = list
    m.registry.Write("recents", FormatJson(list))
end sub

' ---- Guide ----

sub openGuide()
    if m.channels.Count() = 0 then return
    m.banner.visible = false
    m.timecard.dismiss = true
    m.title.visible = false
    m.status.visible = false
    ' The live picture shrinks into the preview box, like a cable box guide.
    m.video.translation = [96, 60]
    m.video.width = 480
    m.video.height = 270
    m.previewLogoBig.visible = m.video.state <> "playing"
    m.idleArt.visible = false
    m.guideBg.visible = true
    m.guide.visible = true
    m.searchPanel.visible = false
    m.menu.visible = true
    updateClock()
    m.clockTimer.control = "start"
    buildMenu()
    now = CreateObject("roDateTime").AsSeconds()
    if m.guideBuiltAt = 0 or now - m.guideBuiltAt > 300
        buildGuide(true)
    else
        focusGrid(true)
    end if
end sub

sub closeGuide()
    m.guide.visible = false
    m.guideBg.visible = false
    m.video.translation = [0, 0]
    m.video.width = 1920
    m.video.height = 1080
    m.searchPanel.visible = false
    m.menu.visible = true
    m.clockTimer.control = "stop"
    m.top.SetFocus(true)
    if m.video.state = "error" then onVideoState()
end sub

' Left-hand menu: fixed entries, the biggest categories, Search, and the stream filter.
sub buildMenu()
    counts = {}
    for each ch in m.channels
        if ch.g <> invalid and ch.g <> "" and ch.g <> "Other"
            if counts.DoesExist(ch.g) then counts[ch.g] = counts[ch.g] + 1 else counts[ch.g] = 1
        end if
    end for
    groups = []
    for each g in counts
        groups.Push({ name: g, n: counts[g] })
    end for
    groups.SortBy("n", "r")

    m.menuFilters = [{ mode: "all" }, { mode: "fav" }, { mode: "recent" }]
    labels = ["All Channels", "Favorites", "Recents"]
    for i = 0 to groups.Count() - 1
        if i >= 7 then exit for
        m.menuFilters.Push({ mode: "group", value: groups[i].name })
        labels.Push(groups[i].name)
    end for
    m.menuFilters.Push({ mode: "search", value: "" })
    labels.Push("Search")
    m.menuFilters.Push({ mode: "toggleOfficial" })
    if m.officialOnly then labels.Push("Showing: Official") else labels.Push("Showing: All streams")

    root = CreateObject("roSGNode", "ContentNode")
    for each label in labels
        item = root.CreateChild("ContentNode")
        item.title = label
    end for
    m.menu.content = root
end sub

sub onMenuSelected()
    f = m.menuFilters[m.menu.itemSelected]
    if f = invalid then return
    if f.mode = "search"
        openSearch("")
        return
    end if
    if f.mode = "toggleOfficial"
        m.officialOnly = not m.officialOnly
        if m.officialOnly then m.registry.Write("official", "1") else m.registry.Write("official", "0")
        m.registry.Flush()
        applyFilter()
        sel = m.menu.itemSelected
        buildMenu()
        m.menu.jumpToItem = sel
        if m.officialOnly then showToast("Official channels only") else showToast("Showing all streams")
        return
    end if
    m.filter = f
    buildGuide(true)
end sub

sub openSearch(query as String)
    if not m.guide.visible then openGuide()
    m.menu.visible = false
    m.searchPanel.visible = true
    m.keyboard.text = query
    m.searchText.text = query + " "
    m.keyboard.SetFocus(true)
    m.filter = { mode: "search", value: query }
    buildGuide(false)
end sub

sub onSearchTyped()
    m.searchText.text = m.keyboard.text + " "
    m.searchTimer.control = "stop"
    m.searchTimer.control = "start"
end sub

sub runSearch()
    m.filter = { mode: "search", value: m.keyboard.text }
    buildGuide(false)
end sub

' Builds the grid for the current filter on a Task; focusAfter moves focus to the grid when ready.
sub buildGuide(focusAfter as Boolean)
    task = CreateObject("roSGNode", "GuideTask")
    task.channels = m.channels
    f = m.filter
    f.recents = m.recents
    f.favs = m.favs
    task.filter = f
    m.focusGridAfterBuild = focusAfter
    task.ObserveField("content", "onGuideBuilt")
    task.control = "RUN"
    m.guideTask = task
end sub

sub onGuideBuilt()
    task = m.guideTask
    m.rowMap = task.rowMap
    m.grid.contentStartTime = task.startTime
    m.grid.content = task.content
    m.guideBuiltAt = CreateObject("roDateTime").AsSeconds()
    empty = (m.rowMap.Count() = 0)
    m.emptyText.visible = empty
    m.grid.visible = not empty
    if empty
        if m.filter.mode = "fav" then m.emptyText.text = "No favorites yet. Press ✱ on a channel to add it."
        if m.filter.mode = "recent" then m.emptyText.text = "Nothing watched yet."
        if m.filter.mode = "search" then m.emptyText.text = "No channels or shows match."
        if m.filter.mode = "all" or m.filter.mode = "group" then m.emptyText.text = "No channels here yet."
    end if
    if m.focusGridAfterBuild then focusGrid(true)
end sub

' Focuses the grid, on the channel that's playing when it's in the list.
sub focusGrid(jumpToCurrent as Boolean)
    if m.rowMap.Count() = 0 then return
    if jumpToCurrent
        row = 0
        for r = 0 to m.rowMap.Count() - 1
            if m.rowMap[r] = m.index then row = r
        end for
        m.grid.jumpToChannel = row
    end if
    m.grid.SetFocus(true)
end sub

function focusedChannel() as Dynamic
    row = m.grid.channelFocused
    if row = invalid or row < 0 or row >= m.rowMap.Count() then return invalid
    return m.channels[m.rowMap[row]]
end function

sub onProgramFocused()
    d = m.grid.programFocusedDetails
    if d = invalid then return
    row = d.focusChannelIndex
    if row = invalid or row < 0 or row >= m.rowMap.Count() then return
    ch = m.channels[m.rowMap[row]]
    ' The focus details only carry indexes; look the programme up in the grid's content.
    prog = invalid
    rowNode = m.grid.content.GetChild(row)
    if rowNode <> invalid and d.focusIndex <> invalid and d.focusIndex >= 0
        prog = rowNode.GetChild(d.focusIndex)
    end if
    m.previewChannel.text = "Ch " + (m.rowMap[row] + 1).ToStr() + "   ·   " + valueOr(ch.name, "")
    logo = ""
    if ch.logo <> invalid then logo = ch.logo
    m.previewLogo.uri = logo
    m.previewLogoBig.uri = logo
    if prog <> invalid and prog.description = "Live programming"
        ' Channel without listings.
        m.previewTitle.text = valueOr(ch.name, "")
        m.previewChannel.text = "Ch " + (m.rowMap[row] + 1).ToStr()
        m.previewTime.text = "ON NOW"
        m.previewDesc.text = "Live programming"
    else if prog <> invalid
        m.previewTitle.text = prog.title
        m.previewTime.text = fmtTime(prog.playStart) + " – " + fmtTime(prog.playStart + prog.playDuration)
        m.previewDesc.text = prog.description
    else
        m.previewTitle.text = valueOr(ch.name, "")
        m.previewTime.text = ""
        m.previewDesc.text = ""
    end if
end sub

sub onProgramSelected()
    row = m.grid.channelFocused
    if row = invalid or row < 0 or row >= m.rowMap.Count() then return
    idx = m.rowMap[row]
    closeGuide()
    if idx <> m.index or m.video.state = "error" or m.currentUrl = "" then playIndex(idx) else showBanner()
end sub

sub updateClock()
    m.clock.text = fmtTime(CreateObject("roDateTime").AsSeconds())
end sub

' ---- Remote ----

function onKeyEvent(key as String, press as Boolean) as Boolean
    if not press then return false

    ' Number keys typed from the phone app's keypad.
    if Left(key, 4) = "Lit_" and Len(key) = 5 and Instr(1, "0123456789", Right(key, 1)) > 0
        if not m.keyboard.IsInFocusChain()
            typeDigit(Right(key, 1))
            return true
        end if
    end if

    if m.guide.visible
        inSearch = m.searchPanel.visible
        if key = "back"
            if m.keyboard.IsInFocusChain()
                m.searchPanel.visible = false
                m.menu.visible = true
                m.menu.SetFocus(true)
            else if m.grid.IsInFocusChain() and inSearch
                m.keyboard.SetFocus(true)
            else
                closeGuide()
            end if
            return true
        else if key = "options"
            if m.grid.IsInFocusChain()
                ch = focusedChannel()
                if ch <> invalid then toggleFavorite(ch)
                return true
            end if
        else if key = "left"
            if m.grid.IsInFocusChain()
                if inSearch then m.keyboard.SetFocus(true) else m.menu.SetFocus(true)
                return true
            end if
        else if key = "right"
            if (m.menu.HasFocus() or m.keyboard.IsInFocusChain()) and m.rowMap.Count() > 0
                focusGrid(false)
                return true
            end if
        end if
        return false
    end if

    if key = "up" or key = "channelup"
        playIndex(m.index + 1)
        return true
    else if key = "down" or key = "channeldown"
        playIndex(m.index - 1)
        return true
    else if key = "OK"
        openGuide()
        return true
    else if key = "options"
        if m.channels.Count() > 0 then toggleFavorite(m.channels[m.index])
        return true
    else if key = "left" or key = "right" or key = "info"
        showBanner()
        return true
    end if
    return false
end function

' ---- Now-playing card ----

' Shows the card for what's on the current channel. On a channel change it always shows;
' otherwise only when a new programme has started since the last card.
sub showTimecard(force as Boolean)
    if m.guide.visible then return
    now = CreateObject("roDateTime").AsSeconds()
    info = { kicker: "Now playing", title: m.lastTitle, meta: "Live", progress: -1 }
    key = m.currentUrl

    if m.channels.Count() > 0 and m.index < m.channels.Count() and m.channels[m.index].url = m.currentUrl
        ch = m.channels[m.index]
        nn = nowNext(ch)
        info.kicker = "Now playing  ·  Ch " + (m.index + 1).ToStr()
        if nn.now <> invalid
            span = nn.now.e - nn.now.s
            info.title = nn.now.t
            info.meta = fmtTime(nn.now.s) + " – " + fmtTime(nn.now.e) + "   ·   " + valueOr(ch.name, "")
            if span > 0 then info.progress = (now - nn.now.s) / span
            key = m.currentUrl + "|" + nn.now.s.ToStr()
        else
            info.title = valueOr(ch.name, "")
            info.meta = "Live programming"
        end if
    end if

    if not force and key = m.cardKey then return
    m.cardKey = key
    m.timecard.info = info
end sub

sub checkProgramChange()
    if m.video.state = "playing" then showTimecard(false)
end sub

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

' Takes any number type: guide times arrive as Integer, LongInteger or Double depending on the source.
function fmtTime(secs as Dynamic) as String
    if secs = invalid then return ""
    dt = CreateObject("roDateTime")
    dt.FromSeconds(Int(secs))
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
