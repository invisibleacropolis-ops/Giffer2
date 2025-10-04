package com.example.gifvision.ffmpeg

import com.example.gifvision.ClipTrim
import com.example.gifvision.EffectSettings
import com.example.gifvision.GifTranscodeBlueprint
import com.example.gifvision.RgbBalance
import com.example.gifvision.TextOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterGraphBuilderTest {

    private val builder = FilterGraphBuilder()

    @Test
    fun `orders filters to match expected pipeline`() {
        val baseBlueprint = GifTranscodeBlueprint.sampleBlueprint()
        val overlay = TextOverlay(
            text = "  Hello \"World\" : 'Test'  ",
            fontSizeSp = 24,
            colorArgb = 0x11223344.toInt(),
            enabled = true
        )
        val customized = baseBlueprint.effectSettings.copy(
            resolutionPercent = 50,
            maxColors = 128,
            frameRate = 24.0,
            clipTrim = ClipTrim(startMs = 1_000, endMs = 3_500),
            textOverlay = overlay,
            brightness = 0.25f,
            contrast = 1.5f,
            saturation = 1.2f,
            hue = 15f,
            sepia = true,
            rgbBalance = RgbBalance(red = 1.2f, green = 0.9f, blue = 1.1f),
            chromaWarp = true,
            colorCycleSpeed = 0.5f,
            motionTrails = true,
            sharpen = true,
            edgeDetect = true,
            negate = true,
            flipHorizontal = true,
            flipVertical = true
        )
        val graphs = builder.buildStreamGraphs(baseBlueprint.copy(effectSettings = customized), customized)

        val expectedFilters = listOf(
            "trim=start=1.000:end=3.500",
            "setpts=PTS-STARTPTS",
            "fps=24.00",
            "scale=trunc(iw*50/100/2)*2:trunc(ih*50/100/2)*2:flags=lanczos",
            "eq=brightness=0.25:contrast=1.50:saturation=1.20",
            "colorchannelmixer=rr=1.20:rg=0:rb=0:gr=0:gg=0.90:gb=0:br=0:bg=0:bb=1.10",
            "hue=h=15.00",
            "colorchannelmixer=.393:.769:.189:.349:.686:.168:.272:.534:.131",
            "chromashift=cb=5:cr=-5",
            "hue='H+0.50*t'",
            "tmix=frames=3:weights='1 1 1'",
            "unsharp=5:5:1.0:5:5:0.0",
            "edgedetect=mode=colormix:high=0.2:low=0.1",
            "negate",
            "hflip",
            "vflip",
            "drawtext=text='Hello \\"World\\" \\:\\ \\'Test\\'':fontsize=24:fontcolor=0x11223344:x=(w-text_w)/2:y=(h-text_h)-10:borderw=2:bordercolor=0x000000AA",
            "format=rgba"
        )
        assertEquals(expectedFilters, graphs.baseFilters)
    }

    @Test
    fun `skips optional filters when disabled`() {
        val blueprint = baseBlueprint()
        val defaults = EffectSettings.defaultSettings(blueprint.streamId)
        val graphs = builder.buildStreamGraphs(blueprint.copy(effectSettings = defaults), defaults)

        assertFalse(graphs.baseFilters.any { it.contains("chromashift") })
        assertFalse(graphs.baseFilters.any { it.contains("tmix") })
        assertFalse(graphs.baseFilters.any { it.contains("unsharp") })
        assertFalse(graphs.baseFilters.any { it.contains("edgedetect") })
        assertFalse(graphs.baseFilters.any { it.contains("negate") })
        assertFalse(graphs.baseFilters.any { it.contains("drawtext") })
        assertTrue(graphs.baseFilters.contains("format=rgba"))
    }

    @Test
    fun `palette chains append palette stages`() {
        val blueprint = baseBlueprint()
        val graphs = builder.buildStreamGraphs(blueprint, blueprint.effectSettings)

        assertTrue(graphs.paletteFilters.last().startsWith("palettegen"))
        assertTrue(graphs.renderFilters.last().startsWith("paletteuse"))
        assertEquals(
            graphs.baseFilters.size + 1,
            graphs.paletteFilters.size
        )
        assertEquals(
            graphs.baseFilters.size + 1,
            graphs.renderFilters.size
        )
    }

    @Test
    fun `palette generation respects max colors`() {
        val blueprint = baseBlueprint()
        val customized = blueprint.effectSettings.copy(maxColors = 32)
        val graphs = builder.buildStreamGraphs(blueprint.copy(effectSettings = customized), customized)

        assertTrue(graphs.paletteFilters.last().contains("max_colors=32"))
    }

    private fun baseBlueprint(): GifTranscodeBlueprint = GifTranscodeBlueprint.sampleBlueprint()
}
