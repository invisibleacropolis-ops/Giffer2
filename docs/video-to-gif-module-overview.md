# Video-to-GIF Workflow Overview

The application logic lives in `MainActivity.kt` and is implemented with Jetpack Compose and FFmpegKit (distributed exclusively via `io.github.jamaismagic.ffmpeg:ffmpeg-kit-lts-16kb:6.0.1`). This document summarizes the main responsibilities of each composable and helper routine to help new engineers navigate the codebase quickly.



> **Important:** The GifVision pipeline must never depend on any FFmpeg build other than `io.github.jamaismagic.ffmpeg:ffmpeg-kit-lts-16kb:6.0.1`. Using alternative artifacts can break native initialization, licensing audits, and binary compatibility.

## UI Layer (Jetpack Compose)

- **`VideoToGifApp`** orchestrates the user flow: video selection, conversion trigger, GIF preview, and save-to-gallery action. It also owns the state for log visibility, progress indication, and error messaging. The composable wires up an `ActivityResultLauncher` that clears previous state each time a new video is selected, ensuring stale status messages are not shown to the user.【F:app/src/main/java/com/example/giffer2/MainActivity.kt†L81-L137】【F:app/src/main/java/com/example/giffer2/MainActivity.kt†L140-L209】
- **`VideoPreview`** hosts a traditional `VideoView` inside Compose via `AndroidView` to provide playback controls. It guards against repeated error reporting, loops the video for continuous preview, and gracefully handles URI changes by resetting playback listeners.【F:app/src/main/java/com/example/giffer2/MainActivity.kt†L211-L315】
- **`GifPreview`** uses Coil with GIF support to render the generated file, ensuring the preview is clipped and scaled appropriately inside a square aspect ratio container.【F:app/src/main/java/com/example/giffer2/MainActivity.kt†L317-L337】

## Conversion Pipeline

- **`convertVideoToGif`** runs on a background `CoroutineScope` tied to a `SupervisorJob` and the IO dispatcher. It validates the input using `extractVideoMetadata`, caches the video locally, and builds a deterministic FFmpeg command (`fps=10`, Lanczos scaling to width 320). Logs and callbacks are marshalled back to the main thread with a `Handler`, so UI state updates remain thread-safe.【F:app/src/main/java/com/example/giffer2/MainActivity.kt†L339-L448】【F:app/src/main/java/com/example/giffer2/MainActivity.kt†L454-L517】
- **`createTempFileFromUri`** copies the selected video into the app's cache directory with a sanitized extension and explicit `fsync` attempt. It validates that the cached file exists and has non-zero length before proceeding, surfacing descriptive IO errors to the logs if anything fails.【F:app/src/main/java/com/example/giffer2/MainActivity.kt†L519-L560】

## Persistence Utilities

- **`saveGifToGallery`** writes the generated GIF into MediaStore, supporting both pre- and post-Android Q semantics. It marks pending entries on modern Android versions, updates the row to clear the pending flag when the copy completes, and returns the resulting `Uri` to the caller.【F:app/src/main/java/com/example/giffer2/MainActivity.kt†L384-L436】
- **`getFileName`, `getFileExtensionFromUri`** and other metadata helpers centralize querying `ContentResolver` for user-friendly details. This avoids scattering cursor-handling logic throughout the UI layer.【F:app/src/main/java/com/example/giffer2/MainActivity.kt†L438-L517】【F:app/src/main/java/com/example/giffer2/MainActivity.kt†L562-L609】

Understanding these building blocks should make it easier to extend the pipeline—for example, to expose frame-rate controls, alternative scaling profiles, or richer error reporting.
