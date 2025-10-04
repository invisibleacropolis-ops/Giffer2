package com.example.giffer2.feature.home.processing

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.annotation.VisibleForTesting
import androidx.core.content.FileProvider
import androidx.work.Data
import androidx.lifecycle.Observer
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.gifvision.GifExportTarget
import com.example.gifvision.BlendMode
import com.example.gifvision.GifTranscodeBlueprint
import com.example.gifvision.GifWorkDataKeys
import com.example.gifvision.GifWorkProgress
import com.example.gifvision.GifWorkTracker
import com.example.gifvision.LayerId
import com.example.gifvision.LogEntry
import com.example.gifvision.LogSeverity
import com.example.gifvision.StreamId
import com.example.gifvision.parseLogEntries
import com.example.giffer2.core.ffmpeg.FfmpegKitInitializer
import com.example.gifvision.ffmpeg.GifBlendWorker
import com.example.gifvision.ffmpeg.GifGenerationWorker
import com.example.gifvision.ffmpeg.MasterBlendWorker
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.UUID
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Central coordination layer for GifVision background work.
 *
 * The coordinator owns all WorkManager requests for stream renders, layer blends, and the
 * master blend.  It exposes stable work identifiers alongside cold [StateFlow] progress feeds so
 * the UI can observe conversions without dealing with WorkManager directly.
 *
 * Responsibilities:
 *  * translate enqueue requests into [OneTimeWorkRequest] objects with deterministic tags,
 *  * surface coarse [GifWorkProgress] updates from [WorkInfo] snapshots,
 *  * forward FFmpeg stdout/stderr payloads into [LogEntry] history capped at 50 items,
 *  * mirror completed GIF outputs into the app-private `filesDir/sources` and `filesDir/exports`
 *    directories so Scoped Storage compliant flows can access the artifacts, and
 *  * invalidate downstream previews whenever a new source replaces a layer.
 */
class GifProcessingCoordinator(
    private val appContext: Context,
    private val workManager: WorkManager = WorkManager.getInstance(appContext),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : Closeable {

    /** Wrapper returned for all enqueue operations so callers can track progress and ids. */
    data class WorkHandle(
        val workId: UUID,
        val progress: StateFlow<GifWorkProgress>
    )

    /** Result payload when persisting into MediaStore downloads. */
    data class SaveResult(
        val uri: Uri,
        val fileName: String
    )

    /** Result payload surfaced when preparing a share intent. */
    data class ShareResult(
        val uri: Uri,
        val mimeType: String,
        val displayName: String
    )

    private val coordinatorScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val workObservers = ConcurrentHashMap<UUID, Job>()
    private val trackedWork = ConcurrentHashMap<UUID, GifWorkTracker>()
    private val requestDescriptors = ConcurrentHashMap<UUID, WorkDescriptor>()

    private val sourcesDirectory: File = File(appContext.filesDir, "sources").apply { mkdirs() }
    private val exportsDirectory: File = File(appContext.filesDir, "exports").apply { mkdirs() }
    private val fileProviderAuthority: String = "${appContext.packageName}.fileprovider"

    private val _logEntries = MutableStateFlow<List<LogEntry>>(emptyList())
    val logEntries: StateFlow<List<LogEntry>> = _logEntries.asStateFlow()

    private val _streamOutputs = MutableStateFlow<Map<StreamId, Uri>>(emptyMap())
    val streamOutputs: StateFlow<Map<StreamId, Uri>> = _streamOutputs.asStateFlow()

    private val _layerBlendOutputs = MutableStateFlow<Map<LayerId, Uri>>(emptyMap())
    val layerBlendOutputs: StateFlow<Map<LayerId, Uri>> = _layerBlendOutputs.asStateFlow()

    private val _masterBlendOutput = MutableStateFlow<Uri?>(null)
    val masterBlendOutput: StateFlow<Uri?> = _masterBlendOutput.asStateFlow()

    /** Enqueues a render for the provided [GifTranscodeBlueprint]. */
    fun enqueueStreamRender(blueprint: GifTranscodeBlueprint): WorkHandle {
        FfmpegKitInitializer.ensureInitialized(appContext)

        val request = OneTimeWorkRequestBuilder<GifGenerationWorker>()
            .setInputData(blueprint.toWorkData())
            .addTag(TAG_STREAM_RENDER)
            .addTag(blueprint.streamId.toTag())
            .build()

        val tracker = GifWorkTracker(blueprint = blueprint, workId = request.id)
        val progressState = MutableStateFlow(tracker.latestProgress)
        trackedWork[request.id] = tracker
        requestDescriptors[request.id] = WorkDescriptor.StreamRender(blueprint, progressState)

        workManager.enqueue(request)
        observeWork(request.id, progressState)
        return WorkHandle(request.id, progressState.asStateFlow())
    }

    /** Enqueues a blend combining Stream A and B outputs for the given [layerId]. */
    fun enqueueLayerBlend(
        layerId: LayerId,
        primaryUri: Uri,
        secondaryUri: Uri,
        blendMode: BlendMode,
        blendOpacity: Float
    ): WorkHandle {
        FfmpegKitInitializer.ensureInitialized(appContext)

        val inputData = workDataOf(
            GifWorkDataKeys.KEY_INPUT_PRIMARY_URI to primaryUri.toString(),
            GifWorkDataKeys.KEY_INPUT_SECONDARY_URI to secondaryUri.toString(),
            GifWorkDataKeys.KEY_INPUT_BLEND_MODE to blendMode.name,
            GifWorkDataKeys.KEY_INPUT_BLEND_OPACITY to blendOpacity,
            GifWorkDataKeys.KEY_INPUT_LAYER_INDEX to layerId.index,
            GifWorkDataKeys.KEY_INPUT_STREAM_ID to StreamId.of(layerId, com.example.gifvision.StreamChannel.A).toWorkValue()
        )
        val request = OneTimeWorkRequestBuilder<GifBlendWorker>()
            .setInputData(inputData)
            .addTag(TAG_LAYER_BLEND)
            .addTag(layerId.toTag())
            .build()

        val blueprint = GifTranscodeBlueprint.sampleBlueprint(StreamId.of(layerId, com.example.gifvision.StreamChannel.A))
        val tracker = GifWorkTracker(blueprint = blueprint, workId = request.id)
        val progressState = MutableStateFlow(tracker.latestProgress)
        trackedWork[request.id] = tracker
        requestDescriptors[request.id] = WorkDescriptor.LayerBlend(layerId, progressState)

        workManager.enqueue(request)
        observeWork(request.id, progressState)
        return WorkHandle(request.id, progressState.asStateFlow())
    }

    /** Enqueues a master blend that merges both layer composites. */
    fun enqueueMasterBlend(
        primaryUri: Uri,
        secondaryUri: Uri,
        blendMode: BlendMode,
        blendOpacity: Float,
        masterLabel: String = DEFAULT_MASTER_LABEL
    ): WorkHandle {
        FfmpegKitInitializer.ensureInitialized(appContext)

        val inputData = workDataOf(
            GifWorkDataKeys.KEY_INPUT_PRIMARY_URI to primaryUri.toString(),
            GifWorkDataKeys.KEY_INPUT_SECONDARY_URI to secondaryUri.toString(),
            GifWorkDataKeys.KEY_INPUT_BLEND_MODE to blendMode.name,
            GifWorkDataKeys.KEY_INPUT_BLEND_OPACITY to blendOpacity,
            GifWorkDataKeys.KEY_INPUT_MASTER_LABEL to masterLabel
        )
        val request = OneTimeWorkRequestBuilder<MasterBlendWorker>()
            .setInputData(inputData)
            .addTag(TAG_MASTER_BLEND)
            .addTag(masterLabel)
            .build()

        val blueprint = GifTranscodeBlueprint.sampleBlueprint(StreamId.of(LayerId.Layer1, com.example.gifvision.StreamChannel.A))
        val tracker = GifWorkTracker(blueprint = blueprint, workId = request.id)
        val progressState = MutableStateFlow(tracker.latestProgress)
        trackedWork[request.id] = tracker
        requestDescriptors[request.id] = WorkDescriptor.MasterBlend(progressState, masterLabel)

        workManager.enqueue(request)
        observeWork(request.id, progressState)
        return WorkHandle(request.id, progressState.asStateFlow())
    }

    /** Cancels the WorkManager job associated with [workId] if it is still running. */
    fun cancel(workId: UUID) {
        workManager.cancelWorkById(workId)
        workObservers.remove(workId)?.cancel()
        requestDescriptors.remove(workId)
        trackedWork.remove(workId)
    }

    /**
     * Resets a layer to its default state when a new source clip is imported.
     * Stream previews, downstream layer blends, and the master blend cache are purged.
     */
    fun resetForNewSource(layerId: LayerId) {
        cancelOutstandingForLayer(layerId)
        _streamOutputs.update { current -> current.filterNot { it.key.layer == layerId } }
        _layerBlendOutputs.update { current -> current - layerId }
        _masterBlendOutput.value = null
    }

    override fun close() {
        workObservers.values.forEach(Job::cancel)
        workObservers.clear()
        coordinatorScope.cancel()
    }

    private fun observeWork(workId: UUID, progressState: MutableStateFlow<GifWorkProgress>) {
        val observerJob = coordinatorScope.launch {
            try {
                workManager.workInfoFlow(workId).collect { workInfo ->
                    val previous = progressState.value
                    val progress = workInfo.toGifWorkProgress(previous)
                    progressState.value = progress
                    trackedWork[workId] = trackedWork[workId]?.update(progress) ?: GifWorkTracker(
                        blueprint = GifTranscodeBlueprint.sampleBlueprint(),
                        workId = workId,
                        latestProgress = progress,
                        isComplete = workInfo.state.isFinished
                    )
                    handleWorkUpdate(workInfo)
                    if (workInfo.state.isFinished) {
                        return@collect
                    }
                }
            } catch (cancellation: CancellationException) {
                // Propagate cancellation silently; callers drive lifecycle explicitly.
            } finally {
                workObservers.remove(workId)
                requestDescriptors.remove(workId)
                trackedWork.remove(workId)
            }
        }
        workObservers[workId] = observerJob
    }

    private suspend fun handleWorkUpdate(workInfo: WorkInfo) {
        val descriptor = requestDescriptors[workInfo.id] ?: return
        val logs = extractLogs(workInfo)
        if (logs.isNotEmpty()) {
            recordLogs(logs)
        }

        if (!workInfo.state.isFinished) {
            return
        }

        val completionEntry = LogEntry(
            message = buildCompletionMessage(descriptor, workInfo.state),
            severity = when (workInfo.state) {
                WorkInfo.State.SUCCEEDED -> LogSeverity.INFO
                WorkInfo.State.FAILED -> LogSeverity.ERROR
                WorkInfo.State.CANCELLED -> LogSeverity.WARNING
                else -> LogSeverity.INFO
            },
            workId = workInfo.id
        )
        recordLogs(listOf(completionEntry))

        if (workInfo.state != WorkInfo.State.SUCCEEDED) {
            return
        }

        when (descriptor) {
            is WorkDescriptor.StreamRender -> handleStreamSuccess(descriptor, workInfo.outputData)
            is WorkDescriptor.LayerBlend -> handleLayerBlendSuccess(descriptor.layerId, workInfo.outputData)
            is WorkDescriptor.MasterBlend -> handleMasterBlendSuccess(descriptor.masterLabel, workInfo.outputData)
        }
    }

    private suspend fun handleStreamSuccess(
        descriptor: WorkDescriptor.StreamRender,
        outputData: Data
    ) {
        val uriToken = outputData.getString(GifWorkDataKeys.KEY_OUTPUT_GIF_URI) ?: return
        val streamId = descriptor.blueprint.streamId
        val destination = cacheFileFor(GifExportTarget.StreamPreview(streamId))
        copyUriToFile(Uri.parse(uriToken), destination)
        _streamOutputs.update { current -> current + (streamId to Uri.fromFile(destination)) }
    }

    private suspend fun handleLayerBlendSuccess(layerId: LayerId, outputData: Data) {
        val uriToken = outputData.getString(GifWorkDataKeys.KEY_OUTPUT_GIF_URI) ?: return
        val destination = cacheFileFor(GifExportTarget.LayerBlend(layerId))
        copyUriToFile(Uri.parse(uriToken), destination)
        _layerBlendOutputs.update { current -> current + (layerId to Uri.fromFile(destination)) }
    }

    private suspend fun handleMasterBlendSuccess(masterLabel: String, outputData: Data) {
        val uriToken = outputData.getString(GifWorkDataKeys.KEY_OUTPUT_GIF_URI) ?: return
        val destination = cacheFileFor(GifExportTarget.MasterBlend)
        copyUriToFile(Uri.parse(uriToken), destination)
        _masterBlendOutput.value = Uri.fromFile(destination)
    }

    suspend fun saveToDownloads(target: GifExportTarget, sourceUri: Uri): SaveResult {
        return withContext(ioDispatcher) {
            val resolver = appContext.contentResolver
            val displayName = target.mediaStoreFileName
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, GifExportTarget.MIME_TYPE_GIF)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + File.separator + target.downloadsRelativePath
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val downloadsCollection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val itemUri = resolver.insert(downloadsCollection, values)
                ?: error("Unable to create MediaStore entry for ${target.displayName}")

            try {
                resolver.openOutputStream(itemUri)?.use { output ->
                    openInputStream(sourceUri)?.use { input ->
                        input.copyTo(output)
                    } ?: error("Unable to open input stream for $sourceUri")
                } ?: error("Unable to open output stream for $itemUri")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val pendingUpdate = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    resolver.update(itemUri, pendingUpdate, null, null)
                }
            } catch (t: Throwable) {
                resolver.delete(itemUri, null, null)
                throw t
            }

            SaveResult(uri = itemUri, fileName = displayName)
        }
    }

    suspend fun prepareShare(target: GifExportTarget, sourceUri: Uri): ShareResult {
        val shareUri = when (sourceUri.scheme?.lowercase(Locale.US)) {
            null, ContentResolver.SCHEME_FILE -> {
                val file = when (sourceUri.scheme?.lowercase(Locale.US)) {
                    null -> File(sourceUri.toString())
                    else -> File(sourceUri.path.orEmpty())
                }
                if (!file.exists()) {
                    error("Cached GIF missing for ${target.displayName} at ${file.path}")
                }
                FileProvider.getUriForFile(appContext, fileProviderAuthority, file)
            }
            ContentResolver.SCHEME_CONTENT -> sourceUri
            "data" -> sourceUri
            else -> sourceUri
        }
        return ShareResult(
            uri = shareUri,
            mimeType = GifExportTarget.MIME_TYPE_GIF,
            displayName = target.displayName
        )
    }

    private suspend fun copyUriToFile(uri: Uri, destination: File) = withContext(ioDispatcher) {
        destination.parentFile?.mkdirs()
        openInputStream(uri).use { input ->
            FileOutputStream(destination).use { output ->
                input?.copyTo(output) ?: error("Unable to open input stream for $uri")
            }
        }
    }

    private fun openInputStream(uri: Uri): InputStream? {
        return when (uri.scheme?.lowercase(Locale.US)) {
            null, "file" -> File(uri.path.orEmpty()).takeIf(File::exists)?.inputStream()
            "data" -> decodeDataUri(uri)
            else -> appContext.contentResolver.openInputStream(uri)
        }
    }

    private fun decodeDataUri(uri: Uri): InputStream? {
        val data = uri.schemeSpecificPart ?: return null
        val base64Index = data.indexOf("base64,")
        val encoded = if (base64Index >= 0) {
            data.substring(base64Index + "base64,".length)
        } else {
            data
        }
        if (encoded.isEmpty()) return ByteArrayInputStream(ByteArray(0))
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        return ByteArrayInputStream(bytes)
    }

    private fun buildCompletionMessage(descriptor: WorkDescriptor, state: WorkInfo.State): String {
        val taskLabel = when (descriptor) {
            is WorkDescriptor.StreamRender -> descriptor.blueprint.streamId.displayName
            is WorkDescriptor.LayerBlend -> "${descriptor.layerId.displayName} blend"
            is WorkDescriptor.MasterBlend -> descriptor.masterLabel
        }
        return "${taskLabel} ${state.name.lowercase(Locale.US)}"
    }

    private fun extractLogs(workInfo: WorkInfo): List<LogEntry> {
        val raw = workInfo.progress.getStringArray(GifWorkDataKeys.KEY_OUTPUT_LOGS)
            ?: workInfo.outputData.getStringArray(GifWorkDataKeys.KEY_OUTPUT_LOGS)
        val logs = parseLogEntries(raw)
        return logs
    }

    private fun recordLogs(entries: List<LogEntry>) {
        if (entries.isEmpty()) return
        _logEntries.update { existing ->
            val merged = existing + entries
            if (merged.size <= LOG_HISTORY_LIMIT) merged else merged.takeLast(LOG_HISTORY_LIMIT)
        }
    }

    private fun cancelOutstandingForLayer(layerId: LayerId) {
        requestDescriptors.entries
            .filter { (_, descriptor) -> descriptor.affectsLayer(layerId) }
            .map { it.key }
            .forEach { workId -> cancel(workId) }
    }

    private fun cacheFileFor(target: GifExportTarget): File {
        val directory = when (target) {
            is GifExportTarget.StreamPreview -> sourcesDirectory
            is GifExportTarget.LayerBlend -> exportsDirectory
            GifExportTarget.MasterBlend -> exportsDirectory
        }
        return File(directory, target.cacheFileName)
    }

    private sealed interface WorkDescriptor {
        fun affectsLayer(layerId: LayerId): Boolean = false

        data class StreamRender(
            val blueprint: GifTranscodeBlueprint,
            val progress: MutableStateFlow<GifWorkProgress>
        ) : WorkDescriptor {
            override fun affectsLayer(layerId: LayerId): Boolean = blueprint.streamId.layer == layerId
        }

        data class LayerBlend(
            val layerId: LayerId,
            val progress: MutableStateFlow<GifWorkProgress>
        ) : WorkDescriptor {
            override fun affectsLayer(layerId: LayerId): Boolean = this.layerId == layerId
        }

        data class MasterBlend(
            val progress: MutableStateFlow<GifWorkProgress>,
            val masterLabel: String
        ) : WorkDescriptor
    }

    companion object {
        private const val TAG_STREAM_RENDER = "gifvision:stream"
        private const val TAG_LAYER_BLEND = "gifvision:layer_blend"
        private const val TAG_MASTER_BLEND = "gifvision:master_blend"
        private const val LOG_HISTORY_LIMIT = 50
        private const val DEFAULT_MASTER_LABEL = "Master Blend"
    }
}

private fun WorkManager.workInfoFlow(workId: UUID): Flow<WorkInfo> = callbackFlow {
    val liveData = getWorkInfoByIdLiveData(workId)
    val observer = Observer<WorkInfo> { workInfo ->
        trySend(workInfo)
    }
    liveData.observeForever(observer)
    awaitClose { liveData.removeObserver(observer) }
}

/**
 * Maps a [WorkInfo] snapshot into a coarse [GifWorkProgress] update.
 *
 * The helper inspects the WorkManager progress [Data] if available; otherwise it infers
 * reasonable defaults from the job state while keeping the progress percentage monotonic.
 */
@VisibleForTesting
fun WorkInfo.toGifWorkProgress(previous: GifWorkProgress): GifWorkProgress {
    val progressData = runCatching { GifWorkProgress.fromData(progress) }.getOrNull()
    if (progressData != null) {
        return progressData
    }

    val stage = when (state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> GifWorkProgress.Stage.QUEUED
        WorkInfo.State.RUNNING -> when (previous.stage) {
            GifWorkProgress.Stage.QUEUED -> GifWorkProgress.Stage.PREPARING
            else -> previous.stage
        }
        WorkInfo.State.SUCCEEDED -> GifWorkProgress.Stage.COMPLETED
        WorkInfo.State.FAILED -> previous.stage
        WorkInfo.State.CANCELLED -> previous.stage
    }

    val percent = when (state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> previous.percent.coerceAtMost(5)
        WorkInfo.State.RUNNING -> previous.percent.coerceAtLeast(10)
        WorkInfo.State.SUCCEEDED -> 100
        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> previous.percent
    }

    return GifWorkProgress(
        workId = id,
        percent = percent,
        stage = stage
    )
}

private fun LayerId.toTag(): String = "layer:${index}"

private fun StreamId.toTag(): String = "stream:${layer.index}:${channel.name.lowercase(Locale.US)}"
