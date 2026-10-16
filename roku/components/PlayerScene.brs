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
    m.grid = m.top.FindNode("grid")
    m.menu = m.top.FindNode("menu")
    m.searchPanel = m.top.FindNode("searchPanel")
    m.searchText = m.top.FindNode("searchText")
    m.keyboard = m.top.FindNode("keyboard")
    m.emptyText = m.top.FindNode("emptyText")
    m.previewLogo = m.top.FindNode("previewLogo")
    m.previewTitle = m.top.FindNode("previewTitle")
    m.previewChannel = m.top.FindNode("previewChannel")
    m.previewTime = m.top.FindNode("previewTime")
    m.previewDesc = m.top.FindNode("previewDesc")
    m.clock = m.top.FindNode("clock")
    m.clockTimer = m.top.FindNode("clockTimer")
    m.searchTimer = m.top.FindNode("searchTimer")
    m.idleArt = m.top.FindNode("idleArt")
    m.timecard = m.top.FindNode("timecard")
    m.programTimer = m.top.FindNode("programTimer")
    m.cardKey = ""
    m.pendingCard = false
    m.lastTitle = ""
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
    m.rowMap = []
    m.filter = { mode: "all" }
    m.menuFilters = []
    m.recents = ParseJson(m.registry.Read("recents"))
    if m.recents = invalid then m.recents = []

    m.video.ObserveField("state", "onVideoState")
    m.grid.ObserveField("programFocusedDetails", "onProgramFocused")
    m.grid.ObserveField("programSelected", "onProgramSelected")
    m.menu.ObserveField("itemSelected", "onMenuSelected")
    m.keyboard.ObserveField("text", "onSearchTyped")
    m.searchTimer.ObserveField("fire", "runSearch")
    m.clockTimer.ObserveField("fire", "updateClock")
    m.programTimer.ObserveField("fire", "checkProgramChange")
    m.programTimer.control = "start"
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
        showStatus("", "Loading channels…")
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
    if m.guide.visible then buildGuide(false)

    if m.channels.Count() = 0
        showStatus("", "Pick a channel on your phone and tap the cast icon.")
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
    addRecent(ch.url)
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
    m.lastTitle = title
    m.idleArt.visible = false
    m.pendingCard = true
    m.timecard.dismiss = true
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
        if m.pendingCard
            m.pendingCard = false
            showTimecard(true)
        end if
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
    m.banner.visible = false
    m.timecard.dismiss = true
    m.guide.visible = true
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
    m.searchPanel.visible = false
    m.menu.visible = true
    m.clockTimer.control = "stop"
    m.top.SetFocus(true)
end sub

' Left-hand menu: fixed entries, the biggest categories, then Search.
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
        if i >= 8 then exit for
        m.menuFilters.Push({ mode: "group", value: groups[i].name })
        labels.Push(groups[i].name)
    end for
    m.menuFilters.Push({ mode: "search", value: "" })
    labels.Push("Search")

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
        m.menu.visible = false
        m.searchPanel.visible = true
        m.keyboard.text = ""
        m.searchText.text = " "
        m.keyboard.SetFocus(true)
        m.filter = { mode: "search", value: "" }
        buildGuide(false)
        return
    end if
    m.filter = f
    buildGuide(true)
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
        if m.filter.mode = "fav" then m.emptyText.text = "No favorites yet. Star channels in the phone app."
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

sub onProgramFocused()
    d = m.grid.programFocusedDetails
    if d = invalid then return
    row = d.focusChannelIndex
    if row = invalid or row < 0 or row >= m.rowMap.Count() then return
    ch = m.channels[m.rowMap[row]]
    prog = d.content
    m.previewChannel.text = "Ch " + (m.rowMap[row] + 1).ToStr() + "   ·   " + valueOr(ch.name, "")
    if ch.logo <> invalid then m.previewLogo.uri = ch.logo else m.previewLogo.uri = ""
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
    if idx <> m.index or m.video.state = "error" then playIndex(idx) else showBanner()
end sub

sub updateClock()
    dt = CreateObject("roDateTime")
    m.clock.text = fmtTime(dt.AsSeconds())
end sub

sub addRecent(url as String)
    list = [url]
    for each u in m.recents
        if u <> url and list.Count() < 30 then list.Push(u)
    end for
    m.recents = list
    m.registry.Write("recents", FormatJson(list))
end sub

' ---- Remote ----

function onKeyEvent(key as String, press as Boolean) as Boolean
    if not press then return false

    if m.guide.visible
        inSearch = m.searchPanel.visible
        if key = "back"
            if m.keyboard.IsInFocusChain()
                ' Leave search, back to the menu.
                m.searchPanel.visible = false
                m.menu.visible = true
                m.menu.SetFocus(true)
            else if m.grid.IsInFocusChain() and inSearch
                m.keyboard.SetFocus(true)
            else
                closeGuide()
            end if
            return true
        else if key = "left" or key = "options"
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
