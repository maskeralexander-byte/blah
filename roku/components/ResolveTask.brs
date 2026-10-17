sub init()
    m.top.functionName = "resolve"
end sub

sub resolve()
    src = m.top.source
    url = ""
    try
        if Left(src, 7) = "xumo://" then url = resolveXumo(Mid(src, 8))
    catch e
        print "[livetv] resolve error: "; e.message
    end try
    m.top.url = url
end sub
