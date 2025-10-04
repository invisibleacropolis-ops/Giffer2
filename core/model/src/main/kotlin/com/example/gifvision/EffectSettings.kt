package com.example.gifvision

/** Metadata extracted from a source clip used to seed effect defaults. */
data class ClipMetadata(
    val width: Int?,
    val height: Int?,
    val durationMillis: Long?,
    val frameRate: Double?
) {
    val aspectRatio: Float? = if (width != null && height != null && height != 0) {
        width.toFloat() / height.toFloat()
    } else {
        null
    }
}

/** Range of the source clip to render. */
data class ClipTrim(
    val startMs: Long = 0,
    val endMs: Long? = null
) {
    init {
        require(startMs >= 0) { "Clip trim start must be non-negative." }
        require(endMs == null || endMs >= startMs) { "Clip trim end must be >= start." }
    }

    val durationMs: Long?
        get() = endMs?.let { it - startMs }

    fun withDuration(durationMs: Long?): ClipTrim = copy(endMs = durationMs?.let { startMs + it })

    companion object {
        val FULL = ClipTrim()
    }
}

/** Configuration for text overlays applied to a stream. */
data class TextOverlay(
    val text: String = "",
    val fontSizeSp: Int = DEFAULT_FONT_SIZE_SP,
    val colorArgb: Int = DEFAULT_COLOR,
    val enabled: Boolean = false
) {
    companion object {
        const val DEFAULT_FONT_SIZE_SP = 18
        const val DEFAULT_COLOR: Int = 0xFFFFFFFF.toInt()
    }
}

/** Color balance multipliers applied to the stream. */
data class RgbBalance(
    val red: Float = 1f,
    val green: Float = 1f,
    val blue: Float = 1f
) {
    fun clamp(): RgbBalance = copy(
        red = red.coerceAtLeast(0f),
        green = green.coerceAtLeast(0f),
        blue = blue.coerceAtLeast(0f)
    )
}

/**
 * Aggregates every user-controlled adjustment surfaced in the GifVision manual.
 */
data class EffectSettings(
    val streamId: StreamId,
    val resolutionPercent: Int,
    val maxColors: Int,
    val frameRate: Double,
    val clipTrim: ClipTrim,
    val textOverlay: TextOverlay,
    val brightness: Float,
    val contrast: Float,
    val saturation: Float,
    val hue: Float,
    val sepia: Boolean,
    val rgbBalance: RgbBalance,
    val chromaWarp: Boolean,
    val colorCycleSpeed: Float,
    val motionTrails: Boolean,
    val sharpen: Boolean,
    val edgeDetect: Boolean,
    val negate: Boolean,
    val flipHorizontal: Boolean,
    val flipVertical: Boolean
) {
    fun resetForNewSource(metadata: ClipMetadata): EffectSettings {
        val defaults = defaultSettings(streamId)
        val duration = metadata.durationMillis
        val frameRateGuess = metadata.frameRate ?: defaults.frameRate
        return defaults.copy(
            frameRate = frameRateGuess,
            clipTrim = ClipTrim(startMs = 0, endMs = duration),
            resolutionPercent = Companion.computeResolutionPercent(metadata) ?: defaults.resolutionPercent
        )
    }

    fun toWorkData(): androidx.work.Data {
        val builder = androidx.work.Data.Builder()
            .putInt(KEY_STREAM, streamId.toWorkValue())
            .putInt(KEY_RESOLUTION_PERCENT, resolutionPercent)
            .putInt(KEY_MAX_COLORS, maxColors)
            .putDouble(KEY_FRAME_RATE, frameRate)
            .putLong(KEY_CLIP_START_MS, clipTrim.startMs)
            .putBoolean(KEY_TEXT_ENABLED, textOverlay.enabled)
            .putString(KEY_TEXT_VALUE, textOverlay.text)
            .putInt(KEY_TEXT_FONT_SIZE, textOverlay.fontSizeSp)
            .putInt(KEY_TEXT_COLOR, textOverlay.colorArgb)
            .putFloat(KEY_BRIGHTNESS, brightness)
            .putFloat(KEY_CONTRAST, contrast)
            .putFloat(KEY_SATURATION, saturation)
            .putFloat(KEY_HUE, hue)
            .putBoolean(KEY_SEPIA, sepia)
            .putFloat(KEY_RGB_RED, rgbBalance.red)
            .putFloat(KEY_RGB_GREEN, rgbBalance.green)
            .putFloat(KEY_RGB_BLUE, rgbBalance.blue)
            .putBoolean(KEY_CHROMA_WARP, chromaWarp)
            .putFloat(KEY_COLOR_CYCLE_SPEED, colorCycleSpeed)
            .putBoolean(KEY_MOTION_TRAILS, motionTrails)
            .putBoolean(KEY_SHARPEN, sharpen)
            .putBoolean(KEY_EDGE_DETECT, edgeDetect)
            .putBoolean(KEY_NEGATE, negate)
            .putBoolean(KEY_FLIP_HORIZONTAL, flipHorizontal)
            .putBoolean(KEY_FLIP_VERTICAL, flipVertical)

        clipTrim.endMs?.let { builder.putLong(KEY_CLIP_END_MS, it) }
        return builder.build()
    }

    companion object {
        private const val KEY_STREAM = "effect.stream"
        private const val KEY_RESOLUTION_PERCENT = "effect.resolution"
        private const val KEY_MAX_COLORS = "effect.maxColors"
        private const val KEY_FRAME_RATE = "effect.frameRate"
        private const val KEY_CLIP_START_MS = "effect.clip.start"
        private const val KEY_CLIP_END_MS = "effect.clip.end"
        private const val KEY_TEXT_ENABLED = "effect.text.enabled"
        private const val KEY_TEXT_VALUE = "effect.text.value"
        private const val KEY_TEXT_FONT_SIZE = "effect.text.fontSize"
        private const val KEY_TEXT_COLOR = "effect.text.color"
        private const val KEY_BRIGHTNESS = "effect.brightness"
        private const val KEY_CONTRAST = "effect.contrast"
        private const val KEY_SATURATION = "effect.saturation"
        private const val KEY_HUE = "effect.hue"
        private const val KEY_SEPIA = "effect.sepia"
        private const val KEY_RGB_RED = "effect.rgb.red"
        private const val KEY_RGB_GREEN = "effect.rgb.green"
        private const val KEY_RGB_BLUE = "effect.rgb.blue"
        private const val KEY_CHROMA_WARP = "effect.chromaWarp"
        private const val KEY_COLOR_CYCLE_SPEED = "effect.colorCycleSpeed"
        private const val KEY_MOTION_TRAILS = "effect.motionTrails"
        private const val KEY_SHARPEN = "effect.sharpen"
        private const val KEY_EDGE_DETECT = "effect.edgeDetect"
        private const val KEY_NEGATE = "effect.negate"
        private const val KEY_FLIP_HORIZONTAL = "effect.flipHorizontal"
        private const val KEY_FLIP_VERTICAL = "effect.flipVertical"

        internal val DEFAULT_FRAME_RATE = 12.0
        internal const val DEFAULT_MAX_COLORS = 256
        internal const val DEFAULT_RESOLUTION_PERCENT = 100

        fun defaultSettings(stream: StreamId): EffectSettings = EffectSettings(
            streamId = stream,
            resolutionPercent = DEFAULT_RESOLUTION_PERCENT,
            maxColors = DEFAULT_MAX_COLORS,
            frameRate = DEFAULT_FRAME_RATE,
            clipTrim = ClipTrim.FULL,
            textOverlay = TextOverlay(),
            brightness = 0f,
            contrast = 1f,
            saturation = 1f,
            hue = 0f,
            sepia = false,
            rgbBalance = RgbBalance(),
            chromaWarp = false,
            colorCycleSpeed = 0f,
            motionTrails = false,
            sharpen = false,
            edgeDetect = false,
            negate = false,
            flipHorizontal = false,
            flipVertical = false
        )

        fun fromWorkData(data: androidx.work.Data): EffectSettings {
            val defaultPacked = StreamId.of(LayerId.Layer1, StreamChannel.A).toWorkValue()
            val stream = StreamId.fromWorkValue(data.getInt(KEY_STREAM, defaultPacked))
            return defaultSettings(stream).copy(
                resolutionPercent = data.getInt(KEY_RESOLUTION_PERCENT, DEFAULT_RESOLUTION_PERCENT),
                maxColors = data.getInt(KEY_MAX_COLORS, DEFAULT_MAX_COLORS),
                frameRate = data.getDouble(KEY_FRAME_RATE, DEFAULT_FRAME_RATE),
                clipTrim = ClipTrim(
                    startMs = data.getLong(KEY_CLIP_START_MS, 0L),
                    endMs = data.getLong(KEY_CLIP_END_MS, -1L).takeIf { it >= 0 }
                ),
                textOverlay = TextOverlay(
                    text = data.getString(KEY_TEXT_VALUE).orEmpty(),
                    fontSizeSp = data.getInt(KEY_TEXT_FONT_SIZE, TextOverlay.DEFAULT_FONT_SIZE_SP),
                    colorArgb = data.getInt(KEY_TEXT_COLOR, TextOverlay.DEFAULT_COLOR),
                    enabled = data.getBoolean(KEY_TEXT_ENABLED, false)
                ),
                brightness = data.getFloat(KEY_BRIGHTNESS, 0f),
                contrast = data.getFloat(KEY_CONTRAST, 1f),
                saturation = data.getFloat(KEY_SATURATION, 1f),
                hue = data.getFloat(KEY_HUE, 0f),
                sepia = data.getBoolean(KEY_SEPIA, false),
                rgbBalance = RgbBalance(
                    red = data.getFloat(KEY_RGB_RED, 1f),
                    green = data.getFloat(KEY_RGB_GREEN, 1f),
                    blue = data.getFloat(KEY_RGB_BLUE, 1f)
                ),
                chromaWarp = data.getBoolean(KEY_CHROMA_WARP, false),
                colorCycleSpeed = data.getFloat(KEY_COLOR_CYCLE_SPEED, 0f),
                motionTrails = data.getBoolean(KEY_MOTION_TRAILS, false),
                sharpen = data.getBoolean(KEY_SHARPEN, false),
                edgeDetect = data.getBoolean(KEY_EDGE_DETECT, false),
                negate = data.getBoolean(KEY_NEGATE, false),
                flipHorizontal = data.getBoolean(KEY_FLIP_HORIZONTAL, false),
                flipVertical = data.getBoolean(KEY_FLIP_VERTICAL, false)
            )
        }

        internal fun computeResolutionPercent(metadata: ClipMetadata): Int? {
            val targetWidth = metadata.width ?: return null
            return when {
                targetWidth >= 1920 -> 50
                targetWidth >= 1280 -> 75
                else -> DEFAULT_RESOLUTION_PERCENT
            }
        }

    }
}

