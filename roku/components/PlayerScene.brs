sub init()
    m.video = m.top.FindNode("video")
    m.title = m.top.FindNode("title")
    m.status = m.top.FindNode("status")
    m.video.ObserveField("state", "onVideoState")
    m.top.SetFocus(true)
end sub

sub onRequest()
    req = m.top.request
    if req = invalid or req.contentId = invalid then return

    content = CreateObject("roSGNode", "ContentNode")
    content.url = req.contentId
    content.title = valueOr(req.title, "")
    content.streamFormat = valueOr(req.format, "hls")
    content.live = true

    ' Some streams only play with the same headers the phone app would send.
    headers = []
    if req.ua <> invalid and req.ua <> "" then headers.Push("User-Agent:" + req.ua)
    if req.ref <> invalid and req.ref <> "" then headers.Push("Referer:" + req.ref)
    if headers.Count() > 0 then content.HttpHeaders = headers

    m.title.text = content.title
    m.status.text = "Loading…"
    m.title.visible = true
    m.status.visible = true

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
        m.title.visible = true
        m.status.visible = true
        m.status.text = "This channel isn't working right now. Pick another one on your phone. (" + m.video.errorMsg + ")"
    end if
end sub

function valueOr(v as Dynamic, fallback as String) as String
    if v = invalid or v = "" then return fallback
    return v
end function
