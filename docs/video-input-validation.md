# Video Input Validation and Preview Resilience

This document explains the key safeguards that protect the GIF conversion pipeline from
invalid or inaccessible video selections. It is intended to help engineers reason about
error scenarios and understand where to extend the workflow.

## Validation Flow Overview

1. **Preview guard rails** (`VideoPreview` in `MainActivity.kt`)
   - Wraps the legacy `VideoView` with defensive listeners.
   - Uses `onPlaybackError` to surface user-facing issues when the platform `MediaPlayer`
     cannot consume the provided `Uri`.
   - Prevents repeated error spam by short-circuiting duplicate callbacks and ensuring
     playback stops immediately when an exception occurs.

2. **Metadata verification** (`extractVideoMetadata`)
   - Executes before GIF conversion in `convertVideoToGif`.
   - Reads display name, size, duration, and dimensions through `MediaMetadataRetriever`.
   - Rejects videos that do not expose a positive duration, which is a reliable indicator
     that the source is malformed or inaccessible.

3. **Isolated caching** (`createTempFileFromUri`)
   - Copies the selected stream into the app cache from a background coroutine.
   - Forces a flush via `FileOutputStream.fd.sync()` (ignored on unsupported file systems)
     and validates byte counts before allowing FFmpeg to read from disk.
   - Returns a `Result<File>` to keep failure semantics explicit for callers.

4. **Structured logging**
   - Each major milestone is logged through `postLog`, providing a trace that mirrors the
     Android logcat excerpts supplied in the issue report.
   - Size and duration metadata is emitted to make it obvious when a zero-byte or truncated
     file sneaks through earlier layers.

## Extensibility Notes

- Additional validation (for example, MIME allow lists or maximum duration checks) should
  be implemented in `extractVideoMetadata` to centralise failures.
- When adding new FFmpeg variants, leverage the existing log sink to avoid regressing the
  UI-driven log viewer.
- The conversion scope lives in a shared `CoroutineScope` (`SupervisorJob` + `Dispatchers.IO`).
  If lifecycle-aware cancellation is introduced in the future, wire a `Job` from the host
  activity into `conversionScope` to keep background work scoped appropriately.

For further context see `MainActivity.kt`, particularly the `convertVideoToGif`,
`VideoPreview`, and helper functions around lines documented in the repository.
