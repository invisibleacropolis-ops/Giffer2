package com.example.gifvision.ffmpeg

import android.content.Context
import android.net.Uri
import androidx.work.Data
import androidx.work.WorkerParameters
import com.example.gifvision.BlendMode
import com.example.gifvision.GifWorkDataKeys
import com.example.gifvision.LogEntry
import com.example.gifvision.StreamId
import com.example.gifvision.toJsonArray
import java.io.File
import java.util.Locale

/**
 * Blends Stream A and Stream B outputs for a single layer, returning the resulting GIF URI.
 */
class GifBlendWorker(
    appContext: Context,
    workerParameters: WorkerParameters
) : BaseBlendWorker<GifBlendWorker.LayerBlendInputs>(appContext, workerParameters) {

    data class LayerBlendInputs(
        override val primaryUri: String,
        override val secondaryUri: String,
        override val blendMode: BlendMode,
        override val blendOpacity: Float,
        val streamId: StreamId
    ) : BlendInputs {
        override val label: String =
            "${streamId.layer.displayName}_${streamId.channel.displayName}".replace(" ", "_").lowercase(Locale.US)
    }

    override fun parseInputs(data: Data): LayerBlendInputs? {
        val primary = data.getString(GifWorkDataKeys.KEY_INPUT_PRIMARY_URI) ?: return null
        val secondary = data.getString(GifWorkDataKeys.KEY_INPUT_SECONDARY_URI) ?: return null
        val blendModeToken = data.getString(GifWorkDataKeys.KEY_INPUT_BLEND_MODE) ?: return null
        val blendMode = runCatching { BlendMode.valueOf(blendModeToken) }.getOrNull() ?: return null
        val opacity = data.getFloat(GifWorkDataKeys.KEY_INPUT_BLEND_OPACITY, Float.NaN)
        if (opacity.isNaN()) {
            return null
        }
        val streamValue = data.getInt(GifWorkDataKeys.KEY_INPUT_STREAM_ID, Int.MIN_VALUE)
        if (streamValue == Int.MIN_VALUE) {
            return null
        }
        val streamId = runCatching { StreamId.fromWorkValue(streamValue) }.getOrNull() ?: return null
        return LayerBlendInputs(primary, secondary, blendMode, opacity, streamId)
    }

    override fun onBlendSuccess(
        inputs: LayerBlendInputs,
        outputFile: File,
        exitCode: Int?,
        stderrTail: List<String>,
        logs: List<LogEntry>
    ): Result {
        val data = Data.Builder()
            .putString(GifWorkDataKeys.KEY_OUTPUT_GIF_URI, Uri.fromFile(outputFile).toString())
            .putInt(GifWorkDataKeys.KEY_INPUT_STREAM_ID, inputs.streamId.toWorkValue())
            .putString(GifWorkDataKeys.KEY_INPUT_BLEND_MODE, inputs.blendMode.name)
            .putFloat(GifWorkDataKeys.KEY_INPUT_BLEND_OPACITY, inputs.blendOpacity)
        exitCode?.let { data.putInt(GifWorkDataKeys.KEY_OUTPUT_EXIT_CODE, it) }
        if (stderrTail.isNotEmpty()) {
            data.putStringArray(GifWorkDataKeys.KEY_OUTPUT_STDERR_TAIL, stderrTail.toTypedArray())
        }
        if (logs.isNotEmpty()) {
            data.putStringArray(GifWorkDataKeys.KEY_OUTPUT_LOGS, logs.toJsonArray())
        }
        return Result.success(data.build())
    }

    override fun onBlendFailure(
        inputs: LayerBlendInputs?,
        reason: String,
        detail: String?,
        exitCode: Int?,
        stderrTail: List<String>,
        logs: List<LogEntry>
    ): Result {
        val data = Data.Builder()
            .putString(GifWorkDataKeys.KEY_FAILURE_REASON, reason)
        detail?.let { data.putString(GifWorkDataKeys.KEY_FAILURE_DETAIL, it) }
        exitCode?.let { data.putInt(GifWorkDataKeys.KEY_OUTPUT_EXIT_CODE, it) }
        if (stderrTail.isNotEmpty()) {
            data.putStringArray(GifWorkDataKeys.KEY_OUTPUT_STDERR_TAIL, stderrTail.toTypedArray())
        }
        if (logs.isNotEmpty()) {
            data.putStringArray(GifWorkDataKeys.KEY_OUTPUT_LOGS, logs.toJsonArray())
        }
        inputs?.let {
            data.putInt(GifWorkDataKeys.KEY_INPUT_STREAM_ID, it.streamId.toWorkValue())
            data.putString(GifWorkDataKeys.KEY_INPUT_PRIMARY_URI, it.primaryUri)
            data.putString(GifWorkDataKeys.KEY_INPUT_SECONDARY_URI, it.secondaryUri)
            data.putString(GifWorkDataKeys.KEY_INPUT_BLEND_MODE, it.blendMode.name)
            data.putFloat(GifWorkDataKeys.KEY_INPUT_BLEND_OPACITY, it.blendOpacity)
        }
        return Result.failure(data.build())
    }
}
