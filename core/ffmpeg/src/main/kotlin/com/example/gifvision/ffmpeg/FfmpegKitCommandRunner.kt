package com.example.gifvision.ffmpeg

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.Level
import com.arthenica.ffmpegkit.Log
import com.arthenica.ffmpegkit.Statistics
import java.util.Locale

/**
 * Thin wrapper around [FFmpegKit.executeAsync] that exposes typed callbacks for the
 * GifVision worker layer. The backing implementation is **always** provided by
 * `io.github.jamaismagic.ffmpeg:ffmpeg-kit-lts-16kb:6.0.1`; introducing other FFmpeg bridges is
 * unsupported because initialization, logging, and licensing policies are tuned to that artifact.
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
            complete = { session ->
                callbacks.onComplete(session)
            },
            log = { log ->
                handleLog(log, callbacks)
            },
            statistics = { statistics ->
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
            complete: (FFmpegSession) -> Unit,
            log: (Log?) -> Unit,
            statistics: (Statistics) -> Unit
        ): FFmpegSession

        object Default : FfmpegKitAdapter {
            override fun executeAsync(
                command: String,
                complete: (FFmpegSession) -> Unit,
                log: (Log?) -> Unit,
                statistics: (Statistics) -> Unit
            ): FFmpegSession {
                return FFmpegKit.executeAsync(
                    command,
                    { session -> complete(session) },
                    { entry: Log? -> log(entry) },
                    { stats -> statistics(stats) }
                )
            }
        }
    }
}
