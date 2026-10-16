' Free Live TV receiver for Roku.
' The Android app starts this channel over ECP (/launch/dev?contentId=<stream url>&...)
' and sends later channel changes to it while it runs (/input?contentId=...).
sub Main(args as Dynamic)
    screen = CreateObject("roSGScreen")
    port = CreateObject("roMessagePort")
    screen.SetMessagePort(port)
    scene = screen.CreateScene("PlayerScene")
    screen.Show()

    input = CreateObject("roInput")
    input.SetMessagePort(port)

    if args <> invalid and args.contentId <> invalid then scene.request = args

    while true
        msg = Wait(0, port)
        msgType = Type(msg)
        if msgType = "roSGScreenEvent"
            if msg.IsScreenClosed() then return
        else if msgType = "roInputEvent"
            if msg.IsInput()
                info = msg.GetInfo()
                if info <> invalid and info.contentId <> invalid then scene.request = info
            end if
        end if
    end while
end sub
