# Tinyscreen

A native Android client for [destiny.gg/bigscreen](https://www.destiny.gg/bigscreen): the stream, chat and
everything around them, built with Kotlin, Jetpack Compose (Material 3) and Media3/ExoPlayer.
Tuned for the Motorola Edge 2025 on Android 16 (works on Android 12+).

Unofficial — not affiliated with destiny.gg.

## What's in it

**Stream panel**
- Plays Destiny's live stream following bigscreen's rules: a `#platform/id` embed wins, then a host while
  Destiny is offline, then Destiny live, then the offline screen with the latest VOD.
- Native playback (no web embeds) for **Kick** (live + VODs), **Twitch** (live, VODs, clips), **YouTube**
  (live + videos) and **Rumble**. Facebook, Vimeo, AngelThump and anything the native player can't open
  fall back to the site's own embed in an ad-blocked WebView, automatically.
- Badges, title and buttons like the site: Live/Embed/Host, viewers, ticking uptime, platforms,
  Subscribe, Donate, Close Embed, Change Platform. Banned embeds show the "content is blocked" screen.
- Top embeds strip (what chat is watching), refreshed live from `wss://live.destiny.gg`.
- Open anything from chat links, `/embed`, deep links (`destiny.gg/bigscreen#…`) or by sharing a
  Kick/Twitch/YouTube/Rumble link to Tinyscreen.

**Player**
- Material 3 controls: play/pause, ±10 s and double-tap seek, DVR/VOD seek bar, LIVE / go-to-live chip,
  quality picker, playback speed, audio-only mode, sleep timer, mute, fit/zoom (pinch too), stats.
- Fullscreen landscape with swipe gestures for brightness (left) and volume (right).
- Picture-in-picture (auto when you go home), background audio with media notification and lock-screen
  controls, low-latency live playback.

**Ad blocking**
- Native playback never loads web ads. Twitch stitched ads are avoided by choosing an ad-free player type,
  swapping in an ad-free rendition during mid-rolls, and stripping ad segments as a last resort.
- WebViews (fallback players, login, web chat) block ad/tracker hosts and hide leftover ad UI.

**Chat** (native, talking to `wss://chat.destiny.gg/ws`)
- Emotes (including animated sprite-strip emotes), flairs, rainbow/flair name colours, greentext, `/me`,
  combos (C-C-C-COMBO), NSFW/NSFL/spoiler links, embed buttons, mentions and highlights, tags,
  ignore/harsh ignore, censored messages, pinned message, polls, sub/gift/donation events, broadcasts.
- Autocomplete for emotes, users and commands; emote picker; user list; user info sheet with
  stalk/mentions/RustleSearch logs and mod actions; all of the site's slash commands.
- Whispers inbox and conversations, optional notifications for whispers and highlights.
- Prefer the site's chat? Turn on **Settings → Use web chat**.

**Layout**
- Portrait: stream, info, top embeds and chat stacked like the site on mobile.
- Landscape: chat beside the stream with a drag handle, Swap and Close; Cinema Mode; Popout (chat-only
  with a mini player).

Sign in from the menu with any of destiny.gg's login options (Google may refuse embedded browsers;
Kick, Twitch, X, Reddit and Discord work).

## Install with Obtainium

Every push to `main` builds a signed APK and publishes it as a GitHub release
(`.github/workflows/release.yml`). Because this repository is private, Obtainium needs a token:

1. Create a GitHub **fine-grained personal access token** with access to only this repository and
   **Repository permissions → Contents: Read-only** (classic tokens need the `repo` scope).
2. In Obtainium: **Settings → GitHub → Personal access token**, paste the token.
3. **Add app** → `https://github.com/jordanwoodson/tinyscreen` → Add. Obtainium picks up the
   `tinyscreen-v1.0.N.apk` asset from the latest release and offers updates from then on.

## Building

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug     # debug build (app id …tinyscreen.debug)
./gradlew :app:assembleRelease                          # needs key.properties, see below
```

JDK 21 and the Android SDK (platform 36) are required. Release signing reads `key.properties`
(`storeFile`, `keyAlias`, `storePassword`, `keyPassword`); without it, local release builds use the
debug key. CI writes it from the `KEYSTORE_*` Actions secrets. Never change the release key: Android
only installs updates signed with the same certificate.
