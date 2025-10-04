package com.example.giffer2.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.gifvision.BlendMode
import com.example.gifvision.ClipMetadata
import com.example.gifvision.ClipTrim
import com.example.gifvision.EffectSettings
import com.example.gifvision.GifReference
import com.example.gifvision.GifTranscodeBlueprint
import com.example.gifvision.GifWorkProgress
import com.example.gifvision.LayerId
import com.example.gifvision.LogEntry
import com.example.gifvision.LogSeverity
import com.example.gifvision.RgbBalance
import com.example.gifvision.StreamChannel
import com.example.gifvision.StreamId
import com.example.gifvision.TextOverlay
import com.example.giffer2.feature.home.processing.GifProcessingCoordinator
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Central presenter for the GifVision home experience.
 *
 * The view model exposes a [StateFlow] of [HomeUiState] describing every card in the
 * interface.  UI callbacks invoke the intent methods below, which update the state
 * immutably and enqueue background work via [GifProcessingCoordinator] when required.
 */
class GifVisionViewModel(
    private val coordinator: GifProcessingCoordinator
) : ViewModel() {

    private val localLogs = MutableStateFlow<List<LogEntry>>(emptyList())

    private val _uiState = MutableStateFlow(createInitialState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val streamJobs = mutableMapOf<StreamId, Job>()
    private val blendJobs = mutableMapOf<LayerId, Job>()
    private var masterJob: Job? = null

    init {
        observeCoordinatorStreams()
        observeCoordinatorBlends()
        observeCoordinatorMasterBlend()
        observeLogs()
    }

    /** Registers a new source clip for [layerId] and resets dependent previews. */
    fun onImportSource(
        layerId: LayerId,
        reference: GifReference,
        sourceLabel: String?,
        metadata: ClipMetadata
    ) {
        coordinator.resetForNewSource(layerId)
        StreamChannel.entries.forEach { channel ->
            streamJobs.remove(StreamId.of(layerId, channel))?.cancel()
        }
        blendJobs.remove(layerId)?.cancel()
        masterJob?.cancel()
        masterJob = null
        updateLayer(layerId) { layer ->
            val updatedStreams = layer.streams.mapValues { (_, stream) ->
                val resetSettings = stream.effectSettings.resetForNewSource(metadata)
                stream.copy(
                    source = reference,
                    sourceLabel = sourceLabel,
                    clipMetadata = metadata,
                    effectSettings = resetSettings,
                    previewUri = null,
                    progress = null,
                    workId = null,
                    lastErrorMessage = null,
                    isGenerateEnabled = true
                )
            }
            layer.copy(
                source = reference,
                sourceLabel = sourceLabel,
                clipMetadata = metadata,
                streams = updatedStreams,
                blend = layer.blend.copy(
                    previewUri = null,
                    progress = null,
                    workId = null,
                    statusMessage = null,
                    isGenerateEnabled = false
                )
            )
        }
        clearMasterBlendPreview()
        postLog(
            message = "${layerId.displayName} source set to ${sourceLabel ?: reference.label}",
            severity = LogSeverity.INFO
        )
    }

    /** Switches the active stream used by the adjustments accordion. */
    fun onActiveStreamChanged(layerId: LayerId, channel: StreamChannel) {
        updateLayer(layerId) { it.copy(activeStream = channel) }
    }

    /** Updates the clip trim for the given [streamId]. */
    fun onClipTrimChanged(streamId: StreamId, clipTrim: ClipTrim) {
        updateEffectSettings(streamId) { settings -> settings.copy(clipTrim = clipTrim) }
    }

    fun onResolutionChanged(streamId: StreamId, resolutionPercent: Int) {
        updateEffectSettings(streamId) { it.copy(resolutionPercent = resolutionPercent) }
    }

    fun onMaxColorsChanged(streamId: StreamId, maxColors: Int) {
        updateEffectSettings(streamId) { it.copy(maxColors = maxColors) }
    }

    fun onFrameRateChanged(streamId: StreamId, frameRate: Double) {
        updateEffectSettings(streamId) { it.copy(frameRate = frameRate) }
    }

    fun onBrightnessChanged(streamId: StreamId, brightness: Float) {
        updateEffectSettings(streamId) { it.copy(brightness = brightness) }
    }

    fun onContrastChanged(streamId: StreamId, contrast: Float) {
        updateEffectSettings(streamId) { it.copy(contrast = contrast) }
    }

    fun onSaturationChanged(streamId: StreamId, saturation: Float) {
        updateEffectSettings(streamId) { it.copy(saturation = saturation) }
    }

    fun onHueChanged(streamId: StreamId, hue: Float) {
        updateEffectSettings(streamId) { it.copy(hue = hue) }
    }

    fun onSepiaToggled(streamId: StreamId, enabled: Boolean) {
        updateEffectSettings(streamId) { it.copy(sepia = enabled) }
    }

    fun onRgbBalanceChanged(streamId: StreamId, balance: RgbBalance) {
        updateEffectSettings(streamId) { it.copy(rgbBalance = balance) }
    }

    fun onTextOverlayChanged(streamId: StreamId, overlay: TextOverlay) {
        updateEffectSettings(streamId) { it.copy(textOverlay = overlay) }
    }

    fun onChromaWarpToggled(streamId: StreamId, enabled: Boolean) {
        updateEffectSettings(streamId) { it.copy(chromaWarp = enabled) }
    }

    fun onColorCycleSpeedChanged(streamId: StreamId, speed: Float) {
        updateEffectSettings(streamId) { it.copy(colorCycleSpeed = speed) }
    }

    fun onMotionTrailsToggled(streamId: StreamId, enabled: Boolean) {
        updateEffectSettings(streamId) { it.copy(motionTrails = enabled) }
    }

    fun onSharpenToggled(streamId: StreamId, enabled: Boolean) {
        updateEffectSettings(streamId) { it.copy(sharpen = enabled) }
    }

    fun onEdgeDetectToggled(streamId: StreamId, enabled: Boolean) {
        updateEffectSettings(streamId) { it.copy(edgeDetect = enabled) }
    }

    fun onNegateToggled(streamId: StreamId, enabled: Boolean) {
        updateEffectSettings(streamId) { it.copy(negate = enabled) }
    }

    fun onFlipHorizontalToggled(streamId: StreamId, enabled: Boolean) {
        updateEffectSettings(streamId) { it.copy(flipHorizontal = enabled) }
    }

    fun onFlipVerticalToggled(streamId: StreamId, enabled: Boolean) {
        updateEffectSettings(streamId) { it.copy(flipVertical = enabled) }
    }

    fun onBlendModeChanged(layerId: LayerId, blendMode: BlendMode) {
        updateLayer(layerId) { layer ->
            layer.copy(blend = layer.blend.copy(blendMode = blendMode, statusMessage = null))
        }
    }

    fun onBlendOpacityChanged(layerId: LayerId, opacity: Float) {
        updateLayer(layerId) { layer ->
            layer.copy(blend = layer.blend.copy(blendOpacity = opacity, statusMessage = null))
        }
    }

    fun onMasterBlendModeChanged(blendMode: BlendMode) {
        _uiState.update { state ->
            state.copy(masterBlend = state.masterBlend.copy(blendMode = blendMode, statusMessage = null))
        }
    }

    fun onMasterBlendOpacityChanged(opacity: Float) {
        _uiState.update { state ->
            state.copy(masterBlend = state.masterBlend.copy(blendOpacity = opacity, statusMessage = null))
        }
    }

    /** Enqueues a WorkManager job to regenerate the stream GIF. */
    fun onGenerateStream(streamId: StreamId) {
        val layerState = _uiState.value.layers[streamId.layer] ?: return
        val streamState = layerState.streams[streamId.channel] ?: return
        val source = layerState.source ?: streamState.source
        if (source == null) {
            postLog(
                message = "${streamId.displayName} is missing a source clip",
                severity = LogSeverity.WARNING
            )
            return
        }
        val blueprint = GifTranscodeBlueprint(
            blueprintId = UUID.randomUUID(),
            streamId = streamId,
            source = source,
            effectSettings = streamState.effectSettings
        )
        val handle = coordinator.enqueueStreamRender(blueprint)
        updateStream(streamId) { stream ->
            stream.copy(
                workId = handle.workId,
                progress = handle.progress.value,
                lastErrorMessage = null,
                isGenerateEnabled = false
            )
        }
        trackStreamProgress(streamId, handle.workId, handle.progress)
    }

    /** Enqueues the blend job for [layerId] if both streams have previews. */
    fun onGenerateLayerBlend(layerId: LayerId) {
        val layer = _uiState.value.layers[layerId] ?: return
        val primary = layer.streams[StreamChannel.A]?.previewUri
        val secondary = layer.streams[StreamChannel.B]?.previewUri
        if (primary == null || secondary == null) {
            val message = "${layerId.displayName} blend requires both Stream A and Stream B previews"
            updateLayer(layerId) { l -> l.copy(blend = l.blend.copy(statusMessage = message)) }
            postLog(message, LogSeverity.WARNING)
            return
        }
        val handle = coordinator.enqueueLayerBlend(
            layerId = layerId,
            primaryUri = primary,
            secondaryUri = secondary,
            blendMode = layer.blend.blendMode,
            blendOpacity = layer.blend.blendOpacity
        )
        updateLayer(layerId) { l ->
            l.copy(
                blend = l.blend.copy(
                    workId = handle.workId,
                    progress = handle.progress.value,
                    statusMessage = null,
                    isGenerateEnabled = false
                )
            )
        }
        trackLayerBlendProgress(layerId, handle.workId, handle.progress)
    }

    /** Runs the master blend when both layer blends are available. */
    fun onGenerateMasterBlend() {
        val state = _uiState.value
        val layer1 = state.layers[LayerId.Layer1]?.blend?.previewUri
        val layer2 = state.layers[LayerId.Layer2]?.blend?.previewUri
        if (layer1 == null || layer2 == null) {
            val message = "Master blend requires both Layer 1 and Layer 2 blends"
            _uiState.update { it.copy(masterBlend = it.masterBlend.copy(statusMessage = message)) }
            postLog(message, LogSeverity.WARNING)
            return
        }
        val handle = coordinator.enqueueMasterBlend(
            primaryUri = layer1,
            secondaryUri = layer2,
            blendMode = state.masterBlend.blendMode,
            blendOpacity = state.masterBlend.blendOpacity
        )
        _uiState.update { current ->
            current.copy(
                masterBlend = current.masterBlend.copy(
                    workId = handle.workId,
                    progress = handle.progress.value,
                    statusMessage = null,
                    isGenerateEnabled = false
                )
            )
        }
        trackMasterBlendProgress(handle.workId, handle.progress)
    }

    fun onSaveRequested(target: ExportTarget) {
        _uiState.update { it.copy(pendingSaveRequest = target) }
    }

    fun onShareRequested(target: ExportTarget) {
        _uiState.update { it.copy(pendingShareRequest = target) }
    }

    fun onSaveRequestConsumed() {
        _uiState.update { it.copy(pendingSaveRequest = null) }
    }

    fun onShareRequestConsumed() {
        _uiState.update { it.copy(pendingShareRequest = null) }
    }

    fun onLogExpandedChanged(isExpanded: Boolean) {
        _uiState.update { state ->
            state.copy(
                isLogExpanded = isExpanded,
                showWarningBadge = if (isExpanded) false else state.hasWarnings,
                showErrorBadge = if (isExpanded) false else state.hasErrors
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        streamJobs.values.forEach(Job::cancel)
        blendJobs.values.forEach(Job::cancel)
        masterJob?.cancel()
        coordinator.close()
    }

    private fun observeLogs() {
        viewModelScope.launch {
            combine(coordinator.logEntries, localLogs) { external, local ->
                (external + local).sortedBy(LogEntry::timestampMillis).takeLast(LOG_HISTORY_LIMIT)
            }.collect { merged ->
                _uiState.update { state ->
                    val hasWarnings = merged.any { it.severity == LogSeverity.WARNING }
                    val hasErrors = merged.any { it.severity == LogSeverity.ERROR }
                    state.copy(
                        logEntries = merged,
                        hasWarnings = hasWarnings,
                        hasErrors = hasErrors,
                        showWarningBadge = if (!state.isLogExpanded && hasWarnings) true else false,
                        showErrorBadge = if (!state.isLogExpanded && hasErrors) true else false
                    )
                }
            }
        }
    }

    private fun observeCoordinatorStreams() {
        viewModelScope.launch {
            coordinator.streamOutputs.collect { outputs ->
                _uiState.update { state ->
                    val updatedLayers = state.layers.mapValues { (layerId, layer) ->
                        val updatedStreams = layer.streams.mapValues { (channel, stream) ->
                            val streamId = StreamId.of(layerId, channel)
                            stream.copy(previewUri = outputs[streamId])
                        }
                        layer.copy(streams = updatedStreams).recalculateDerivedFlags()
                    }
                    state.copy(layers = updatedLayers).recalculateMasterBlend()
                }
            }
        }
    }

    private fun observeCoordinatorBlends() {
        viewModelScope.launch {
            coordinator.layerBlendOutputs.collect { outputs ->
                _uiState.update { state ->
                    val updatedLayers = state.layers.mapValues { (layerId, layer) ->
                        val preview = outputs[layerId]
                        layer.copy(
                            blend = layer.blend.copy(previewUri = preview)
                        ).recalculateDerivedFlags()
                    }
                    state.copy(layers = updatedLayers).recalculateMasterBlend()
                }
            }
        }
    }

    private fun observeCoordinatorMasterBlend() {
        viewModelScope.launch {
            coordinator.masterBlendOutput.collect { uri ->
                _uiState.update { state ->
                    state.copy(
                        masterBlend = state.masterBlend.copy(previewUri = uri)
                    ).recalculateMasterBlend()
                }
            }
        }
    }

    private fun updateEffectSettings(
        streamId: StreamId,
        transform: (EffectSettings) -> EffectSettings
    ) {
        updateStream(streamId) { stream ->
            stream.copy(effectSettings = transform(stream.effectSettings))
        }
    }

    private fun updateStream(streamId: StreamId, transform: (StreamUiState) -> StreamUiState) {
        updateLayer(streamId.layer) { layer ->
            val current = layer.streams[streamId.channel] ?: return@updateLayer layer
            val updated = transform(current)
            layer.copy(
                streams = layer.streams + (streamId.channel to updated)
            )
        }
    }

    private fun updateLayer(layerId: LayerId, transform: (LayerUiState) -> LayerUiState) {
        _uiState.update { state ->
            val layer = state.layers[layerId] ?: return@update state
            val updatedLayer = transform(layer).recalculateDerivedFlags()
            state.copy(
                layers = state.layers + (layerId to updatedLayer)
            ).recalculateMasterBlend()
        }
    }

    private fun HomeUiState.recalculateMasterBlend(): HomeUiState {
        val canGenerate = layers.values.all { it.blend.previewUri != null } && !masterBlend.isGenerating
        return copy(
            masterBlend = masterBlend.copy(
                isGenerateEnabled = canGenerate
            )
        )
    }

    private fun LayerUiState.recalculateDerivedFlags(): LayerUiState {
        val updatedStreams = streams.mapValues { (_, stream) ->
            stream.copy(isGenerateEnabled = hasSource && !stream.isGenerating)
        }
        val canBlend = hasSource && updatedStreams.values.all { it.previewUri != null } && !blend.isGenerating
        return copy(
            streams = updatedStreams,
            blend = blend.copy(isGenerateEnabled = canBlend)
        )
    }

    private fun trackStreamProgress(streamId: StreamId, workId: UUID, progress: StateFlow<GifWorkProgress>) {
        streamJobs.remove(streamId)?.cancel()
        val job = viewModelScope.launch {
            progress.collect { update ->
                updateStream(streamId) { stream ->
                    stream.copy(
                        workId = workId,
                        progress = update,
                        isGenerateEnabled = false
                    )
                }
                if (update.stage == GifWorkProgress.Stage.COMPLETED || update.percent >= 100) {
                    cancel()
                }
            }
        }
        job.invokeOnCompletion { streamJobs.remove(streamId) }
        streamJobs[streamId] = job
    }

    private fun trackLayerBlendProgress(layerId: LayerId, workId: UUID, progress: StateFlow<GifWorkProgress>) {
        blendJobs.remove(layerId)?.cancel()
        val job = viewModelScope.launch {
            progress.collect { update ->
                updateLayer(layerId) { layer ->
                    layer.copy(
                        blend = layer.blend.copy(
                            workId = workId,
                            progress = update,
                            isGenerateEnabled = false
                        )
                    )
                }
                if (update.stage == GifWorkProgress.Stage.COMPLETED || update.percent >= 100) {
                    cancel()
                }
            }
        }
        job.invokeOnCompletion { blendJobs.remove(layerId) }
        blendJobs[layerId] = job
    }

    private fun trackMasterBlendProgress(workId: UUID, progress: StateFlow<GifWorkProgress>) {
        masterJob?.cancel()
        val job = viewModelScope.launch {
            progress.collect { update ->
                _uiState.update { state ->
                    state.copy(
                        masterBlend = state.masterBlend.copy(
                            workId = workId,
                            progress = update,
                            isGenerateEnabled = false
                        )
                    )
                }
                if (update.stage == GifWorkProgress.Stage.COMPLETED || update.percent >= 100) {
                    cancel()
                }
            }
        }
        job.invokeOnCompletion { masterJob = null }
        masterJob = job
    }

    private fun clearMasterBlendPreview() {
        _uiState.update { state ->
            state.copy(
                masterBlend = state.masterBlend.copy(
                    previewUri = null,
                    progress = null,
                    workId = null,
                    statusMessage = null
                )
            ).recalculateMasterBlend()
        }
    }

    private fun postLog(message: String, severity: LogSeverity, workId: UUID? = null) {
        localLogs.update { existing ->
            (existing + LogEntry(message = message, severity = severity, workId = workId)).takeLast(LOG_HISTORY_LIMIT)
        }
    }

    private fun createInitialState(): HomeUiState {
        val layers = LayerId.All.associateWith { layerId -> LayerUiState.initial(layerId) }
        return HomeUiState(layers = layers)
    }

    companion object {
        private const val LOG_HISTORY_LIMIT = 50
    }
}
