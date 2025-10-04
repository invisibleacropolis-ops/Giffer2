package com.example.giffer2.core.ffmpeg

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import com.arthenica.ffmpegkit.FFmpegKitConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Centralizes one-time initialization for FFmpegKit so the native binaries are ready when the
 * UI attempts to start a conversion job. The initializer is idempotent and safe to call from the
 * application process as early as possible.
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
}
