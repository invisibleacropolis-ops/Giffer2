package com.example.giffer2.feature.home

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.MediaController
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.giffer2.feature.home.processing.GifProcessingCoordinator
import com.example.giffer2.feature.home.rememberShareSheetLauncher
import com.example.gifvision.BlendMode
import com.example.gifvision.ClipMetadata
import com.example.gifvision.ClipTrim
import com.example.gifvision.GifExportTarget
import com.example.gifvision.GifReference
import com.example.gifvision.LayerId
import com.example.gifvision.LogEntry
import com.example.gifvision.RgbBalance
import com.example.gifvision.StreamChannel
import com.example.gifvision.StreamId
import com.example.gifvision.TextOverlay
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeRoute(
    modifier: Modifier = Modifier,
    viewModel: GifVisionViewModel = viewModel(factory = rememberGifVisionViewModelFactory())
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    var pendingImportLayer by remember { mutableStateOf<LayerId?>(null) }
    val shareLauncher = rememberShareSheetLauncher()

    val pendingSave = uiState.pendingSaveRequest
    LaunchedEffect(pendingSave) {
        val target = pendingSave ?: return@LaunchedEffect
        val message = try {
            val result = viewModel.save(target)
            if (result.isSuccess) {
                val saved = result.getOrThrow()
                "Saved ${target.displayName} to Downloads as ${saved.fileName}"
            } else {
                val error = result.exceptionOrNull()?.localizedMessage ?: "Unknown error"
                "Failed to save ${target.displayName}: $error"
            }
        } finally {
            viewModel.onExportFinished(target)
            viewModel.onSaveRequestConsumed()
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    val pendingShare = uiState.pendingShareRequest
    LaunchedEffect(pendingShare) {
        val target = pendingShare ?: return@LaunchedEffect
        val message = try {
            val result = viewModel.prepareShare(target)
            if (result.isSuccess) {
                val shareResult = result.getOrThrow()
                if (shareLauncher.launch(shareResult)) {
                    "Share ready for ${target.displayName}"
                } else {
                    "No compatible apps found to share ${target.displayName}"
                }
            } else {
                val error = result.exceptionOrNull()?.localizedMessage ?: "Unknown error"
                "Failed to share ${target.displayName}: $error"
            }
        } finally {
            viewModel.onExportFinished(target)
            viewModel.onShareRequestConsumed()
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    val pickVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            val layer = pendingImportLayer ?: return@rememberLauncherForActivityResult
            pendingImportLayer = null
            if (uri != null) {
                scope.launch {
                    val clip = importClip(context, uri) ?: return@launch
                    viewModel.onImportSource(
                        layerId = layer,
                        reference = clip.reference,
                        sourceLabel = clip.label,
                        metadata = clip.metadata
                    )
                }
            }
        }
    )

    Scaffold(
        modifier = modifier,
        topBar = {
            HomeTopBar(
                activePage = uiState.activePage,
                onNavigate = { page ->
                    viewModel.onPageSelected(page)
                }
            )
        }
    ) { innerPadding ->
        val scrollState = rememberScrollState()
        when (val activePage = uiState.activePage) {
            HomePage.Layer1, HomePage.Layer2 -> {
                val layerId = activePage.layerId ?: LayerId.Layer1
                val layerState = uiState.layers[layerId]
                if (layerState != null) {
                    LayerPage(
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize()
                            .verticalScroll(scrollState),
                        layerState = layerState,
                        activeExports = uiState.activeExports,
                        onBrowseFiles = { layer ->
                            pendingImportLayer = layer
                            pickVideoLauncher.launch(arrayOf("video/*"))
                        },
                        onDropUri = { layer, uri ->
                            scope.launch {
                                val clip = importClip(context, uri) ?: return@launch
                                viewModel.onImportSource(layer, clip.reference, clip.label, clip.metadata)
                            }
                        },
                        onActiveStreamChanged = viewModel::onActiveStreamChanged,
                        onResolutionChanged = viewModel::onResolutionChanged,
                        onMaxColorsChanged = viewModel::onMaxColorsChanged,
                        onFrameRateChanged = viewModel::onFrameRateChanged,
                        onClipTrimChanged = viewModel::onClipTrimChanged,
                        onBrightnessChanged = viewModel::onBrightnessChanged,
                        onContrastChanged = viewModel::onContrastChanged,
                        onSaturationChanged = viewModel::onSaturationChanged,
                        onHueChanged = viewModel::onHueChanged,
                        onSepiaChanged = viewModel::onSepiaToggled,
                        onRgbBalanceChanged = viewModel::onRgbBalanceChanged,
                        onTextOverlayChanged = viewModel::onTextOverlayChanged,
                        onChromaWarpChanged = viewModel::onChromaWarpToggled,
                        onColorCycleSpeedChanged = viewModel::onColorCycleSpeedChanged,
                        onMotionTrailsChanged = viewModel::onMotionTrailsToggled,
                        onSharpenChanged = viewModel::onSharpenToggled,
                        onEdgeDetectChanged = viewModel::onEdgeDetectToggled,
                        onNegateChanged = viewModel::onNegateToggled,
                        onFlipHorizontalChanged = viewModel::onFlipHorizontalToggled,
                        onFlipVerticalChanged = viewModel::onFlipVerticalToggled,
                        onGenerateStream = viewModel::onGenerateStream,
                        onSaveStream = { stream -> viewModel.onSaveRequested(GifExportTarget.StreamPreview(stream)) },
                        onShareStream = { stream -> viewModel.onShareRequested(GifExportTarget.StreamPreview(stream)) },
                        onBlendModeChanged = viewModel::onBlendModeChanged,
                        onBlendOpacityChanged = viewModel::onBlendOpacityChanged,
                        onGenerateBlend = viewModel::onGenerateLayerBlend,
                        onSaveBlend = { layer -> viewModel.onSaveRequested(GifExportTarget.LayerBlend(layer)) },
                        onShareBlend = { layer -> viewModel.onShareRequested(GifExportTarget.LayerBlend(layer)) },
                        logEntries = uiState.logEntries,
                        isLogExpanded = uiState.isLogExpanded,
                        showWarningBadge = uiState.showWarningBadge,
                        showErrorBadge = uiState.showErrorBadge,
                        onLogExpandedChanged = viewModel::onLogExpandedChanged
                    )
                }
            }

            HomePage.MasterBlend -> {
                MasterBlendPage(
                    modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                    masterBlend = uiState.masterBlend,
                    layers = uiState.layers,
                    activeExports = uiState.activeExports,
                    onBlendModeChanged = viewModel::onMasterBlendModeChanged,
                    onBlendOpacityChanged = viewModel::onMasterBlendOpacityChanged,
                    onGenerate = viewModel::onGenerateMasterBlend,
                    onSave = { viewModel.onSaveRequested(GifExportTarget.MasterBlend) },
                    onShare = { viewModel.onShareRequested(GifExportTarget.MasterBlend) },
                    logEntries = uiState.logEntries,
                    isLogExpanded = uiState.isLogExpanded,
                    showWarningBadge = uiState.showWarningBadge,
                    showErrorBadge = uiState.showErrorBadge,
                    onLogExpandedChanged = viewModel::onLogExpandedChanged
                )
            }
        }
    }
}

@Composable
private fun rememberGifVisionViewModelFactory(): ViewModelProvider.Factory {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val coordinator = GifProcessingCoordinator(context)
                @Suppress("UNCHECKED_CAST")
                return GifVisionViewModel(coordinator) as T
            }
        }
    }
}

@Composable
private fun HomeTopBar(
    activePage: HomePage,
    onNavigate: (HomePage) -> Unit
) {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = "GifVision",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HomePage.ordered.forEach { page ->
                val isSelected = page == activePage
                ElevatedButton(
                    onClick = { onNavigate(page) },
                    shape = RoundedCornerShape(20.dp),
                    colors = if (isSelected) {
                        ButtonDefaults.elevatedButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        ButtonDefaults.elevatedButtonColors()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(page.label)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LayerPage(
    modifier: Modifier,
    layerState: LayerUiState,
    activeExports: Set<GifExportTarget>,
    onBrowseFiles: (LayerId) -> Unit,
    onDropUri: (LayerId, Uri) -> Unit,
    onActiveStreamChanged: (LayerId, StreamChannel) -> Unit,
    onResolutionChanged: (StreamId, Int) -> Unit,
    onMaxColorsChanged: (StreamId, Int) -> Unit,
    onFrameRateChanged: (StreamId, Double) -> Unit,
    onClipTrimChanged: (StreamId, ClipTrim) -> Unit,
    onBrightnessChanged: (StreamId, Float) -> Unit,
    onContrastChanged: (StreamId, Float) -> Unit,
    onSaturationChanged: (StreamId, Float) -> Unit,
    onHueChanged: (StreamId, Float) -> Unit,
    onSepiaChanged: (StreamId, Boolean) -> Unit,
    onRgbBalanceChanged: (StreamId, RgbBalance) -> Unit,
    onTextOverlayChanged: (StreamId, com.example.gifvision.TextOverlay) -> Unit,
    onChromaWarpChanged: (StreamId, Boolean) -> Unit,
    onColorCycleSpeedChanged: (StreamId, Float) -> Unit,
    onMotionTrailsChanged: (StreamId, Boolean) -> Unit,
    onSharpenChanged: (StreamId, Boolean) -> Unit,
    onEdgeDetectChanged: (StreamId, Boolean) -> Unit,
    onNegateChanged: (StreamId, Boolean) -> Unit,
    onFlipHorizontalChanged: (StreamId, Boolean) -> Unit,
    onFlipVerticalChanged: (StreamId, Boolean) -> Unit,
    onGenerateStream: (StreamId) -> Unit,
    onSaveStream: (StreamId) -> Unit,
    onShareStream: (StreamId) -> Unit,
    onBlendModeChanged: (LayerId, BlendMode) -> Unit,
    onBlendOpacityChanged: (LayerId, Float) -> Unit,
    onGenerateBlend: (LayerId) -> Unit,
    onSaveBlend: (LayerId) -> Unit,
    onShareBlend: (LayerId) -> Unit,
    logEntries: List<LogEntry>,
    isLogExpanded: Boolean,
    showWarningBadge: Boolean,
    showErrorBadge: Boolean,
    onLogExpandedChanged: (Boolean) -> Unit
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        UploadCard(
            layerState = layerState,
            onBrowseFiles = onBrowseFiles,
            onDropUri = onDropUri
        )

        VideoPreviewCard(layerState = layerState)

        AdjustmentsAccordion(
            layerState = layerState,
            onActiveStreamChanged = onActiveStreamChanged,
            onResolutionChanged = onResolutionChanged,
            onMaxColorsChanged = onMaxColorsChanged,
            onFrameRateChanged = onFrameRateChanged,
            onClipTrimChanged = onClipTrimChanged,
            onBrightnessChanged = onBrightnessChanged,
            onContrastChanged = onContrastChanged,
            onSaturationChanged = onSaturationChanged,
            onHueChanged = onHueChanged,
            onSepiaChanged = onSepiaChanged,
            onRgbBalanceChanged = onRgbBalanceChanged,
            onTextOverlayChanged = onTextOverlayChanged,
            onChromaWarpChanged = onChromaWarpChanged,
            onColorCycleSpeedChanged = onColorCycleSpeedChanged,
            onMotionTrailsChanged = onMotionTrailsChanged,
            onSharpenChanged = onSharpenChanged,
            onEdgeDetectChanged = onEdgeDetectChanged,
            onNegateChanged = onNegateChanged,
            onFlipHorizontalChanged = onFlipHorizontalChanged,
            onFlipVerticalChanged = onFlipVerticalChanged
        )

        StreamPreviewSection(
            layerState = layerState,
            activeExports = activeExports,
            onGenerateStream = onGenerateStream,
            onSaveStream = onSaveStream,
            onShareStream = onShareStream
        )

        LayerBlendCard(
            layerState = layerState,
            activeExports = activeExports,
            onBlendModeChanged = onBlendModeChanged,
            onBlendOpacityChanged = onBlendOpacityChanged,
            onGenerateBlend = onGenerateBlend,
            onSaveBlend = onSaveBlend,
            onShareBlend = onShareBlend
        )

        FfmpegLogPanel(
            logEntries = logEntries,
            isExpanded = isLogExpanded,
            showWarningBadge = showWarningBadge,
            showErrorBadge = showErrorBadge,
            onExpandedChanged = onLogExpandedChanged
        )
    }
}

@Composable
private fun UploadCard(
    layerState: LayerUiState,
    onBrowseFiles: (LayerId) -> Unit,
    onDropUri: (LayerId, Uri) -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event ->
                    event.mimeTypes?.any { it.startsWith("image/") || it.startsWith("video/") } == true
                },
                onDrop = { _, event ->
                    val uri = event.clipData?.getItemAt(0)?.uri
                    if (uri != null) {
                        onDropUri(layerState.layerId, uri)
                    }
                    true
                }
            )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "${layerState.layerId.displayName} Source",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = layerState.sourceLabel ?: "Drag a video here or browse to select one",
                color = if (layerState.hasSource) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onBrowseFiles(layerState.layerId) }) {
                    Text(if (layerState.hasSource) "Change" else "Browse Files")
                }
                if (layerState.hasSource) {
                    TextButton(onClick = { onBrowseFiles(layerState.layerId) }) {
                        Text("Replace")
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoPreviewCard(layerState: LayerUiState) {
    val stream = layerState.streams[layerState.activeStream]
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Video Preview",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (layerState.source != null) {
                val uri = when (val reference = layerState.source) {
                    is GifReference.ContentUri -> Uri.parse(reference.uri)
                    is GifReference.FileUri -> Uri.parse(reference.uri)
                    is GifReference.InMemory -> null
                }
                if (uri != null) {
                    VideoPreview(uri = uri)
                } else {
                    Text("Preview unavailable for in-memory sources.")
                }
            } else {
                Text("Upload a video to enable preview.")
            }
            Spacer(modifier = Modifier.height(16.dp))
            StreamMetadataSummary(stream)
        }
    }
}

@Composable
private fun StreamMetadataSummary(stream: StreamUiState?) {
    if (stream == null) return
    val metadata = stream.clipMetadata
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        metadata?.width?.let { width ->
            Text("Width: $width px")
        }
        metadata?.height?.let { height ->
            Text("Height: $height px")
        }
        metadata?.durationMillis?.let { duration ->
            Text("Duration: ${duration / 1000.0}s")
        }
        metadata?.frameRate?.let { fps ->
            Text("Frame rate: ${"%.2f".format(fps)} fps")
        }
    }
}

@Composable
private fun StreamPreviewSection(
    layerState: LayerUiState,
    activeExports: Set<GifExportTarget>,
    onGenerateStream: (StreamId) -> Unit,
    onSaveStream: (StreamId) -> Unit,
    onShareStream: (StreamId) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Stream Previews",
            style = MaterialTheme.typography.titleMedium
        )
        StreamChannel.entries.forEach { channel ->
            val streamState = layerState.streams[channel] ?: return@forEach
            val exportTarget = GifExportTarget.StreamPreview(streamState.streamId)
            val exportEnabled = exportTarget !in activeExports
            StreamPreviewCard(
                streamState = streamState,
                isExportEnabled = exportEnabled,
                onGenerate = { onGenerateStream(streamState.streamId) },
                onSave = { onSaveStream(streamState.streamId) },
                onShare = { onShareStream(streamState.streamId) }
            )
        }
    }
}

@Composable
private fun StreamPreviewCard(
    streamState: StreamUiState,
    isExportEnabled: Boolean,
    onGenerate: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = streamState.streamId.displayName,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (streamState.previewUri != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(streamState.previewUri)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Generate to see a preview")
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (streamState.isGenerating) {
                streamState.progress?.let { progress ->
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        progress = progress.percent / 100f
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(progress.stage.name.lowercase(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) })
                }
            }
            streamState.lastErrorMessage?.let { error ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onGenerate, enabled = streamState.isGenerateEnabled) {
                    Text("Generate")
                }
                OutlinedButton(
                    onClick = onSave,
                    enabled = streamState.previewUri != null && isExportEnabled
                ) {
                    Text("Save")
                }
                OutlinedButton(
                    onClick = onShare,
                    enabled = streamState.previewUri != null && isExportEnabled
                ) {
                    Text("Share")
                }
            }
        }
    }
}

@Composable
private fun LayerBlendCard(
    layerState: LayerUiState,
    activeExports: Set<GifExportTarget>,
    onBlendModeChanged: (LayerId, BlendMode) -> Unit,
    onBlendOpacityChanged: (LayerId, Float) -> Unit,
    onGenerateBlend: (LayerId) -> Unit,
    onSaveBlend: (LayerId) -> Unit,
    onShareBlend: (LayerId) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "${layerState.layerId.displayName} Blend",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            BlendControls(
                blendMode = layerState.blend.blendMode,
                blendOpacity = layerState.blend.blendOpacity,
                onModeChanged = { onBlendModeChanged(layerState.layerId, it) },
                onOpacityChanged = { onBlendOpacityChanged(layerState.layerId, it) }
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (layerState.blend.previewUri != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(layerState.blend.previewUri)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Generate the layer blend to preview")
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            layerState.blend.statusMessage?.let { status ->
                Text(status, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(8.dp))
            }
            layerState.blend.progress?.let { progress ->
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    progress = progress.percent / 100f
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(progress.stage.name)
            }
            Spacer(modifier = Modifier.height(12.dp))
            val exportTarget = GifExportTarget.LayerBlend(layerState.layerId)
            val exportEnabled = exportTarget !in activeExports
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onGenerateBlend(layerState.layerId) }, enabled = layerState.blend.isGenerateEnabled) {
                    Text("Generate")
                }
                OutlinedButton(
                    onClick = { onSaveBlend(layerState.layerId) },
                    enabled = layerState.blend.previewUri != null && exportEnabled
                ) {
                    Text("Save")
                }
                OutlinedButton(
                    onClick = { onShareBlend(layerState.layerId) },
                    enabled = layerState.blend.previewUri != null && exportEnabled
                ) {
                    Text("Share")
                }
            }
        }
    }
}

@Composable
private fun BlendControls(
    blendMode: BlendMode,
    blendOpacity: Float,
    onModeChanged: (BlendMode) -> Unit,
    onOpacityChanged: (Float) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box {
            OutlinedButton(onClick = { menuExpanded = true }) {
                Text("Mode: ${blendMode.displayName}")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                BlendMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.displayName) },
                        onClick = {
                            onModeChanged(mode)
                            menuExpanded = false
                        }
                    )
                }
            }
        }
        Column {
            Text("Opacity: ${(blendOpacity * 100).toInt()}%")
            Slider(
                value = blendOpacity,
                onValueChange = onOpacityChanged,
                valueRange = 0f..1f,
                steps = 9
            )
        }
    }
}

@Composable
private fun AdjustmentsAccordion(
    layerState: LayerUiState,
    onActiveStreamChanged: (LayerId, StreamChannel) -> Unit,
    onResolutionChanged: (StreamId, Int) -> Unit,
    onMaxColorsChanged: (StreamId, Int) -> Unit,
    onFrameRateChanged: (StreamId, Double) -> Unit,
    onClipTrimChanged: (StreamId, ClipTrim) -> Unit,
    onBrightnessChanged: (StreamId, Float) -> Unit,
    onContrastChanged: (StreamId, Float) -> Unit,
    onSaturationChanged: (StreamId, Float) -> Unit,
    onHueChanged: (StreamId, Float) -> Unit,
    onSepiaChanged: (StreamId, Boolean) -> Unit,
    onRgbBalanceChanged: (StreamId, RgbBalance) -> Unit,
    onTextOverlayChanged: (StreamId, TextOverlay) -> Unit,
    onChromaWarpChanged: (StreamId, Boolean) -> Unit,
    onColorCycleSpeedChanged: (StreamId, Float) -> Unit,
    onMotionTrailsChanged: (StreamId, Boolean) -> Unit,
    onSharpenChanged: (StreamId, Boolean) -> Unit,
    onEdgeDetectChanged: (StreamId, Boolean) -> Unit,
    onNegateChanged: (StreamId, Boolean) -> Unit,
    onFlipHorizontalChanged: (StreamId, Boolean) -> Unit,
    onFlipVerticalChanged: (StreamId, Boolean) -> Unit
) {
    val stream = layerState.streams[layerState.activeStream] ?: return
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Adjustments", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StreamChannel.entries.forEach { channel ->
                    ElevatedFilterChip(
                        selected = channel == layerState.activeStream,
                        onClick = { onActiveStreamChanged(layerState.layerId, channel) },
                        label = { Text(channel.displayName) },
                        shape = RoundedCornerShape(16.dp),
                        colors = FilterChipDefaults.elevatedFilterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
            }
            QualitySection(stream, onResolutionChanged, onMaxColorsChanged, onFrameRateChanged)
            ClipTrimSection(stream, onClipTrimChanged)
            TextOverlaySection(stream, onTextOverlayChanged)
            ColorToneSection(stream, onBrightnessChanged, onContrastChanged, onSaturationChanged, onHueChanged, onSepiaChanged)
            RgbBalanceSection(stream, onRgbBalanceChanged)
            ExperimentalSection(
                stream = stream,
                onChromaWarpChanged = onChromaWarpChanged,
                onColorCycleSpeedChanged = onColorCycleSpeedChanged,
                onMotionTrailsChanged = onMotionTrailsChanged,
                onSharpenChanged = onSharpenChanged,
                onEdgeDetectChanged = onEdgeDetectChanged,
                onNegateChanged = onNegateChanged,
                onFlipHorizontalChanged = onFlipHorizontalChanged,
                onFlipVerticalChanged = onFlipVerticalChanged
            )
        }
    }
}

@Composable
private fun QualitySection(
    stream: StreamUiState,
    onResolutionChanged: (StreamId, Int) -> Unit,
    onMaxColorsChanged: (StreamId, Int) -> Unit,
    onFrameRateChanged: (StreamId, Double) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Quality & Size", fontWeight = FontWeight.SemiBold)
        SliderWithValue(
            label = "Resolution",
            value = stream.effectSettings.resolutionPercent.toFloat(),
            valueRange = 25f..200f,
            onValueChange = { onResolutionChanged(stream.streamId, it.toInt()) },
            valueFormatter = { "${it.toInt()}%" }
        )
        SliderWithValue(
            label = "Max Colors",
            value = stream.effectSettings.maxColors.toFloat(),
            valueRange = 2f..256f,
            onValueChange = { onMaxColorsChanged(stream.streamId, it.toInt()) },
            steps = 254,
            valueFormatter = { it.toInt().toString() }
        )
        SliderWithValue(
            label = "Frame Rate",
            value = stream.effectSettings.frameRate.toFloat(),
            valueRange = 4f..60f,
            onValueChange = { onFrameRateChanged(stream.streamId, it.toDouble()) },
            valueFormatter = { "${"%.1f".format(it)} fps" }
        )
    }
}

@Composable
private fun SliderWithValue(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    steps: Int = 0,
    valueFormatter: (Float) -> String
) {
    Column {
        Text("$label: ${valueFormatter(value)}")
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps
        )
    }
}

@Composable
private fun ClipTrimSection(
    stream: StreamUiState,
    onClipTrimChanged: (StreamId, ClipTrim) -> Unit
) {
    val duration = stream.clipMetadata?.durationMillis?.toFloat()?.takeIf { it > 0 }
    if (duration == null) {
        Text("Clip trim available after metadata loads", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val start = stream.effectSettings.clipTrim.startMs.coerceIn(0L, duration.toLong())
    val end = stream.effectSettings.clipTrim.endMs ?: duration.toLong()
    val range = remember(start, end) { mutableStateOf(start.toFloat()..end.toFloat()) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Clip Range", fontWeight = FontWeight.SemiBold)
        RangeSlider(
            value = range.value,
            valueRange = 0f..duration,
            onValueChange = { updated ->
                range.value = updated
                val startMs = updated.start.toLong()
                val endMs = updated.endInclusive.toLong()
                val normalizedEnd = if (endMs >= duration.toLong()) null else endMs
                onClipTrimChanged(
                    stream.streamId,
                    ClipTrim(startMs = startMs, endMs = normalizedEnd)
                )
            }
        )
        Text(
            text = "Start: ${"%.2f".format(range.value.start / 1000f)}s    End: ${"%.2f".format(range.value.endInclusive / 1000f)}s"
        )
    }
}

@Composable
private fun TextOverlaySection(
    stream: StreamUiState,
    onTextOverlayChanged: (StreamId, TextOverlay) -> Unit
) {
    var overlay by remember(stream.streamId) { mutableStateOf(stream.effectSettings.textOverlay) }
    var colorHex by remember(stream.streamId) {
        mutableStateOf(String.format("#%08X", overlay.colorArgb))
    }

    fun dispatch(updated: TextOverlay) {
        overlay = updated
        onTextOverlayChanged(stream.streamId, updated)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Text Overlay", fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enabled")
            Spacer(Modifier.width(12.dp))
            Switch(checked = overlay.enabled, onCheckedChange = { dispatch(overlay.copy(enabled = it)) })
        }
        OutlinedTextField(
            value = overlay.text,
            onValueChange = { dispatch(overlay.copy(text = it)) },
            label = { Text("Text") },
            enabled = overlay.enabled,
            modifier = Modifier.fillMaxWidth()
        )
        SliderWithValue(
            label = "Font Size",
            value = overlay.fontSizeSp.toFloat(),
            valueRange = 8f..72f,
            onValueChange = { dispatch(overlay.copy(fontSizeSp = it.toInt())) },
            valueFormatter = { it.toInt().toString() }
        )
        OutlinedTextField(
            value = colorHex,
            onValueChange = {
                colorHex = it
                dispatch(overlay.copy(colorArgb = parseColor(it)))
            },
            label = { Text("Color (#AARRGGBB)") },
            enabled = overlay.enabled,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun parseColor(value: String): Int {
    return runCatching { android.graphics.Color.parseColor(value) }.getOrElse { 0xFFFFFFFF.toInt() }
}

@Composable
private fun ColorToneSection(
    stream: StreamUiState,
    onBrightnessChanged: (StreamId, Float) -> Unit,
    onContrastChanged: (StreamId, Float) -> Unit,
    onSaturationChanged: (StreamId, Float) -> Unit,
    onHueChanged: (StreamId, Float) -> Unit,
    onSepiaChanged: (StreamId, Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Color & Tone", fontWeight = FontWeight.SemiBold)
        SliderWithValue(
            label = "Brightness",
            value = stream.effectSettings.brightness,
            valueRange = -1f..1f,
            onValueChange = { onBrightnessChanged(stream.streamId, it) },
            valueFormatter = { "${"%.2f".format(it)}" }
        )
        SliderWithValue(
            label = "Contrast",
            value = stream.effectSettings.contrast,
            valueRange = 0.2f..3f,
            onValueChange = { onContrastChanged(stream.streamId, it) },
            valueFormatter = { "${"%.2f".format(it)}" }
        )
        SliderWithValue(
            label = "Saturation",
            value = stream.effectSettings.saturation,
            valueRange = 0f..3f,
            onValueChange = { onSaturationChanged(stream.streamId, it) },
            valueFormatter = { "${"%.2f".format(it)}" }
        )
        SliderWithValue(
            label = "Hue",
            value = stream.effectSettings.hue,
            valueRange = -180f..180f,
            onValueChange = { onHueChanged(stream.streamId, it) },
            valueFormatter = { "${"%.0f".format(it)}°" }
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sepia")
            Spacer(modifier = Modifier.width(12.dp))
            Switch(checked = stream.effectSettings.sepia, onCheckedChange = { onSepiaChanged(stream.streamId, it) })
        }
    }
}

@Composable
private fun RgbBalanceSection(
    stream: StreamUiState,
    onRgbBalanceChanged: (StreamId, RgbBalance) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("RGB Balance", fontWeight = FontWeight.SemiBold)
        BalanceSlider(
            label = "Red",
            value = stream.effectSettings.rgbBalance.red,
            onValueChange = { onRgbBalanceChanged(stream.streamId, stream.effectSettings.rgbBalance.copy(red = it)) }
        )
        BalanceSlider(
            label = "Green",
            value = stream.effectSettings.rgbBalance.green,
            onValueChange = { onRgbBalanceChanged(stream.streamId, stream.effectSettings.rgbBalance.copy(green = it)) }
        )
        BalanceSlider(
            label = "Blue",
            value = stream.effectSettings.rgbBalance.blue,
            onValueChange = { onRgbBalanceChanged(stream.streamId, stream.effectSettings.rgbBalance.copy(blue = it)) }
        )
    }
}

@Composable
private fun BalanceSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    SliderWithValue(
        label = label,
        value = value,
        valueRange = 0f..2f,
        onValueChange = onValueChange,
        valueFormatter = { "${"%.2f".format(it)}" }
    )
}

@Composable
private fun ExperimentalSection(
    stream: StreamUiState,
    onChromaWarpChanged: (StreamId, Boolean) -> Unit,
    onColorCycleSpeedChanged: (StreamId, Float) -> Unit,
    onMotionTrailsChanged: (StreamId, Boolean) -> Unit,
    onSharpenChanged: (StreamId, Boolean) -> Unit,
    onEdgeDetectChanged: (StreamId, Boolean) -> Unit,
    onNegateChanged: (StreamId, Boolean) -> Unit,
    onFlipHorizontalChanged: (StreamId, Boolean) -> Unit,
    onFlipVerticalChanged: (StreamId, Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Experimental & Artistic", fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Chroma Warp")
            Spacer(Modifier.width(12.dp))
            Switch(checked = stream.effectSettings.chromaWarp, onCheckedChange = { onChromaWarpChanged(stream.streamId, it) })
        }
        SliderWithValue(
            label = "Color Cycle Speed",
            value = stream.effectSettings.colorCycleSpeed,
            valueRange = 0f..10f,
            onValueChange = { onColorCycleSpeedChanged(stream.streamId, it) },
            valueFormatter = { "${"%.2f".format(it)}" }
        )
        ToggleRow(label = "Motion Trails", checked = stream.effectSettings.motionTrails, onCheckedChange = { onMotionTrailsChanged(stream.streamId, it) })
        ToggleRow(label = "Sharpen", checked = stream.effectSettings.sharpen, onCheckedChange = { onSharpenChanged(stream.streamId, it) })
        ToggleRow(label = "Edge Detect", checked = stream.effectSettings.edgeDetect, onCheckedChange = { onEdgeDetectChanged(stream.streamId, it) })
        ToggleRow(label = "Negate Colors", checked = stream.effectSettings.negate, onCheckedChange = { onNegateChanged(stream.streamId, it) })
        ToggleRow(label = "Flip Horizontal", checked = stream.effectSettings.flipHorizontal, onCheckedChange = { onFlipHorizontalChanged(stream.streamId, it) })
        ToggleRow(label = "Flip Vertical", checked = stream.effectSettings.flipVertical, onCheckedChange = { onFlipVerticalChanged(stream.streamId, it) })
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun VideoPreview(
    uri: Uri,
    modifier: Modifier = Modifier
) {
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp)
            .clip(RoundedCornerShape(16.dp)),
        factory = { context ->
            android.widget.VideoView(context).apply {
                tag = uri.toString()
                val controller = MediaController(context)
                controller.setAnchorView(this)
                setMediaController(controller)
                setVideoURI(uri)
                setOnPreparedListener { player ->
                    player.isLooping = true
                    seekTo(1)
                    start()
                }
            }
        },
        update = { view ->
            if (view.tag != uri.toString()) {
                view.tag = uri.toString()
                view.setVideoURI(uri)
                view.start()
            }
        }
    )
}

@Composable
private fun MasterBlendPage(
    modifier: Modifier,
    masterBlend: MasterBlendUiState,
    layers: Map<LayerId, LayerUiState>,
    activeExports: Set<GifExportTarget>,
    onBlendModeChanged: (BlendMode) -> Unit,
    onBlendOpacityChanged: (Float) -> Unit,
    onGenerate: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    logEntries: List<LogEntry>,
    isLogExpanded: Boolean,
    showWarningBadge: Boolean,
    showErrorBadge: Boolean,
    onLogExpandedChanged: (Boolean) -> Unit
) {
    Column(modifier = modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Master Blend", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))
                BlendControls(
                    blendMode = masterBlend.blendMode,
                    blendOpacity = masterBlend.blendOpacity,
                    onModeChanged = onBlendModeChanged,
                    onOpacityChanged = onBlendOpacityChanged
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (masterBlend.previewUri != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(masterBlend.previewUri)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(16.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Generate layer blends to unlock the master preview")
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                masterBlend.statusMessage?.let { status ->
                    Text(status, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                masterBlend.progress?.let { progress ->
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        progress = progress.percent / 100f
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(progress.stage.name)
                }
                Spacer(modifier = Modifier.height(12.dp))
                val exportEnabled = GifExportTarget.MasterBlend !in activeExports
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onGenerate, enabled = masterBlend.isGenerateEnabled) {
                        Text("Generate")
                    }
                    OutlinedButton(
                        onClick = onSave,
                        enabled = masterBlend.previewUri != null && exportEnabled
                    ) {
                        Text("Save")
                    }
                    OutlinedButton(
                        onClick = onShare,
                        enabled = masterBlend.previewUri != null && exportEnabled
                    ) {
                        Text("Share")
                    }
                }
            }
        }

        SummaryCard(layers)

        FfmpegLogPanel(
            logEntries = logEntries,
            isExpanded = isLogExpanded,
            showWarningBadge = showWarningBadge,
            showErrorBadge = showErrorBadge,
            onExpandedChanged = onLogExpandedChanged
        )
    }
}

@Composable
private fun SummaryCard(layers: Map<LayerId, LayerUiState>) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Layer Status", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            layers.values.sortedBy { it.layerId }.forEach { layer ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(layer.layerId.displayName, fontWeight = FontWeight.SemiBold)
                    val ready = layer.blend.previewUri != null
                    Text(if (ready) "Blend ready" else "Waiting for blend")
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

private data class ImportedClip(
    val reference: GifReference,
    val label: String?,
    val metadata: ClipMetadata
)

private suspend fun importClip(context: Context, uri: Uri): ImportedClip? {
    return withContext(Dispatchers.IO) {
        val reference = when (uri.scheme?.lowercase(Locale.US)) {
            null, "file" -> GifReference.FileUri(uri.toString())
            else -> GifReference.ContentUri(uri.toString())
        }
        val label = context.contentResolver.queryDisplayName(uri)
        if (DocumentsContract.isDocumentUri(context, uri)) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        val metadata = readClipMetadata(context, uri) ?: ClipMetadata(width = null, height = null, durationMillis = null, frameRate = null)
        ImportedClip(reference, label, metadata)
    }
}

private fun Context.queryDisplayName(uri: Uri): String? {
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
    return contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getString(0)
        } else {
            null
        }
    }
}

private suspend fun readClipMetadata(context: Context, uri: Uri): ClipMetadata? {
    return withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_RATE)?.toDoubleOrNull()
            ClipMetadata(width = width, height = height, durationMillis = duration, frameRate = frameRate)
        } catch (error: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}

*** End of File
