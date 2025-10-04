package com.example.giffer2

import android.app.Application
import com.example.giffer2.core.ffmpeg.FfmpegKitInitializer

/**
 * Application entry point responsible for preparing shared native resources before any Compose
 * UI attempts to trigger conversions.
 */
class GifVisionApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FfmpegKitInitializer.initialize(applicationContext)
    }
}
