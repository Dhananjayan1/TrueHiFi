# TrueHiFi (Android)

TrueHiFi is a scientific audio transparency tool for Android. It scans your music library to detect "fake" high-fidelity files—such as upsampled lossy transcodes (MP3/AAC repackaged as FLAC) or zero-padded bit-depth "Hi-Res" files.

Unlike simple frequency-cutoff tools, TrueHiFi uses a modular, multi-signal analysis engine that adapts to the actual content of the audio to provide a reliable, evidence-based verdict.

## Scientific Analysis Engine

TrueHiFi's detection logic is built on a robust pipeline that evaluates multiple independent signals:

1.  **Content-Aware Spectral Analysis**: Uses **Spectral Centroid** calculation to adapt bandwidth targets. A "dark" classical recording isn't held to the same 20kHz standard as a bright EDM track, significantly reducing false "Suspicious" verdicts on legitimate but naturally bandwidth-limited content.
2.  **Spectral Curvature (2nd Derivative)**: Beyond simple slope measurement, the engine prototypes curvature analysis to identify the characteristic "shoulder" of digital low-pass filters used in lossy encoders, distinguishing them from natural analog roll-offs.
3.  **Automatic Deep-Scan Escalation**: If the initial "Quick Scan" produces ambiguous data or high spectral variance, the engine automatically triggers an internal **Deep Scan** (analyzing 24 windows instead of 6) to resolve the uncertainty before presenting a result.
4.  **Confidence Saturation Model**: A weighted evidence-fusion engine that prioritizes core spectral integrity (bandwidth and slope) while factoring in secondary signals like **Joint Stereo HF Collapse** and **LSB Bit-Depth Padding**.
5.  **Multi-Window FFT Averaging**: Each analysis window (1500ms) averages multiple FFT chunks to significantly improve the Signal-to-Noise Ratio (SNR) and provide a stable frequency footprint.

## Features

-   **Transparency Engine**: View a detailed "Confidence Breakdown" for every track, showing exactly how different signals (Spectral, Stereo, Metadata) influenced the final verdict.
-   **Advanced Visualization**: Toggle between a **High-Resolution Frequency Spectrum** (with window overlays) and a **Time-Frequency Spectrogram** to visually inspect the audio's structure.
-   **Bit-Depth Audit**: Detects "Fake Hi-Res" where 16-bit content is placed in a 24-bit container with zero-padded lowest bits.
-   **Smart Library Scanning**: Powered by **WorkManager**, scanning runs reliably in the background. Results are cached via **Room** (using file-hash-like heuristics) to ensure subsequent scans only process new or modified files.
-   **Adaptive Filtering & Sorting**: Easily organize your library by Verdict, Confidence, Title, or Date Added.
-   **AMOLED Dark Theme**: A power-efficient, true-black interface designed for modern high-end displays.

## Project Structure

```
app/src/main/java/com/fakehifi/detector/
├── analysis/
│   ├── AudioDecoder.kt      # High-precision float PCM extraction
│   ├── SpectralAnalyzer.kt  # Cutoff, Centroid, and Curvature detection
│   ├── StereoAnalyzer.kt    # Joint Stereo HF Collapse check
│   ├── BitDepthAnalyzer.kt  # LSB padding audit
│   ├── QualityAnalyzer.kt   # Dynamic Range (DR) and Clipping metrics
│   ├── DetectorEngine.kt    # Orchestrates concurrent analysis components
│   └── FakeDetector.kt      # Core evidence-fusion and verdict logic
├── worker/ScanWorker.kt      # WorkManager-based background scanning logic
├── db/                       # Room database for cached scan results
├── ui/
│   ├── MainScreen.kt        # Library list with advanced filtering
│   └── DetailScreen.kt      # "Transparency Engine" breakdown and charts
└── viewmodel/ScanViewModel.kt # Reactive UI state management
```

## Getting Started

TrueHiFi is a modern Android project using **Jetpack Compose**, **KSP**, and **Kotlin Coroutines**.

1.  **Open** the project in Android Studio (Ladybug or newer).
2.  **Sync Gradle**: The project uses Version Catalogs and KSP for Room.
3.  **Run**: Deploy to a device running Android 8.0 (API 26) or higher.

### Manual Build / CI
A GitHub Actions workflow is included at `.github/workflows/build.yml` for automated APK generation.

## Technical Limitations

-   **APE Support**: Monkey's Audio requires a native decoder not currently present in the Android system. These files will currently show as "Unknown."
-   **LSB Audit Reliability**: The bit-depth padding check depends on the system decoder providing high-precision output. If the decoder truncates to 16-bit, the audit is automatically bypassed.
-   **Heuristic Nature**: While scientifically grounded, these results are heuristics. Always use the provided spectrum plots and confidence breakdown as a guide for your own judgment.
