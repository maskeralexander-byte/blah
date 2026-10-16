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
    try
        buildLineup(m.top.force, m.top)
    catch e
        m.top.progress = "Something went wrong loading channels: " + e.message
        m.top.lineup = { channels: [], builtAt: 0, failed: true }
    end try
end sub
