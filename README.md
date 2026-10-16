# Free Live TV

An Android app for **phones and Android TV / Google TV / Fire TV** that streams thousands of
free live TV channels. It works like a live-TV box: a channel guide, numbered channels,
channel up/down on the remote, favorites and recently watched.

## Where the channels come from

By default the app loads two community-maintained lists of **publicly available, free-to-air
streams**: broadcasters' own free streams and free ad-supported services such as Pluto TV.

| Source | Approx. channels |
| --- | --- |
| [iptv-org](https://github.com/iptv-org/iptv) | ~11,000 |
| [Free-TV/IPTV](https://github.com/Free-TV/IPTV) | ~2,000 |

Duplicates are removed. Lists refresh every 12 hours and are cached for offline start-up.
The app opens on **English** channels (about 3,300, tagged using iptv-org's language list); everything
else is under the **Other languages** tab. Playlists you add yourself always appear under English.

You can turn sources on or off, or add any M3U/M3U8 playlist link you're entitled to use
(for example your TV provider's IPTV link) under **Settings → Channel sources**.

Because these are public lists, some streams go offline at any time. To keep things watchable:

- **Auto-reconnect:** if a stream stalls or drops, the player reconnects on its own (up to 6 tries,
  backing off from 1 s to 15 s) and jumps back to the live edge if it falls behind.
- **Dead channels are hidden:** a channel that won't play is hidden for 24 hours, then tried again.
  Favorites always stay visible. Turn this off, or un-hide them, under **Channel sources**.

Note: the public lists also contain some unofficial rebroadcasts of paid channels. These tend to be the
least reliable streams, and the app makes no attempt to keep them working.

## Official channels vs. all streams

The chips under the tabs switch between **Official channels** (the default) and **All streams**.
Official means the stream comes from a free streaming service (Pluto TV, Samsung TV Plus, Plex,
Tubi, Xumo and their delivery partners), a broadcaster, or a major CDN that broadcasters use. That's
about 1,700 English channels, and they're the most reliable. **All streams** also shows everything
else in the public lists, including unofficial rebroadcasts on unknown servers. Playlists you add
yourself always count as official.

## Program guide

Channels from Pluto TV, Samsung TV Plus and Plex show what's on now (in the list) and now/next (in
the player), using the free XMLTV guides published by [i.mjh.nz](https://github.com/matthuisman/i.mjh.nz).
Guides refresh every 6 hours. Pluto channels match by channel ID; Samsung and Plex match by name.

## Using it

**On a TV remote**
- Browse: D-pad to move, OK to play, → to the ★ to favorite.
- Watching: ▲/▼ or CH+/CH− change channel, OK (or GUIDE) opens the channel list with what's on,
  ◀/▶ shows channel info, number keys jump to a channel, MENU toggles favorite, BACK closes the
  list or returns to browsing.

**On a phone**
- Tap ☰ for categories and countries, search at the top.
- While watching, tap the screen to show the controls (back, channel list, cast, channel arrows, ★).

## Casting to a Roku

Tap the cast icon (top bar, or top-right while watching) to pick a Roku on the same Wi-Fi. Your
phone becomes the remote: changing channels in the app changes them on the TV.

Current Rokus can't play a stream URL sent from a phone on their own, so the TV runs a small
receiver channel (`roku/`) that the app starts and controls over Roku's External Control Protocol.
It's installed once:

1. On the Roku remote press **Home ×3, Up ×2, Right, Left, Right, Left, Right**, choose
   **Enable installer and restart**, and set a password.
2. Cast any channel from the app. It sees the receiver is missing and asks for that password,
   then installs the receiver itself.

(Manual alternative: open `http://<roku-ip>` in a browser, sign in as `rokudev`, and upload
`roku-receiver.zip`, which the build outputs to `app/build/generated/roku-assets/`.)

**On the TV:** while casting, the Roku gets the whole channel list and guide from the phone, so
the Roku remote works on its own. **Up/Down** change channel, **OK** opens the on-screen guide (what's
on now and next, with progress), **Left/Right** show the channel banner, **Back** closes the guide or
exits. The Roku keeps a copy of the lineup, so it keeps working if the phone app closes, and you can
open Free Live TV straight from the Roku home screen to pick up where you left off. (Guide times
refresh from the phone every 5 minutes while it's reachable.)

When a newer receiver ships with the app, it updates the Roku automatically using the Developer Mode
password saved on the phone at first install.

- If no Roku shows up, enter its IP address (on the Roku: Settings → Network → About).
- If the Roku refuses requests, set Settings → System → Advanced system settings → Control by
  mobile apps → Network access to **Default** or **Permissive**.
- Older Rokus that still have the built-in "Play on Roku" player work without the receiver.

## Building

Requires JDK 17+ and the Android SDK (platform 35).

```sh
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest    # parser tests
```

The release build is signed with the debug key so it installs directly. To install on a TV box,
sideload the APK (for example with `adb install` or the "Downloader" app on Fire TV).
