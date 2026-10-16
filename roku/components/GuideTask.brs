sub init()
    m.top.functionName = "build"
end sub

sub build()
    channels = m.top.channels
    f = m.top.filter
    if channels = invalid then channels = []
    if f = invalid then f = { mode: "all" }
    now = CreateObject("roDateTime").AsSeconds()
    start = now - (now MOD 1800)

    ' Which channels, in which order.
    order = []
    mode = f.mode
    if mode = "recent"
        indexByUrl = {}
        for i = 0 to channels.Count() - 1
            indexByUrl[channels[i].url] = i
        end for
        if f.recents <> invalid
            for each url in f.recents
                if indexByUrl.DoesExist(url) then order.Push(indexByUrl[url])
            end for
        end if
    else
        query = ""
        if f.value <> invalid then query = LCase(f.value)
        for i = 0 to channels.Count() - 1
            ch = channels[i]
            keep = true
            if mode = "fav"
                keep = (ch.fav = true)
            else if mode = "group"
                keep = (ch.g = f.value)
            else if mode = "search"
                keep = matches(ch, query, now)
            end if
            if keep then order.Push(i)
        end for
    end if

    root = CreateObject("roSGNode", "ContentNode")
    for each i in order
        ch = channels[i]
        row = root.CreateChild("ContentNode")
        row.title = textOf(ch.name)
        row.AddFields({ number: (i + 1).ToStr() })
        if ch.logo <> invalid then row.HDSMALLICONURL = ch.logo

        added = 0
        if ch.p <> invalid
            for each p in ch.p
                if p.e > start
                    prog = row.CreateChild("ContentNode")
                    prog.title = p.t
                    prog.playStart = p.s
                    prog.playDuration = p.e - p.s
                    if p.d <> invalid then prog.description = p.d
                    added = added + 1
                end if
            end for
        end if
        if added = 0
            ' No listings: one block for the whole window, like a cable guide's "channel name" cell.
            prog = row.CreateChild("ContentNode")
            prog.title = textOf(ch.name)
            prog.playStart = start
            prog.playDuration = 6 * 3600
            prog.description = "Live programming"
        end if
    end for

    m.top.rowMap = order
    m.top.startTime = start
    m.top.content = root
end sub

function matches(ch as Object, query as String, now as Integer) as Boolean
    if query = "" then return true
    if Instr(1, LCase(textOf(ch.name)), query) > 0 then return true
    if ch.p <> invalid
        for each p in ch.p
            if p.e > now and Instr(1, LCase(p.t), query) > 0 then return true
        end for
    end if
    return false
end function

function textOf(v as Dynamic) as String
    if v = invalid then return ""
    return v
end function
