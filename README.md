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
You can turn sources on or off, or add any M3U/M3U8 playlist link you're entitled to use
(for example your TV provider's IPTV link) under **Settings → Channel sources**.

Because these are public lists, some streams go offline at any time. If a channel doesn't play,
press ▲/▼ to move on.

## Using it

**On a TV remote**
- Browse: D-pad to move, OK to play, → to the ★ to favorite.
- Watching: ▲/▼ or CH+/CH− change channel, number keys jump to a channel number,
  OK shows channel info, MENU toggles favorite, BACK returns to the list.

**On a phone**
- Tap ☰ for categories and countries, search at the top.
- While watching, tap the screen to show the controls (back, channel arrows, ★).

## Building

Requires JDK 17+ and the Android SDK (platform 35).

```sh
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest    # parser tests
```

The release build is signed with the debug key so it installs directly. To install on a TV box,
sideload the APK (for example with `adb install` or the "Downloader" app on Fire TV).
