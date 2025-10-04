package com.example.gifvision.ffmpeg

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.example.gifvision.BlendMode
import com.example.gifvision.GifWorkDataKeys
import com.example.gifvision.LogEntry
import com.example.gifvision.LogSeverity
import com.example.gifvision.toJsonArray
import com.example.giffer2.core.ffmpeg.FfmpegKitInitializer
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Shared execution harness for blend workers. Subclasses only implement request parsing and
 * success payload assembly while this base class handles resolution, padding, FFmpeg invocation,
 * and standardized logging/failure data.
 */
abstract class BaseBlendWorker<I : BaseBlendWorker.BlendInputs>(
    appContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(appContext, workerParameters) {

    interface BlendInputs {
        val primaryUri: String
        val secondaryUri: String
        val blendMode: BlendMode
        val blendOpacity: Float
        val label: String
    }

    private val commandRunner = FfmpegKitCommandRunner()
    private val filterGraphBuilder = FilterGraphBuilder()

    final override suspend fun doWork(): Result {
        FfmpegKitInitializer.ensureInitialized(applicationContext)

        val logs = mutableListOf<LogEntry>()
        val inputs = parseInputs(inputData)
        if (inputs == null) {
            logs += LogEntry(
                message = "Blend worker received invalid input payload",
                severity = LogSeverity.ERROR,
                workId = id
            )
            return onBlendFailure(
                inputs = null,
                reason = GifWorkDataKeys.FAILURE_REASON_INVALID_INPUT,
                detail = "missing_inputs",
                exitCode = null,
                stderrTail = emptyList(),
                logs = logs
            )
        }

        val primary = runCatching { resolveInput(inputs.primaryUri, "primary_${inputs.label}") }
            .getOrElse { error ->
                logs += LogEntry(
                    message = "Unable to resolve primary input: ${error.message}",
                    severity = LogSeverity.ERROR,
                    workId = id
                )
                return onBlendFailure(
                    inputs = inputs,
                    reason = GifWorkDataKeys.FAILURE_REASON_INVALID_INPUT,
                    detail = "primary_unavailable",
                    exitCode = null,
                    stderrTail = emptyList(),
                    logs = logs
                )
            }

        val secondary = runCatching { resolveInput(inputs.secondaryUri, "secondary_${inputs.label}") }
            .getOrElse { error ->
                logs += LogEntry(
                    message = "Unable to resolve secondary input: ${error.message}",
                    severity = LogSeverity.ERROR,
                    workId = id
                )
                cleanup(primary)
                return onBlendFailure(
                    inputs = inputs,
                    reason = GifWorkDataKeys.FAILURE_REASON_INVALID_INPUT,
                    detail = "secondary_unavailable",
                    exitCode = null,
                    stderrTail = emptyList(),
                    logs = logs
                )
            }

        val outputFile = createOutputFile(inputs)

        return try {
            val filterComplex = buildBlendFilter(inputs)
            val command = buildBlendCommand(primary.file, secondary.file, outputFile, filterComplex)
            val result = executeCommand(command, logs)
            if (result.isSuccess) {
                onBlendSuccess(inputs, outputFile, result.exitCode, result.stderrTail, logs)
            } else {
                logs += LogEntry(
                    message = "Blend command failed with exit code ${result.exitCode ?: -1}",
                    severity = LogSeverity.ERROR,
                    workId = id
                )
                onBlendFailure(
                    inputs = inputs,
                    reason = GifWorkDataKeys.FAILURE_REASON_FFMPEG_ERROR,
                    detail = "blend_failed",
                    exitCode = result.exitCode,
                    stderrTail = result.stderrTail,
                    logs = logs
                )
            }
        } catch (throwable: Throwable) {
            logs += LogEntry(
                message = "Blend worker crashed: ${throwable.message ?: throwable::class.java.simpleName}",
                severity = LogSeverity.ERROR,
                workId = id
            )
            onBlendFailure(
                inputs = inputs,
                reason = GifWorkDataKeys.FAILURE_REASON_EXCEPTION,
                detail = throwable.message,
                exitCode = null,
                stderrTail = emptyList(),
                logs = logs
            )
        } finally {
            cleanup(primary)
            cleanup(secondary)
        }
    }

    protected abstract fun parseInputs(data: Data): I?

    protected open fun createOutputFile(inputs: I): File {
        return File.createTempFile("blend_${inputs.label}", ".gif", applicationContext.cacheDir)
    }

    protected open fun buildBlendFilter(inputs: I): String {
        val baseFilter = buildNormalizedScalePadFilter()
        val blendGraph = filterGraphBuilder.buildBlendGraph(inputs.blendMode, inputs.blendOpacity)
            .replace("[0:v]", "[base]")
            .replace("[1:v]", "[overlay]")
        return buildString {
            append("[0:v]")
            append(baseFilter)
            append("[base];")
            append("[1:v]")
            append(baseFilter)
            append("[overlay];")
            append(blendGraph)
        }
    }

    protected abstract fun onBlendSuccess(
        inputs: I,
        outputFile: File,
        exitCode: Int?,
        stderrTail: List<String>,
        logs: List<LogEntry>
    ): Result

    protected open fun onBlendFailure(
        inputs: I?,
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
            data.putString(GifWorkDataKeys.KEY_INPUT_PRIMARY_URI, it.primaryUri)
            data.putString(GifWorkDataKeys.KEY_INPUT_SECONDARY_URI, it.secondaryUri)
        }
        return Result.failure(data.build())
    }

    private suspend fun executeCommand(
        command: String,
        logs: MutableList<LogEntry>
    ): CommandResult = suspendCancellableCoroutine { continuation ->
        val stderrTail = ArrayDeque<String>()
        var sessionRef: FFmpegSession? = null
        val callbacks = FfmpegKitCommandRunner.Callbacks(
            onStdout = { line ->
                if (line.isNotBlank()) {
                    logs += LogEntry(line, LogSeverity.INFO, workId = id)
                }
            },
            onStderr = { line ->
                if (line.isNotBlank()) {
                    val severity = if (line.contains("warn", ignoreCase = true)) {
                        LogSeverity.WARNING
                    } else {
                        LogSeverity.ERROR
                    }
                    logs += LogEntry(line, severity, workId = id)
                    if (stderrTail.size >= STDERR_TAIL_LIMIT) {
                        stderrTail.removeFirst()
                    }
                    stderrTail.addLast(line)
                }
            },
            onComplete = { session ->
                sessionRef = session
                if (continuation.isActive) {
                    continuation.resume(CommandResult(session, stderrTail.toList()))
                }
            }
        )
        sessionRef = commandRunner.execute(command, callbacks)
        continuation.invokeOnCancellation {
            sessionRef?.let { FFmpegKit.cancel(it.sessionId) }
        }
    }

    private fun buildBlendCommand(
        primary: File,
        secondary: File,
        output: File,
        filterComplex: String
    ): String {
        val args = listOf(
            "-y",
            "-i",
            primary.absolutePath,
            "-i",
            secondary.absolutePath,
            "-filter_complex",
            filterComplex,
            "-loop",
            "0",
            "-gifflags",
            "+transdiff",
            output.absolutePath
        )
        return joinArguments(args)
    }

    private fun resolveInput(rawUri: String, label: String): ResolvedInput {
        val uri = Uri.parse(rawUri)
        return when (uri.scheme) {
            null, "file" -> {
                val file = File(uri.path ?: rawUri)
                require(file.exists()) { "Input file does not exist: ${file.absolutePath}" }
                ResolvedInput(file, deleteOnCleanup = false)
            }

            "content" -> copyUriToCache(uri, label)

            else -> {
                val file = File(uri.path ?: rawUri)
                if (file.exists()) {
                    ResolvedInput(file, deleteOnCleanup = false)
                } else {
                    copyUriToCache(uri, label)
                }
            }
        }
    }

    private fun copyUriToCache(uri: Uri, label: String): ResolvedInput {
        val suffix = uri.lastPathSegment?.substringAfterLast('.', missingDelimiterValue = "")?.takeIf { it.isNotEmpty() }
            ?.let { ".${it}" } ?: ".gif"
        val temp = File.createTempFile(label.lowercase(Locale.US), suffix, applicationContext.cacheDir)
        val resolver = applicationContext.contentResolver
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(temp).use { output ->
                input.copyTo(output)
            }
        } ?: error("Unable to open URI: $uri")
        return ResolvedInput(temp, deleteOnCleanup = true)
    }

    private fun cleanup(resolved: ResolvedInput?) {
        if (resolved?.deleteOnCleanup == true) {
            resolved.file.delete()
        }
    }

    private fun joinArguments(arguments: List<String>): String {
        return arguments.joinToString(" ") { argument ->
            if (argument.isBlank()) {
                "\"\""
            } else if (argument.any { it.isWhitespace() }) {
                "\"${argument.replace("\"", "\\\"")}\""
            } else {
                argument
            }
        }.trim()
    }

    private fun buildNormalizedScalePadFilter(): String {
        return "scale=$TARGET_WIDTH:$TARGET_HEIGHT:force_original_aspect_ratio=decrease," +
            "pad=$TARGET_WIDTH:$TARGET_HEIGHT:(ow-iw)/2:(oh-ih)/2:black,format=rgba"
    }

    private data class ResolvedInput(val file: File, val deleteOnCleanup: Boolean)

    private data class CommandResult(val session: FFmpegSession, val stderrTail: List<String>) {
        val exitCode: Int?
            get() = session.returnCode?.value
        val isSuccess: Boolean
            get() = ReturnCode.isSuccess(session.returnCode)
    }

    companion object {
        private const val TARGET_WIDTH = 1280
        private const val TARGET_HEIGHT = 720
        private const val STDERR_TAIL_LIMIT = 20
    }
}
