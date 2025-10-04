package com.example.gifvision.ffmpeg

import com.example.gifvision.BlendMode
import com.example.gifvision.EffectSettings
import com.example.gifvision.GifTranscodeBlueprint
import com.example.gifvision.RgbBalance
import com.example.gifvision.TextOverlay
import java.util.Locale
import kotlin.math.abs

class FilterGraphBuilder {

    data class StreamGraphs(
        val baseFilters: List<String>,
        val paletteFilters: List<String>,
        val renderFilters: List<String>
    ) {
        val baseGraph: String = baseFilters.joinToString(",")
        val paletteGraph: String = paletteFilters.joinToString(",")
        val renderGraph: String = renderFilters.joinToString(",")
    }

    fun buildStreamGraphs(
        blueprint: GifTranscodeBlueprint,
        effectSettings: EffectSettings = blueprint.effectSettings
    ): StreamGraphs {
        val baseFilters = buildBaseFilters(effectSettings)
        val paletteFilters = baseFilters + buildPaletteFilter(effectSettings)
        val renderFilters = baseFilters + buildPaletteUseFilter()
        return StreamGraphs(baseFilters, paletteFilters, renderFilters)
    }

    fun buildBlendGraph(blendMode: BlendMode, opacity: Float): String {
        val clampedOpacity = opacity.coerceIn(0f, 1f)
        return "[0:v][1:v]blend=all_mode=${blendMode.ffmpegToken}:all_opacity=${formatDecimal(clampedOpacity.toDouble())},format=rgba"
    }

    private fun buildBaseFilters(effectSettings: EffectSettings): List<String> {
        val filters = mutableListOf<String>()
        buildTrimFilter(effectSettings)?.let(filters::add)
        filters.add("setpts=PTS-STARTPTS")
        filters.add("fps=${formatDecimal(effectSettings.frameRate)}")
        filters.add(buildScaleFilter(effectSettings.resolutionPercent))
        filters.add(buildEqFilter(effectSettings))
        buildColorBalanceFilter(effectSettings.rgbBalance)?.let(filters::add)
        buildHueFilter(effectSettings.hue)?.let(filters::add)
        if (effectSettings.sepia) {
            filters.add(SEPIA_FILTER)
        }
        if (effectSettings.chromaWarp) {
            filters.add("chromashift=cb=5:cr=-5")
        }
        if (!isApproximatelyZero(effectSettings.colorCycleSpeed)) {
            filters.add("hue='H+${formatDecimal(effectSettings.colorCycleSpeed.toDouble())}*t'")
        }
        if (effectSettings.motionTrails) {
            filters.add("tmix=frames=3:weights='1 1 1'")
        }
        if (effectSettings.sharpen) {
            filters.add("unsharp=5:5:1.0:5:5:0.0")
        }
        if (effectSettings.edgeDetect) {
            filters.add("edgedetect=mode=colormix:high=0.2:low=0.1")
        }
        if (effectSettings.negate) {
            filters.add("negate")
        }
        if (effectSettings.flipHorizontal) {
            filters.add("hflip")
        }
        if (effectSettings.flipVertical) {
            filters.add("vflip")
        }
        buildTextOverlayFilter(effectSettings.textOverlay)?.let(filters::add)
        filters.add("format=rgba")
        return filters
    }

    private fun buildTrimFilter(effectSettings: EffectSettings): String? {
        val startMs = effectSettings.clipTrim.startMs
        val endMs = effectSettings.clipTrim.endMs
        if (startMs <= 0 && endMs == null) {
            return null
        }
        val parts = mutableListOf<String>()
        if (startMs > 0) {
            parts += "start=${formatSeconds(startMs)}"
        }
        if (endMs != null) {
            parts += "end=${formatSeconds(endMs)}"
        }
        return "trim=${parts.joinToString(":")}"
    }

    private fun buildEqFilter(effectSettings: EffectSettings): String {
        return "eq=brightness=${formatDecimal(effectSettings.brightness.toDouble())}:" +
            "contrast=${formatDecimal(effectSettings.contrast.toDouble())}:" +
            "saturation=${formatDecimal(effectSettings.saturation.toDouble())}"
    }

    private fun buildColorBalanceFilter(balance: RgbBalance): String? {
        if (isApproximately(balance.red, 1f) &&
            isApproximately(balance.green, 1f) &&
            isApproximately(balance.blue, 1f)
        ) {
            return null
        }
        return buildString {
            append("colorchannelmixer=")
            append("rr=${formatDecimal(balance.red.toDouble())}:")
            append("rg=0:rb=0:")
            append("gr=0:gg=${formatDecimal(balance.green.toDouble())}:")
            append("gb=0:")
            append("br=0:bg=0:bb=${formatDecimal(balance.blue.toDouble())}")
        }
    }

    private fun buildHueFilter(hue: Float): String? {
        if (isApproximatelyZero(hue)) {
            return null
        }
        return "hue=h=${formatDecimal(hue.toDouble())}"
    }

    private fun buildTextOverlayFilter(overlay: TextOverlay): String? {
        if (!overlay.enabled) {
            return null
        }
        val sanitizedText = sanitizeOverlayText(overlay.text) ?: return null
        val color = formatColor(overlay.colorArgb)
        return "drawtext=text='$sanitizedText':fontsize=${overlay.fontSizeSp}:fontcolor=$color:" +
            "x=(w-text_w)/2:y=(h-text_h)-10:borderw=2:bordercolor=0x000000AA"
    }

    private fun buildScaleFilter(resolutionPercent: Int): String {
        val percent = resolutionPercent.coerceAtLeast(1)
        return "scale=trunc(iw*${percent}/100/2)*2:trunc(ih*${percent}/100/2)*2:flags=lanczos"
    }

    private fun buildPaletteFilter(effectSettings: EffectSettings): String {
        return "palettegen=max_colors=${effectSettings.maxColors}:stats_mode=diff"
    }

    private fun buildPaletteUseFilter(): String {
        return "paletteuse=new=1:dither=bayer:bayer_scale=5"
    }

    private fun sanitizeOverlayText(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        return trimmed
            .replace("\\", "\\\\")
            .replace(":", "\\\\:")
            .replace("'", "\\\\'")
            .replace("\"", "\\\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
    }

    private fun formatColor(color: Int): String {
        val unsigned = color.toLong() and 0xFFFFFFFFL
        return String.format(Locale.US, "0x%08X", unsigned)
    }

    private fun formatDecimal(value: Double): String {
        return String.format(Locale.US, "%.2f", value)
    }

    private fun formatSeconds(millis: Long): String {
        return String.format(Locale.US, "%.3f", millis / 1000.0)
    }

    private fun isApproximately(value: Float, expected: Float): Boolean {
        return abs(value - expected) < 0.0001f
    }

    private fun isApproximatelyZero(value: Float): Boolean = isApproximately(value, 0f)

    companion object {
        private const val SEPIA_FILTER = "colorchannelmixer=.393:.769:.189:.349:.686:.168:.272:.534:.131"
    }
}
