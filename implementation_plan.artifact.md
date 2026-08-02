# Performance and Stability Fix for Library Scanning

Address UI lag, main thread blocking, and scan stalls by optimizing the scanning worker, view model flows, and UI state management.

## User Review Required

> [!IMPORTANT]
> The scanning progress updates are now throttled to 100ms and result list updates in `ScanRepository` are removed to avoid redundant state flow saturation. The UI will now rely solely on the database for result list updates, which is the source of truth.

## Proposed Changes

### [Audio Analysis Layer]

#### [MODIFY] [AudioDecoder.kt](file:///C:/Users/hp/Downloads/FakeHiFiDetector/app/src/main/java/com/fakehifi/detector/analysis/AudioDecoder.kt)
- Make `decodeSampleWindows` and `decodeOneWindow` suspend functions.
- Add `currentCoroutineContext().ensureActive()` in decoding loops to ensure responsive cancellation.

### [Background Worker Layer]

#### [MODIFY] [ScanWorker.kt](file:///C:/Users/hp/Downloads/FakeHiFiDetector/app/src/main/java/com/fakehifi/detector/worker/ScanWorker.kt)
- Add `ensureActive()` in the main track loop.
- Stop pushing the entire result list to `ScanRepository` during full scans; the UI now observes the database for results.
- Throttle `currentTitle` and progress updates to the repository to avoid flooding the `StateFlow`.

### [View Model & State Layer]

#### [MODIFY] [ScanViewModel.kt](file:///C:/Users/hp/Downloads/FakeHiFiDetector/app/src/main/java/com/fakehifi/detector/viewmodel/ScanViewModel.kt)
- Re-architect `uiState` flow to perform expensive list mapping and filtering on `Dispatchers.Default`.
- Separate scanning progress from result list management to minimize redundant computations.

### [UI Layer]

#### [MODIFY] [MainScreen.kt](file:///C:/Users/hp/Downloads/FakeHiFiDetector/app/src/main/java/com/fakehifi/detector/MainActivity.kt)
- Use `remember` and `derivedStateOf` for `visibleResults` to prevent heavy filtering on every recomposition.

## Verification Plan

### Automated Tests
- Run `:app:compileDebugKotlin` to ensure no regression in types.

### Manual Verification
- Start a full scan of a large library (>500 tracks).
- Verify UI remains responsive (scrolling, clicking) during active scan.
- Verify scan can be cancelled instantly.
- Verify search and filter work smoothly during scan.
