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

## Using it

**On a TV remote**
- Browse: D-pad to move, OK to play, → to the ★ to favorite.
- Watching: ▲/▼ or CH+/CH− change channel, number keys jump to a channel number,
  OK shows channel info, MENU toggles favorite, BACK returns to the list.

**On a phone**
- Tap ☰ for categories and countries, search at the top.
- While watching, tap the screen to show the controls (back, channel arrows, ★).

## Casting to a Roku

Tap the cast icon (top bar, or top-right while watching) to pick a Roku on the same Wi-Fi. The
Roku plays the stream in its built-in media player, and your phone becomes the remote: changing
channels in the app changes them on the TV. Uses Roku's External Control Protocol, so no Roku
app needs to be installed.

- If no Roku shows up, enter its IP address (on the Roku: Settings → Network → About).
- If the Roku refuses, set Settings → System → Advanced system settings → Control by mobile apps →
  Network access to **Default** or **Permissive**.
- Channels that need custom headers (user agent / referrer) may not load on Roku, which can't
  send them.

## Building

Requires JDK 17+ and the Android SDK (platform 35).

```sh
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest    # parser tests
```

The release build is signed with the debug key so it installs directly. To install on a TV box,
sideload the APK (for example with `adb install` or the "Downloader" app on Fire TV).
