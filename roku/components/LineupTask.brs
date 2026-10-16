sub init()
    m.top.functionName = "work"
end sub

sub work()
    if m.top.mode = "cache"
        lineup = readLineupCache()
        if lineup = invalid then lineup = { channels: [], builtAt: 0 }
        m.top.lineup = lineup
        return
    end if
    m.top.lineup = buildLineup(m.top.force)
end sub
