package com.example.gifvision.ffmpeg

import android.content.Context
import android.net.Uri
import androidx.work.Data
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.example.gifvision.GifTranscodeBlueprint
import com.example.gifvision.GifReference
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
 * Executes the two-pass GIF pipeline (palette generation + GIF render) for a single stream.
 * The worker normalizes FFmpeg stdout/stderr into [LogEntry] payloads so the UI can render
 * diagnostics inline with progress updates.
 */
class GifGenerationWorker(
    appContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(appContext, workerParameters) {

    private val commandRunner = FfmpegKitCommandRunner()
    private val filterGraphBuilder = FilterGraphBuilder()

    override suspend fun doWork(): Result {
        FfmpegKitInitializer.ensureInitialized(applicationContext)

        val logs = mutableListOf<LogEntry>()
        val blueprint = runCatching { GifTranscodeBlueprint.fromWorkData(inputData) }
            .getOrElse { error ->
                logs += LogEntry(
                    message = "Failed to parse blueprint: ${error.message}",
                    severity = LogSeverity.ERROR,
                    workId = id
                )
                return failureResult(
                    reason = GifWorkDataKeys.FAILURE_REASON_INVALID_INPUT,
                    detail = error.message,
                    exitCode = null,
                    stderrTail = emptyList(),
                    logs = logs
                )
            }

        var resolvedSource: ResolvedSource? = null
        var paletteFile: File? = null
        var outputFile: File? = null

        return try {
            resolvedSource = resolveSource(blueprint)
            val graphs = filterGraphBuilder.buildStreamGraphs(blueprint)
            paletteFile = File.createTempFile(
                "palette_${blueprint.streamId.layer.index}_${blueprint.streamId.channel.name.lowercase(Locale.US)}",
                ".png",
                applicationContext.cacheDir
            )
            outputFile = File.createTempFile(
                "gif_${blueprint.streamId.layer.index}_${blueprint.streamId.channel.name.lowercase(Locale.US)}",
                ".gif",
                applicationContext.cacheDir
            )

            val paletteResult = executeCommand(
                command = buildPaletteCommand(resolvedSource.file, paletteFile, graphs),
                stderrTail = ArrayDeque(),
                logs = logs
            )
            if (!paletteResult.isSuccess) {
                logs += LogEntry(
                    message = "Palette generation failed with exit code ${paletteResult.exitCode ?: -1}",
                    severity = LogSeverity.ERROR,
                    workId = id
                )
                return failureResult(
                    reason = GifWorkDataKeys.FAILURE_REASON_FFMPEG_ERROR,
                    detail = "palette_generation_failed",
                    exitCode = paletteResult.exitCode,
                    stderrTail = paletteResult.stderrTail,
                    logs = logs
                )
            }

            val renderResult = executeCommand(
                command = buildRenderCommand(resolvedSource.file, paletteFile, outputFile, graphs),
                stderrTail = ArrayDeque(),
                logs = logs
            )
            if (!renderResult.isSuccess) {
                logs += LogEntry(
                    message = "GIF rendering failed with exit code ${renderResult.exitCode ?: -1}",
                    severity = LogSeverity.ERROR,
                    workId = id
                )
                return failureResult(
                    reason = GifWorkDataKeys.FAILURE_REASON_FFMPEG_ERROR,
                    detail = "gif_render_failed",
                    exitCode = renderResult.exitCode,
                    stderrTail = renderResult.stderrTail,
                    logs = logs
                )
            }

            successResult(outputFile, renderResult.exitCode, renderResult.stderrTail, logs)
        } catch (throwable: Throwable) {
            logs += LogEntry(
                message = "GifGenerationWorker failed: ${throwable.message ?: throwable::class.java.simpleName}",
                severity = LogSeverity.ERROR,
                workId = id
            )
            failureResult(
                reason = GifWorkDataKeys.FAILURE_REASON_EXCEPTION,
                detail = throwable.message,
                exitCode = null,
                stderrTail = emptyList(),
                logs = logs
            )
        } finally {
            paletteFile?.delete()
            if (resolvedSource?.deleteOnCleanup == true) {
                resolvedSource.file.delete()
            }
        }
    }

    private fun successResult(
        outputFile: File,
        exitCode: Int?,
        stderrTail: List<String>,
        logs: List<LogEntry>
    ): Result {
        val data = Data.Builder()
            .putString(GifWorkDataKeys.KEY_OUTPUT_GIF_URI, Uri.fromFile(outputFile).toString())
        exitCode?.let { data.putInt(GifWorkDataKeys.KEY_OUTPUT_EXIT_CODE, it) }
        if (stderrTail.isNotEmpty()) {
            data.putStringArray(GifWorkDataKeys.KEY_OUTPUT_STDERR_TAIL, stderrTail.toTypedArray())
        }
        if (logs.isNotEmpty()) {
            data.putStringArray(GifWorkDataKeys.KEY_OUTPUT_LOGS, logs.toJsonArray())
        }
        return Result.success(data.build())
    }

    private fun failureResult(
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
        return Result.failure(data.build())
    }

    private fun buildPaletteCommand(
        inputFile: File,
        paletteFile: File,
        graphs: FilterGraphBuilder.StreamGraphs
    ): String {
        val args = listOf(
            "-y",
            "-i",
            inputFile.absolutePath,
            "-vf",
            graphs.paletteGraph,
            "-frames:v",
            "1",
            paletteFile.absolutePath
        )
        return joinArguments(args)
    }

    private fun buildRenderCommand(
        inputFile: File,
        paletteFile: File,
        outputFile: File,
        graphs: FilterGraphBuilder.StreamGraphs
    ): String {
        val renderFilters = graphs.renderFilters
        require(renderFilters.isNotEmpty()) { "Render filter chain cannot be empty" }
        val baseFilters = renderFilters.dropLast(1).joinToString(",")
        val paletteUse = renderFilters.last()
        val filterComplex = if (baseFilters.isEmpty()) {
            "[0:v][1:v]$paletteUse"
        } else {
            "[0:v]$baseFilters[base];[base][1:v]$paletteUse"
        }
        val args = listOf(
            "-y",
            "-i",
            inputFile.absolutePath,
            "-i",
            paletteFile.absolutePath,
            "-filter_complex",
            filterComplex,
            "-loop",
            "0",
            "-gifflags",
            "+transdiff",
            outputFile.absolutePath
        )
        return joinArguments(args)
    }

    private suspend fun executeCommand(
        command: String,
        stderrTail: ArrayDeque<String>,
        logs: MutableList<LogEntry>
    ): CommandResult = suspendCancellableCoroutine { continuation ->
        stderrTail.clear()
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

    private fun resolveSource(blueprint: GifTranscodeBlueprint): ResolvedSource {
        val source = blueprint.source
        val label = "${blueprint.streamId.layer.displayName}_${blueprint.streamId.channel.displayName}".replace(" ", "_")
        return when (source) {
            is GifReference.FileUri -> {
                val uri = Uri.parse(source.uri)
                if (uri.scheme == null || uri.scheme == "file") {
                    val file = File(uri.path ?: source.uri)
                    require(file.exists()) { "Input file does not exist: ${file.absolutePath}" }
                    ResolvedSource(file, deleteOnCleanup = false)
                } else {
                    copyUriToCache(uri, label)
                }
            }

            is GifReference.ContentUri -> copyUriToCache(Uri.parse(source.uri), label)

            is GifReference.InMemory -> {
                val temp = File.createTempFile("source_${label}", ".gif", applicationContext.cacheDir)
                FileOutputStream(temp).use { output ->
                    output.write(source.bytes)
                }
                ResolvedSource(temp, deleteOnCleanup = true)
            }
        }
    }

    private fun copyUriToCache(uri: Uri, label: String): ResolvedSource {
        val suffix = uri.lastPathSegment?.substringAfterLast('.', missingDelimiterValue = "")?.takeIf { it.isNotEmpty() }
            ?.let { ".${it}" } ?: ".gif"
        val temp = File.createTempFile("import_${label}", suffix, applicationContext.cacheDir)
        val resolver = applicationContext.contentResolver
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(temp).use { output ->
                input.copyTo(output)
            }
        } ?: error("Unable to open content URI: $uri")
        return ResolvedSource(temp, deleteOnCleanup = true)
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

    private data class ResolvedSource(val file: File, val deleteOnCleanup: Boolean)

    private data class CommandResult(val session: FFmpegSession, val stderrTail: List<String>) {
        val exitCode: Int?
            get() = session.returnCode?.value
        val isSuccess: Boolean
            get() = ReturnCode.isSuccess(session.returnCode)
    }

    companion object {
        private const val STDERR_TAIL_LIMIT = 20
    }
}
