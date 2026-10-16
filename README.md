# Live TV

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

## On the TV (Roku)

The Roku app (`roku/`) is a standalone live TV app. It downloads the English channel list and the
free guide feeds (Pluto TV and Samsung TV Plus, about 370 channels with listings) by itself, keeps
a copy, and refreshes it in the background, so it works from the Roku home screen with just the Roku
remote:

- **Up/Down** change channel, **Left/Right** show the channel banner, **✱** adds or removes a
  favorite, **Back** exits.
- **OK** opens a cable-style guide: the live picture keeps playing in the box at the top left, with a
  time grid of channels and shows, and a menu (**Left**) with All Channels, Favorites, Recents, the
  biggest categories, **Search**, and an Official / All streams switch.
- A "now playing" card slides in on every channel change and when a new show starts.

### Installing it (once)

1. On the Roku remote press **Home ×3, Up ×2, Right, Left, Right, Left, Right**, choose
   **Enable installer and restart**, and set a password.
2. In the phone app tap the cast icon, pick the Roku, and enter that password when asked. The app
   installs Live TV on the Roku and keeps it updated after that.

(Manual alternative: open `http://<roku-ip>` in a browser, sign in as `rokudev`, and upload
`roku-receiver.zip`, which the build outputs to `app/build/generated/roku-assets/`.)

### The phone as a remote

Once connected, the **remote** icon in the phone app opens a remote for the TV: D-pad and OK, Back,
Home, Guide, Favorite, CH ▲/▼, volume and mute, play/pause, a number pad that tunes the TV's channel
numbers, and **Search on TV**, which types your search into the TV's guide. Tapping a channel in the
phone app plays it on the TV. Everything goes over Roku's External Control Protocol on your Wi-Fi.

- If no Roku shows up, enter its IP address (on the Roku: Settings → Network → About).
- If the Roku refuses requests, set Settings → System → Advanced system settings → Control by
  mobile apps → Network access to **Default** or **Permissive**.

## Look

Night-sky gradients with neon violet / cyan / pink accents, Poppins for text and Bungee for channel
numbers and the clock (both SIL Open Font License, see `licenses/`). The Roku artwork, including the
9-patch images for the guide grid, is drawn by `tools/make_roku_art.py`.

## Building

Requires JDK 17+ and the Android SDK (platform 35).

```sh
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest    # parser tests
```

The release build is signed with the debug key so it installs directly. To install on a TV box,
sideload the APK (for example with `adb install` or the "Downloader" app on Fire TV).
