# Wrackline

A local music player for Android — plays whatever's on your device (scanned via MediaStore),
keeps playing with the screen locked, and reacts visually to the actual audio playing.

## Installing

Get it through [Palewake](https://github.com/TravBuildsSick/palewake) — Wrackline is listed in
its catalog and updates the same way. You can also grab the APK directly from this repo's
[Releases page](https://github.com/TravBuildsSick/wrackline/releases) if you'd rather sideload it
standalone (see Palewake's README for the sideload/Play-Protect steps, they're identical).

## What it does

- **Background/lock-screen playback**: a `MediaSessionService` (Media3/ExoPlayer) keeps audio
  running and gives lock-screen transport controls after the screen locks or the app is
  backgrounded.
- **Audio-reactive visualizer**: the vinyl-ring UI is driven by a real
  `android.media.audiofx.Visualizer` attached to the player's live audio session — not a canned
  animation.
- **On-device library**: scans `MediaStore.Audio.Media` for playable tracks. Needs
  `READ_MEDIA_AUDIO` (API 33+) / `READ_EXTERNAL_STORAGE` (below that), requested on launch.
  `RECORD_AUDIO` is also requested for the visualizer, and `POST_NOTIFICATIONS` for the
  lock-screen notification on API 33+ — none of these block playback if denied, they just turn
  off the corresponding feature.
- **Preinstalled/downloadable track packs**: `packs.json` at the repo root lists content packs,
  each hosted as a `.zip` asset on its own GitHub release (tag `pack-<id>`). Any pack marked
  `"preinstalled": true` downloads and unzips into app-private storage automatically on first
  launch, merged into the library alongside whatever's actually on the device.

## Publishing an update

```
./push_update.sh "release notes"
```

Builds a release APK (signed with the key at `~/.android/keystores/wrackline/`, never the debug
key — a debug-signed build can't install as an update over a real release) and publishes it as a
GitHub release tagged `v<versionCode>`. Bump `versionCode` in `app/build.gradle.kts` first — the
in-app updater only detects a new build via that integer.

## Adding a track pack

1. Zip the tracks flat (no nested folders): `zip -j pack.zip *.mp3`
2. `gh release create pack-<id> pack.zip --title "<Name>" --notes "..."`
3. Add an entry to `packs.json`:
   ```json
   {
     "id": "pack-id",
     "name": "Display Name",
     "description": "One line.",
     "preinstalled": false,
     "release_tag": "pack-<id>"
   }
   ```

Only `"preinstalled": true` packs actually do anything right now — the app auto-downloads those
on first launch. There's no in-app UI yet to browse and manually download `preinstalled: false`
packs; `packs.json` can list them ahead of that being built, but they're inert until then.
