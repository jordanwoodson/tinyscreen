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
  Kick/Twitch/YouTube/Rumble link to Tinyscreen. Auto tries another available live platform when a source fails.
  Kick clips open on Kick.

**Player**
- Material 3 controls: play/pause, ±10 s and double-tap seek, DVR/VOD seek bar, LIVE / go-to-live chip,
  quality picker, playback speed, audio-only mode, sleep timer, mute, fit/zoom (pinch too), stats.
- **More → Share this moment** sends a bigscreen link starting at the current VOD position.
  **Menu → Continue watching** reopens the last 12 unfinished videos at their saved positions.
  The history stays on this device and can be cleared from that menu.
- The native player's settings offer captions and alternate audio when the video includes those tracks.
  It remembers the languages you select. Reloading a VOD preserves its position and play/pause state.
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

**Platform chats**
- Swipe or tap between destiny.gg chat and Destiny's chat on each platform he streams to: Kick (always),
  YouTube, Twitch, Rumble and Facebook while live there. `dggApi:streamInfo` lists exactly those five.
- A tab for the chat of whoever bigscreen is showing ("Kick · anythingelse") on Kick, Twitch, YouTube
  live and Rumble.
- Each is the platform's own popout chat, so signing in (tab menu → Sign in) lets you chat there too.
  Google may refuse sign-in inside the app; the YouTube tab offers the YouTube app instead.

**Layout**
- Portrait: stream, info, top embeds and chat stacked like the site on mobile.
- Landscape: chat beside the stream with a drag handle, Swap and Close; Cinema Mode; Popout (chat-only
  with a mini player).

Sign in from the menu with any of destiny.gg's login options (Google may refuse embedded browsers;
Kick, Twitch, X, Reddit and Discord work).

## Install with Obtainium

App changes pushed to `main` build a signed APK and publish it as a GitHub release
(`.github/workflows/release.yml`). Because this repository is private, Obtainium needs a token:

1. Create a GitHub **fine-grained personal access token** with access to only this repository and
   **Repository permissions → Contents: Read-only** (classic tokens need the `repo` scope).
2. In Obtainium: **Settings → GitHub → Personal access token**, paste the token.
3. **Add app** → `https://github.com/jordanwoodson/tinyscreen` → Add. Obtainium picks up the
   `tinyscreen-v1.0.N.apk` asset from the latest release and offers updates from then on.

## Opening links on Android

From a video, choose **Share → Tinyscreen** to play it. This also works when a browser or platform app
already handles the link. For supported bigscreen links on Android 12 and later, open **Tinyscreen →
Settings → Open supported links**, enable **Open supported links**, and select the destiny.gg addresses
Android offers.

## Building

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug     # debug build (app id …tinyscreen.debug)
./gradlew :app:lintRelease                              # lint, as CI runs it
./gradlew :app:assembleRelease                          # minified release; needs key.properties, see below
```

JDK 21 and the Android SDK (platform 36) are required. Release signing reads `key.properties`
(`storeFile`, `keyAlias`, `storePassword`, `keyPassword`); without it, local release builds use the
debug key. CI writes it from the `KEYSTORE_*` Actions secrets. Never change the release key: Android
only installs updates signed with the same certificate. Keep an offline backup of the keystore and
its passwords: Actions secrets can't be read back, and without the key every phone would have to
uninstall the app to update it.

Lint findings that predate the lint step are in `app/lint-baseline.xml`; a new lint error fails the
build. After fixing old findings, regenerate it with `./gradlew :app:updateLintBaseline`.

### Smoke-testing the release build

The release workflow runs a minified build with a bundled video on an Android emulator. It checks
playback recovery, forward progress, paused reload, timestamp sharing, saved history and track selection.
To run it against a booted emulator:

```sh
./gradlew :app:assembleSmoke
python3 scripts/test-playback.py -s emulator-5554
```

Running **Playback on Android** manually in Actions also checks a public YouTube video using the production
resolvers. To run that check locally after installing the release APK, use
`python3 scripts/test-playback.py -s emulator-5554 --public-youtube`.

The release build is shrunk and obfuscated by R8, which can break code the debug build runs fine.
To check a release APK on a device or emulator:

```sh
./gradlew :app:assembleRelease
scripts/smoke-test-release.sh -s emulator-5554     # serial from `adb devices -l`
```

It installs `app/build/outputs/apk/release/app-release.apk` (or the APK you pass), launches the
app, opens a YouTube embed, and fails if the app crashed, stopped responding, or logged an exception
R8 typically causes. Logcat and a screenshot are saved in `app/build/smoke-test/`. A local release
uses the debug key when the release keystore is absent. For installations signed with a different
key, `--replace` uninstalls the installed copy first, which deletes its data. `-h` lists the options.

## Releases

- Every push to `main` that touches `app/`, `gradle/`, the Gradle scripts or the release workflow
  publishes a release; running the workflow by hand only publishes from `main`. Pull requests run
  the tests, lint, emulator playback check and a release build (`.github/workflows/check.yml`) but publish nothing.
- Versions are `1.0.N`, counting up from the newest tag (`scripts/next-version.sh`, which also
  prints the next version when run locally). The version code is `MAJOR×1,000,000 + MINOR×1,000 + N`,
  so 1.0.5 is 1000005, and moving to 1.1.0 (change `VERSION_LINE` in the script) still goes up.
  Releases 1.0.1–1.0.4 used codes 1–4. Don't delete release tags: the next release would reuse the
  number.
- Release notes are the commit subjects since the previous release.
- The build job has a read-only token; only the publish job can create the tag and release. The
  keystore exists on the runner only while the signing build runs. Actions are pinned to commit
  SHAs, which Renovate keeps current.
- Each run keeps the R8 mapping (`r8-mapping-v1.0.N`) as a workflow artifact for 90 days. To read
  a stack trace from a release: `retrace mapping.txt stacktrace.txt` (from the Android SDK's
  `cmdline-tools`).
- Dependency updates come from Renovate (`renovate.json`) as weekly grouped PRs. OkHttp stays on
  4.x because 5.x needs compileSdk 37.
