package com.example.gifvision.ffmpeg

import android.content.Context
import android.net.Uri
import androidx.work.Data
import androidx.work.WorkerParameters
import com.example.gifvision.BlendMode
import com.example.gifvision.GifWorkDataKeys
import com.example.gifvision.LogEntry
import com.example.gifvision.toJsonArray
import java.io.File
import java.util.Locale

/**
 * Combines the blended outputs from Layer 1 and Layer 2 into a master GIF composite.
 */
class MasterBlendWorker(
    appContext: Context,
    workerParameters: WorkerParameters
) : BaseBlendWorker<MasterBlendWorker.MasterBlendInputs>(appContext, workerParameters) {

    data class MasterBlendInputs(
        override val primaryUri: String,
        override val secondaryUri: String,
        override val blendMode: BlendMode,
        override val blendOpacity: Float,
        val masterLabel: String
    ) : BlendInputs {
        override val label: String = masterLabel.replace(" ", "_").lowercase(Locale.US)
    }

    override fun parseInputs(data: Data): MasterBlendInputs? {
        val primary = data.getString(GifWorkDataKeys.KEY_INPUT_PRIMARY_URI) ?: return null
        val secondary = data.getString(GifWorkDataKeys.KEY_INPUT_SECONDARY_URI) ?: return null
        val blendModeToken = data.getString(GifWorkDataKeys.KEY_INPUT_BLEND_MODE) ?: return null
        val blendMode = runCatching { BlendMode.valueOf(blendModeToken) }.getOrNull() ?: return null
        val opacity = data.getFloat(GifWorkDataKeys.KEY_INPUT_BLEND_OPACITY, Float.NaN)
        if (opacity.isNaN()) {
            return null
        }
        val label = data.getString(GifWorkDataKeys.KEY_INPUT_MASTER_LABEL) ?: "master"
        return MasterBlendInputs(primary, secondary, blendMode, opacity, label)
    }

    override fun onBlendSuccess(
        inputs: MasterBlendInputs,
        outputFile: File,
        exitCode: Int?,
        stderrTail: List<String>,
        logs: List<LogEntry>
    ): Result {
        val data = Data.Builder()
            .putString(GifWorkDataKeys.KEY_OUTPUT_GIF_URI, Uri.fromFile(outputFile).toString())
            .putString(GifWorkDataKeys.KEY_INPUT_MASTER_LABEL, inputs.masterLabel)
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
        inputs: MasterBlendInputs?,
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
            data.putString(GifWorkDataKeys.KEY_INPUT_MASTER_LABEL, it.masterLabel)
            data.putString(GifWorkDataKeys.KEY_INPUT_PRIMARY_URI, it.primaryUri)
            data.putString(GifWorkDataKeys.KEY_INPUT_SECONDARY_URI, it.secondaryUri)
            data.putString(GifWorkDataKeys.KEY_INPUT_BLEND_MODE, it.blendMode.name)
            data.putFloat(GifWorkDataKeys.KEY_INPUT_BLEND_OPACITY, it.blendOpacity)
        }
        return Result.failure(data.build())
    }
}
