' Free Live TV for Roku.
' The Android app launches this channel over ECP (/launch/dev?contentId=<stream>&lineup=<url>&index=<n>)
' and sends channel changes while it runs (/input?...). It can also be opened from the Roku home
' screen, in which case it reloads the last channel lineup it saw.
sub Main(args as Dynamic)
    screen = CreateObject("roSGScreen")
    port = CreateObject("roMessagePort")
    screen.SetMessagePort(port)
    scene = screen.CreateScene("PlayerScene")
    screen.Show()

    input = CreateObject("roInput")
    input.SetMessagePort(port)

    if args <> invalid and (args.contentId <> invalid or args.lineup <> invalid)
        scene.request = args
    else
        scene.request = { resume: "true" }
    end if

    while true
        msg = Wait(0, port)
        msgType = Type(msg)
        if msgType = "roSGScreenEvent"
            if msg.IsScreenClosed() then return
        else if msgType = "roInputEvent"
            if msg.IsInput()
                info = msg.GetInfo()
                if info <> invalid then scene.request = info
            end if
        end if
    end while
end sub
