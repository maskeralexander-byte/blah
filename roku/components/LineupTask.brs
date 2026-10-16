sub init()
    m.top.functionName = "load"
end sub

sub load()
    cachePath = "cachefs:/lineup.json"
    text = invalid
    if m.top.uri <> invalid and m.top.uri <> ""
        xfer = CreateObject("roUrlTransfer")
        xfer.SetUrl(m.top.uri)
        xfer.SetMinimumTransferRate(1, 8)
        text = xfer.GetToString()
        if text = "" then text = invalid
    end if

    fromCache = false
    if text = invalid
        text = ReadAsciiFile(cachePath)
        fromCache = true
    else
        WriteAsciiFile(cachePath, text)
    end if

    lineup = invalid
    if text <> invalid and text <> "" then lineup = ParseJson(text)
    if lineup = invalid then lineup = { channels: [] }
    m.top.fromCache = fromCache
    m.top.lineup = lineup
end sub
