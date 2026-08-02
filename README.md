# TrueHiFi (Android)

Scans your device's music library and flags FLAC/WAV/ALAC/APE/AIFF files that
look like a lossy source (MP3/AAC) upsampled or repackaged as "lossless" or
"hi-res."

## How detection works

1. **Scan** — queries `MediaStore` for all lossless/hi-res-container audio
   files (lossy formats like MP3/AAC are skipped — they don't claim to be
   anything they're not).
2. **Decode** — for each file, pulls a handful of short PCM windows spread
   across the track (`AudioDecoder.kt`, using `MediaExtractor` + `MediaCodec`,
   requesting float PCM output so extra bit-depth precision survives decode).
3. **Analyze** — runs an FFT on each window (`FFT.kt`, `SpectralAnalyzer.kt`)
   and looks for a hard cutoff frequency above which there's essentially no
   energy, plus a **bit-depth padding check** (`BitDepthAnalyzer.kt`) on the
   lowest byte of >16-bit files, since a genuinely upsampled/padded fake
   often has zeroed-out low bits regardless of what the spectrum shows.
4. **Classify** — combines both signals into a verdict (Genuine / Suspicious
   / Fake), a **confidence score** (based on how sharp the spectral cutoff
   is, how consistent it is across windows, and how many windows were
   analyzed), and a plain-English reason (`FakeDetector.kt`).

**This is a heuristic, not a certainty.** Treat "Fake" as a strong signal,
"Suspicious" as a nudge to double check — use the confidence score and the
detail screen's spectrum plot to judge for yourself.

## Features

- **Confidence score** — shown as a percentage everywhere a verdict appears.
- **Bit-depth padding check** — flags files claiming >16-bit depth where the
  extra bits are silent, independent of the frequency-cutoff test.
- **Deep scan** — from a track's detail screen, re-analyze it with 24 windows
  instead of 6 for a slower, more confident second opinion.
- **Detail screen** — tap any track for its full spectrum plot, cutoff
  frequency, bit-depth check result, and reasoning.
- **Filter & sort** — filter the result list by verdict, sort by verdict,
  name, or confidence.
- **Result caching (Room)** — results are cached by file path + size + last-
  modified time, so re-scanning a library only re-analyzes new/changed
  files. "Clear cache & rescan" (overflow menu) forces a full re-analysis.
- **Foreground service** — scanning runs in `ScanForegroundService` with a
  progress notification, so Android won't kill a long scan if you switch
  apps.
- **AMOLED dark theme** — dark mode uses true black (`#000000`) backgrounds,
  not Material's usual dark grey, since that's what actually saves power on
  an OLED screen.

## Project structure

```
app/src/main/java/com/fakehifi/detector/
├── MainActivity.kt              # List screen, navigation, filter/sort UI
├── model/                       # Data classes + shared ScanUiState
├── scanner/MusicScanner.kt      # MediaStore query
├── analysis/
│   ├── AudioDecoder.kt          # PCM extraction (float-precision output)
│   ├── FFT.kt                    # Radix-2 FFT
│   ├── SpectralAnalyzer.kt      # Cutoff frequency + confidence scoring
│   ├── BitDepthAnalyzer.kt      # Low-bit padding check
│   └── FakeDetector.kt          # Combines signals into a verdict
├── db/                           # Room: entity, DAO, database
├── repository/ScanRepository.kt  # Shared state between service and UI
├── service/ScanForegroundService.kt
├── viewmodel/ScanViewModel.kt
└── ui/
    ├── DetailScreen.kt           # Per-track detail + spectrum chart
    └── theme/                    # AMOLED dark + light Material3 themes
```

## Setting this up

This project was generated outside Android Studio, so it's missing the
Gradle wrapper binary (a binary jar that can't be hand-written). To open it:

1. Open Android Studio → **Open** → select the `TrueHiFi` folder.
2. Android Studio will detect the missing wrapper and offer to fix the
   project / regenerate `gradlew` — accept that, or run `gradle wrapper`
   yourself if you have Gradle installed locally.
3. Let Gradle sync (it needs internet access to `google()` and
   `mavenCentral()` the first time, to pull the Android Gradle Plugin,
   Compose, Room/KSP, and Navigation libraries). If the KSP plugin version
   pinned in the root `build.gradle.kts` (`1.9.24-1.0.20`) fails to
   resolve, check the [KSP releases page](https://github.com/google/ksp/releases)
   for the latest version built against Kotlin 1.9.24 and swap it in.
4. Run on a device or emulator running Android 8.0 (API 26) or later.

## Running this without a PC

### Option A: GitHub Actions (recommended — the cloud does the heavy lifting)

A ready-to-go workflow is included at `.github/workflows/build.yml`.

1. From your phone, create a new GitHub repo and upload this project's files
   (GitHub's web "Add file → Upload files" accepts a whole folder; or use
   Termux + `git push` with a personal access token).
2. Make sure `.github/workflows/build.yml` is present in the repo (it is, if
   you uploaded everything).
3. Go to the repo's **Actions** tab — the workflow runs automatically on
   push (or trigger it manually with "Run workflow").
4. Wait a few minutes, open the finished run, and download the `app-debug`
   artifact from your phone's browser.
5. Unzip it, tap `app-debug.apk`, and install (you'll need to allow "install
   unknown apps" for your browser or file manager once, in Android settings).

This avoids needing to compile Compose on your device, which is the slow,
memory-hungry part.

### Option B: Build entirely on-device with Termux

Heavier and more fiddly, but fully local, no cloud account needed. Install
**Termux from F-Droid** (the Play Store build is outdated). Then, for the
most reliable results, build inside a proot Ubuntu environment rather than
raw Termux:

```bash
pkg update && pkg upgrade -y
pkg install proot-distro -y
proot-distro install ubuntu
proot-distro login ubuntu

apt update && apt upgrade -y
apt install -y openjdk-17-jdk gradle aapt wget unzip git
```

Get the Android SDK command-line tools and accept licenses:

```bash
mkdir -p ~/android-sdk/cmdline-tools && cd ~/android-sdk/cmdline-tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-*.zip && mv cmdline-tools latest
export ANDROID_HOME=$HOME/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

Termux's Gradle downloads an x86 `aapt2` that won't run on a phone's ARM
CPU — point it at the working one `apt install aapt` just gave you:

```bash
mkdir -p ~/.gradle
echo "android.aapt2FromMavenOverride=$(which aapt2)" >> ~/.gradle/gradle.properties
```

Then build (after copying/unzipping the project into this environment, e.g.
via `termux-setup-storage` and copying from your Downloads folder):

```bash
cd ~/TrueHiFi
gradle assembleDebug --no-daemon
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk` — open it with
a file manager to install. First build downloads a lot of dependencies
(needs decent internet) and Compose compilation is CPU/RAM-heavy, so expect
it to take a while; the exact SDK command-line-tools download URL/version
above may have moved on, so search "android sdk commandlinetools download"
if the `wget` 404s.

## Known limitations / next steps

- **ALAC** decodes via Android's built-in software decoder (available since
  API 24) and should work out of the box. **APE (Monkey's Audio) has no
  native Android decoder at all** — files in that format will come back as
  "Unknown." Real APE support would mean bundling a native decoder (e.g. via
  NDK), which is a substantial separate project, not something addressable
  from a manifest/permissions change.
- **Bit depth for FLAC** often falls back to displaying "16-bit" even for
  genuine 24-bit files, because Android doesn't reliably expose a pre-decode
  bit-depth key for compressed lossless codecs the way it does for raw WAV.
  The padding check itself still runs whenever float decode succeeds; it's
  just not always labeled against a known "claimed" depth.
- **Thresholds and confidence weights** in `FakeDetector.kt` and
  `SpectralAnalyzer.kt` are reasonable starting points, not tuned against a
  large labeled dataset — adjust if you find false positives/negatives.
- **No app icon** included — add one via Android Studio's Image Asset tool
  when you're ready.
