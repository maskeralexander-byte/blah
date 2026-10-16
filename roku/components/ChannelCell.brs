sub init()
    m.number = m.top.FindNode("number")
    m.logo = m.top.FindNode("logo")
    m.name = m.top.FindNode("name")
end sub

sub layout()
    c = m.top.content
    w = m.top.width
    h = m.top.height
    if c = invalid or w <= 0 or h <= 0 then return

    m.number.height = h
    if c.hasField("number") then m.number.text = c.number else m.number.text = ""

    left = 80
    boxW = w - left - 12
    boxH = h - 14
    if c.HDSMALLICONURL <> invalid and c.HDSMALLICONURL <> ""
        m.logo.visible = true
        m.name.visible = false
        m.logo.translation = [left, 7]
        m.logo.width = boxW
        m.logo.height = boxH
        m.logo.uri = c.HDSMALLICONURL
    else
        m.logo.visible = false
        m.name.visible = true
        m.name.translation = [left, 0]
        m.name.width = boxW
        m.name.height = h
        m.name.text = c.title
    end if
end sub
