package com.example.giffer2.core.ffmpeg

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import com.arthenica.ffmpegkit.FFmpegKitConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Centralizes one-time initialization for FFmpegKit so the native binaries from
 * `io.github.jamaismagic.ffmpeg:ffmpeg-kit-lts-16kb:6.0.1` are ready when the UI attempts to start a
 * conversion job. The initializer is idempotent and safe to call from the application process as
 * early as possible. **Do not** swap in any other FFmpeg distributionthe project is contractually
 * tied to this artifact and runtime assumptions (ABI splits, logging, licensing) rely on it.
 */
object FfmpegKitInitializer {
    private val initialized = AtomicBoolean(false)

    /**
     * Configures FFmpegKit logging and eagerly warms up the WorkManager runtime used by the
     * conversion pipeline. Subsequent calls become no-ops.
     */
    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) {
            return
        }

        val appContext = context.applicationContext
        // Touch WorkManager to ensure the runtime is bootstrapped before heavy work begins.
        WorkManager.getInstance(appContext)

        FFmpegKitConfig.enableLogCallback { logMessage ->
            logMessage.message?.let { message ->
                if (message.isNotBlank()) {
                    Log.d("FfmpegKitInitializer", message)
                }
            }
        }
    }

    /**
     * Ensures the FFmpegKit toolchain is initialized before a background worker executes. The
     * method is safe to call for every WorkManager invocation; initialization only runs once per
     * process.
     */
    fun ensureInitialized(context: Context) {
        if (!initialized.get()) {
            initialize(context)
        }
    }
}
