sub init()
    m.bg = m.top.FindNode("bg")
    m.bar = m.top.FindNode("bar")
    m.name = m.top.FindNode("name")
    m.now = m.top.FindNode("now")
    m.next = m.top.FindNode("next")
end sub

sub onContent()
    c = m.top.itemContent
    if c = invalid then return
    m.name.text = c.title
    m.now.text = c.description
    m.next.text = c.shortDescriptionLine2
    progress = 0
    if c.hasField("progress") then progress = c.progress
    m.bar.width = Int(712 * progress)
end sub

sub onFocus()
    if m.top.itemHasFocus
        m.bg.color = "0x8B73FF55"
    else
        m.bg.color = "0x00000000"
    end if
end sub
