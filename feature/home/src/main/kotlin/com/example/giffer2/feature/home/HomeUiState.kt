package com.example.giffer2.feature.home

import android.net.Uri
import com.example.gifvision.BlendMode
import com.example.gifvision.ClipMetadata
import com.example.gifvision.EffectSettings
import com.example.gifvision.GifExportTarget
import com.example.gifvision.GifReference
import com.example.gifvision.GifWorkProgress
import com.example.gifvision.LayerId
import com.example.gifvision.LogEntry
import com.example.gifvision.StreamChannel
import com.example.gifvision.StreamId
import java.util.UUID

/**
 * Immutable representation of the entire GifVision home screen.
 *
 * The state groups information per layer and exposes the master blend, log
 * history, and pending export/share requests.  UI components observe the
 * [uiState][GifVisionViewModel.uiState] flow and render based on the current
 * snapshot.
 */
data class HomeUiState(
    val layers: Map<LayerId, LayerUiState> = emptyMap(),
    val masterBlend: MasterBlendUiState = MasterBlendUiState(),
    val activePage: HomePage = HomePage.Layer1,
    val logEntries: List<LogEntry> = emptyList(),
    val isLogExpanded: Boolean = false,
    val hasWarnings: Boolean = false,
    val hasErrors: Boolean = false,
    val showWarningBadge: Boolean = false,
    val showErrorBadge: Boolean = false,
    val pendingSaveRequest: GifExportTarget? = null,
    val pendingShareRequest: GifExportTarget? = null,
    val activeExports: Set<GifExportTarget> = emptySet()
) {
    companion object {
        /** Factory used for previews/tests so callers get deterministic state. */
        fun preview(): HomeUiState {
            val layers = LayerId.All.associateWith { layerId ->
                LayerUiState.preview(layerId)
            }
            return HomeUiState(layers = layers)
        }
    }
}

/**
 * State for a single GifVision layer. Each layer tracks the active upload,
 * two stream previews, and the composite blend produced from streams A & B.
 */
data class LayerUiState(
    val layerId: LayerId,
    val source: GifReference? = null,
    val sourceLabel: String? = null,
    val clipMetadata: ClipMetadata? = null,
    val activeStream: StreamChannel = StreamChannel.A,
    val streams: Map<StreamChannel, StreamUiState>,
    val blend: BlendUiState
) {
    val hasSource: Boolean
        get() = source != null

    companion object {
        fun initial(layerId: LayerId): LayerUiState {
            val streams = StreamChannel.entries.associateWith { channel ->
                StreamUiState.initial(StreamId.of(layerId, channel))
            }
            return LayerUiState(
                layerId = layerId,
                streams = streams,
                blend = BlendUiState(layerId = layerId)
            )
        }

        fun preview(layerId: LayerId): LayerUiState {
            val streams = StreamChannel.entries.associateWith { channel ->
                StreamUiState.preview(StreamId.of(layerId, channel))
            }
            return LayerUiState(
                layerId = layerId,
                sourceLabel = "Sample.mp4",
                clipMetadata = ClipMetadata(width = 1920, height = 1080, durationMillis = 8_000, frameRate = 30.0),
                streams = streams,
                blend = BlendUiState(
                    layerId = layerId,
                    previewUri = Uri.parse("file:///sample/layer${layerId.index}_blend.gif"),
                    isGenerateEnabled = true
                )
            )
        }
    }
}

/**
 * Stream level state surfaced to Compose. Each stream holds its effect settings,
 * preview output, and the latest WorkManager progress snapshot (if rendering).
 */
data class StreamUiState(
    val streamId: StreamId,
    val source: GifReference? = null,
    val sourceLabel: String? = null,
    val clipMetadata: ClipMetadata? = null,
    val effectSettings: EffectSettings = EffectSettings.defaultSettings(streamId),
    val previewUri: Uri? = null,
    val workId: UUID? = null,
    val progress: GifWorkProgress? = null,
    val lastErrorMessage: String? = null,
    val isGenerateEnabled: Boolean = false
) {
    val isGenerating: Boolean
        get() = progress?.let { it.stage != GifWorkProgress.Stage.COMPLETED && it.percent < 100 } ?: false

    companion object {
        fun initial(streamId: StreamId): StreamUiState = StreamUiState(
            streamId = streamId,
            effectSettings = EffectSettings.defaultSettings(streamId)
        )

        fun preview(streamId: StreamId): StreamUiState = StreamUiState(
            streamId = streamId,
            sourceLabel = "Sample.mp4",
            effectSettings = EffectSettings.defaultSettings(streamId),
            previewUri = Uri.parse("file:///sample/${streamId.layer.index}_${streamId.channel.name.lowercase()}.gif"),
            progress = GifWorkProgress(
                workId = UUID.randomUUID(),
                percent = 100,
                stage = GifWorkProgress.Stage.COMPLETED
            ),
            isGenerateEnabled = true
        )
    }
}

/**
 * Blend preview for a single layer. Requires both stream outputs before a
 * WorkManager request can be enqueued.
 */
data class BlendUiState(
    val layerId: LayerId,
    val blendMode: BlendMode = BlendMode.Normal,
    val blendOpacity: Float = 1f,
    val previewUri: Uri? = null,
    val workId: UUID? = null,
    val progress: GifWorkProgress? = null,
    val statusMessage: String? = null,
    val isGenerateEnabled: Boolean = false
) {
    val isGenerating: Boolean
        get() = progress?.let { it.stage != GifWorkProgress.Stage.COMPLETED && it.percent < 100 } ?: false
}

/**
 * Master blend state produced from the two layer blends.
 */
data class MasterBlendUiState(
    val blendMode: BlendMode = BlendMode.Normal,
    val blendOpacity: Float = 1f,
    val previewUri: Uri? = null,
    val workId: UUID? = null,
    val progress: GifWorkProgress? = null,
    val statusMessage: String? = null,
    val isGenerateEnabled: Boolean = false
) {
    val isGenerating: Boolean
        get() = progress?.let { it.stage != GifWorkProgress.Stage.COMPLETED && it.percent < 100 } ?: false
}
