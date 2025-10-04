package com.example.gifvision.ffmpeg

import com.arthenica.ffmpegkit.ExecuteCallback
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.Level
import com.arthenica.ffmpegkit.Log
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.Statistics
import com.arthenica.ffmpegkit.StatisticsCallback
import java.util.Locale

/**
 * Thin wrapper around [FFmpegKit.executeAsync] that exposes typed callbacks for the
 * GifVision worker layer.
 */
class FfmpegKitCommandRunner(
    private val adapter: FfmpegKitAdapter = FfmpegKitAdapter.Default
) {

    data class Callbacks(
        val onStdout: (String) -> Unit = {},
        val onStderr: (String) -> Unit = {},
        val onStatistics: (Statistics) -> Unit = {},
        val onComplete: (FFmpegSession) -> Unit = {}
    )

    fun execute(command: String, callbacks: Callbacks = Callbacks()): FFmpegSession {
        return adapter.executeAsync(
            command = command,
            complete = ExecuteCallback { session ->
                callbacks.onComplete(session)
            },
            log = LogCallback { log ->
                handleLog(log, callbacks)
            },
            statistics = StatisticsCallback { statistics ->
                callbacks.onStatistics(statistics)
            }
        )
    }

    private fun handleLog(log: Log?, callbacks: Callbacks) {
        val message = log?.message?.trimEnd()?.takeIf { it.isNotEmpty() } ?: return
        val level = log.level
        if (isStderrLevel(level)) {
            callbacks.onStderr(message)
        } else {
            callbacks.onStdout(message)
        }
    }

    private fun isStderrLevel(level: Level?): Boolean {
        if (level == null) {
            return false
        }
        return when (level) {
            Level.AV_LOG_PANIC,
            Level.AV_LOG_FATAL,
            Level.AV_LOG_ERROR,
            Level.AV_LOG_WARNING -> true
            else -> level.name.lowercase(Locale.US).contains("error") ||
                level.name.lowercase(Locale.US).contains("stderr")
        }
    }

    fun interface FfmpegKitAdapter {
        fun executeAsync(
            command: String,
            complete: ExecuteCallback,
            log: LogCallback,
            statistics: StatisticsCallback
        ): FFmpegSession

        object Default : FfmpegKitAdapter {
            override fun executeAsync(
                command: String,
                complete: ExecuteCallback,
                log: LogCallback,
                statistics: StatisticsCallback
            ): FFmpegSession {
                return FFmpegKit.executeAsync(command, complete, log, statistics)
            }
        }
    }
}
