<div align="center">

# Velox

**A video player for Android built around one goal: nothing should ever feel like it is waiting.**

[![CI](https://github.com/BOSSincrypto/veloxplay/actions/workflows/ci.yml/badge.svg)](https://github.com/BOSSincrypto/veloxplay/actions/workflows/ci.yml)
[![Release](https://github.com/BOSSincrypto/veloxplay/actions/workflows/release.yml/badge.svg)](https://github.com/BOSSincrypto/veloxplay/actions/workflows/release.yml)
[![Latest release](https://img.shields.io/github/v/release/BOSSincrypto/veloxplay?label=download)](https://github.com/BOSSincrypto/veloxplay/releases/latest)
[![APK size](https://img.shields.io/badge/APK-~4.2%20MB-brightgreen)](https://github.com/BOSSincrypto/veloxplay/releases/latest)
[![minSdk](https://img.shields.io/badge/minSdk-31-blue)](https://developer.android.com/about/versions/12)

[Русская версия](README.ru.md)

</div>

---

## What it does

| | |
|---|---|
| **Global playback speed** | Set it once, in settings or from the player. Every video plays at that speed, this session and the next. 0.25x to 4x. |
| **Picture in picture** | Auto-enters on Home press. Play/pause and ±10 s controls live inside the PiP window. |
| **Instant seeking** | Keyframe-snapped seeks plus Media3 scrubbing mode while you drag. No spinner between the drag and the frame. |
| **Gestures** | Left half = brightness, right half = volume, sideways = scrub, double-tap = skip, hold = temporary speed-up. |
| **Background playback** | MediaSession notification, lock-screen controls, audio keeps going when you leave. |
| **Position memory** | Reopens where you left off, unless you were near the start or the end. |
| **Frame capture** | Saves the current frame to `Pictures/Velox`. |
| **A-B loop** | Two taps set the range, a third clears it. |
| **Local + streaming** | Device library via MediaStore, plus HTTP, HLS and DASH URLs. |

## Why it is fast

Speed here is architecture, not a setting. The decisions that matter:

**One player, no IPC.** The `ExoPlayer` instance lives in [`PlayerHolder`](app/src/main/java/io/github/bossincrypto/velox/PlayerHolder.kt) and is shared by the Activity and the playback service, which run in the same process. Play, pause and seek are direct method calls — most players route these through a `MediaController` and pay a Binder round trip on every one of them.

**The decoder is never rebuilt.** `PlayerActivity` declares the full `configChanges` set and `launchMode="singleTask"`, so rotation, PiP entry and re-opening the same file all reuse the warm `MediaCodec` instance. Tearing it down and back up is what produces the black flash other players show on rotate.

**SurfaceView, not TextureView.** Video frames go to a surface the system compositor can hand to a hardware overlay. A `TextureView` would force a GPU copy every single frame.

**Buffers tuned for latency.** `bufferForPlaybackMs` is 250 ms instead of the 2500 ms default, so playback starts almost immediately. A 20 s back buffer is retained, which makes rewinding inside that window need no I/O and no decoder flush at all.

**Seeks snap to keyframes.** `SeekParameters.CLOSEST_SYNC` skips the decode-and-discard pass that exact seeking requires. During a seek-bar drag, Media3 scrubbing mode keeps the whole pipeline in its seek-optimised state instead of doing a full flush per drag event. Exact seeking is one switch away in settings.

**The UI does nothing it does not have to.** Views and view binding, no Compose recomposition over the video surface. The progress ticker runs at 4 Hz, only while something is playing, and only touches views while the overlay is visible. R8 full mode plus resource shrinking take the release APK to about 4.2 MB.

## Install

Grab the APK from [Releases](https://github.com/BOSSincrypto/veloxplay/releases/latest). Every merge into `main` publishes a new one automatically.

Releases are signed with [`keystore/velox-public.p12`](keystore), which is committed on purpose. It is not a secret: the point is that every release shares one signature, so each new APK installs straight over the previous one. Anyone can also reproduce a release build byte for byte. Do not treat it as a trust anchor — if you fork this for real distribution, swap in a private keystore (see below).

## Build

Requires JDK 17. Everything else is fetched by Gradle.

```bash
git clone https://github.com/BOSSincrypto/veloxplay.git
cd veloxplay
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

Needs SDK platform `android-37.1` and build-tools `36.0.0`; if you build outside Android Studio, install them with `sdkmanager "platforms;android-37.1" "build-tools;36.0.0"` and point `local.properties` at your SDK.

### Signing your own releases

Replace the debug signing config in [`app/build.gradle.kts`](app/build.gradle.kts):

```kotlin
signingConfigs {
    create("release") {
        storeFile = file(System.getenv("KEYSTORE_PATH") ?: "release.jks")
        storePassword = System.getenv("KEYSTORE_PASSWORD")
        keyAlias = System.getenv("KEY_ALIAS")
        keyPassword = System.getenv("KEY_PASSWORD")
    }
}
```

Then point `buildTypes.release.signingConfig` at it and add the four values as repository secrets, decoding the keystore in the release workflow before the build step.

## Stack

Everything is pinned to what was current on 13 August 2026.

| | |
|---|---|
| Media3 / ExoPlayer | 1.11.0 |
| Android Gradle Plugin | 9.3.1 (Kotlin support built in) |
| Gradle | 9.7.0 |
| compileSdk / targetSdk | 37.1 / 37 (Android 17) |
| minSdk | 31 (Android 12) |
| JDK | 17 |

Six dependencies total, all first-party AndroidX or Google. No image loader, no DI framework, no Compose.

## Layout

```
app/src/main/java/io/github/bossincrypto/velox/
├── PlayerHolder.kt        shared ExoPlayer + all tuning
├── PlayerActivity.kt      surface, controls, gestures, PiP, A-B, snapshots
├── PlaybackService.kt     MediaSession wrapper for background playback
├── PipActionReceiver.kt   PiP window buttons
├── LibraryActivity.kt     MediaStore list, file picker, URL entry
├── VideoLibrary.kt        MediaStore queries, thumbnails, RecyclerView adapter
├── SettingsActivity.kt    global settings
├── Prefs.kt               every setting, one SharedPreferences file
└── Format.kt              duration / size / speed formatting
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Short version: keep the dependency list short and do not put work on the frame path.

## License

[MIT](LICENSE).
