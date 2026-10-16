' Live TV for Roku.
' Runs on its own from the Roku home screen. The phone app can also start it or send it
' commands over ECP: /launch/dev?... when it's closed, /input?... while it runs
' (contentId=<stream> to play a channel, or command=channel_up|channel_down|number|search|guide|favorite).
sub Main(args as Dynamic)
    screen = CreateObject("roSGScreen")
    port = CreateObject("roMessagePort")
    screen.SetMessagePort(port)
    print "[livetv] starting, version "; CreateObject("roAppInfo").GetVersion()
    scene = screen.CreateScene("PlayerScene")
    screen.Show()

    input = CreateObject("roInput")
    input.SetMessagePort(port)

    if args <> invalid and (args.contentId <> invalid or args.command <> invalid)
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
