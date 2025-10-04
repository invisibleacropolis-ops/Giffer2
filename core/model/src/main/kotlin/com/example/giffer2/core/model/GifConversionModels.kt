package com.example.giffer2.core.model

/**
 * Represents the overall lifecycle of a GIF conversion request so UI surfaces can present
 * meaningful progress to the user.
 */
sealed interface GifConversionState {
    /** No work has started yet. */
    data object Idle : GifConversionState

    /** FFmpeg is actively running. */
    data object Running : GifConversionState

    /** Conversion finished successfully and produced an output GIF. */
    data class Completed(val outputPath: String) : GifConversionState

    /** Conversion failed or was cancelled. */
    data class Failed(val errorMessage: String?) : GifConversionState
}

/**
 * Result payload emitted by the converter when work finishes. Either [outputPath] or
 * [errorMessage] is populated.
 */
data class GifConversionResult(
    val outputPath: String?,
    val errorMessage: String?
)

/**
 * Simple log entry emitted during conversion. Storing the timestamp makes it easier for external
 * diagnostics tooling to order events if needed.
 */
data class GifConversionLogEntry(
    val message: String,
    val timestampMillis: Long = System.currentTimeMillis()
)
