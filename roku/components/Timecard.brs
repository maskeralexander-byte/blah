sub init()
    m.card = m.top.FindNode("card")
    m.kicker = m.top.FindNode("kicker")
    m.title = m.top.FindNode("title")
    m.meta = m.top.FindNode("meta")
    m.track = m.top.FindNode("track")
    m.bar = m.top.FindNode("bar")
    m.slideIn = m.top.FindNode("slideIn")
    m.slideOut = m.top.FindNode("slideOut")
    m.hideTimer = m.top.FindNode("hideTimer")
    m.hideTimer.ObserveField("fire", "onDismiss")
    m.shown = false
end sub

sub onInfo()
    info = m.top.info
    if info = invalid then return
    m.kicker.text = UCase(textOf(info.kicker))
    m.title.text = textOf(info.title)
    m.meta.text = textOf(info.meta)
    progress = -1
    if info.progress <> invalid then progress = info.progress
    m.track.visible = progress >= 0
    m.bar.visible = progress >= 0
    if progress >= 0
        if progress > 1 then progress = 1
        m.bar.width = Int(576 * progress)
    end if

    m.slideOut.control = "stop"
    if not m.shown
        m.slideIn.control = "start"
        m.shown = true
    end if
    m.hideTimer.control = "stop"
    m.hideTimer.control = "start"
end sub

sub onDismiss()
    if not m.shown then return
    m.shown = false
    m.hideTimer.control = "stop"
    m.slideIn.control = "stop"
    m.slideOut.control = "start"
end sub

function textOf(v as Dynamic) as String
    if v = invalid then return ""
    return v
end function
