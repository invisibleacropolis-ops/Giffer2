package com.example.gifvision

/**
 * Central registry for WorkManager input/output keys shared by GifVision workers.
 * Keeping the constants in one place ensures the UI and background layers stay in sync
 * and avoids subtle typos when building Data payloads.
 */
object GifWorkDataKeys {
    // Generic input keys
    const val KEY_INPUT_PRIMARY_URI: String = "gifvision.input.primaryUri"
    const val KEY_INPUT_SECONDARY_URI: String = "gifvision.input.secondaryUri"
    const val KEY_INPUT_BLEND_MODE: String = "gifvision.input.blendMode"
    const val KEY_INPUT_BLEND_OPACITY: String = "gifvision.input.blendOpacity"
    const val KEY_INPUT_LAYER_INDEX: String = "gifvision.input.layerIndex"
    const val KEY_INPUT_STREAM_ID: String = "gifvision.input.streamId"
    const val KEY_INPUT_MASTER_LABEL: String = "gifvision.input.masterLabel"

    // Generic output keys
    const val KEY_OUTPUT_GIF_URI: String = "gifvision.output.gifUri"
    const val KEY_OUTPUT_LOGS: String = "gifvision.output.logs"
    const val KEY_OUTPUT_STDERR_TAIL: String = "gifvision.output.stderrTail"
    const val KEY_OUTPUT_EXIT_CODE: String = "gifvision.output.exitCode"

    // Failure specific keys
    const val KEY_FAILURE_REASON: String = "gifvision.failure.reason"
    const val KEY_FAILURE_DETAIL: String = "gifvision.failure.detail"

    // Standardized failure reasons to keep analytics consistent.
    const val FAILURE_REASON_INVALID_INPUT: String = "invalid_input"
    const val FAILURE_REASON_FFMPEG_ERROR: String = "ffmpeg_error"
    const val FAILURE_REASON_EXCEPTION: String = "uncaught_exception"
}
