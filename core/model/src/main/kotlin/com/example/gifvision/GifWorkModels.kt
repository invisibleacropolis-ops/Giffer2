package com.example.gifvision

import androidx.work.Data
import java.util.UUID

/** Severity levels emitted by FFmpeg and surfaced to the UI. */
enum class LogSeverity { INFO, WARNING, ERROR }

/** Structured log entry forwarded to the GifVision log console. */
data class LogEntry(
    val message: String,
    val severity: LogSeverity = LogSeverity.INFO,
    val timestampMillis: Long = System.currentTimeMillis(),
    val workId: UUID? = null
)

/**
 * Snapshot of an end-to-end GIF render request. The blueprint keeps the render inputs together so
 * scheduling, logging, and retry logic can reuse a single identifier.
 */
data class GifTranscodeBlueprint(
    val blueprintId: UUID,
    val streamId: StreamId,
    val source: GifReference,
    val effectSettings: EffectSettings,
    val blendMode: BlendMode = BlendMode.Normal,
    val blendOpacity: Float = 1f,
    val requestedAtMillis: Long = System.currentTimeMillis()
) {
    fun toWorkData(): Data {
        val builder = Data.Builder()
            .putString(KEY_BLUEPRINT_ID, blueprintId.toString())
            .putInt(KEY_STREAM, streamId.toWorkValue())
            .putString(KEY_BLEND_MODE, blendMode.name)
            .putFloat(KEY_BLEND_OPACITY, blendOpacity)
            .putLong(KEY_REQUESTED_AT, requestedAtMillis)
            .putAll(effectSettings.toWorkData())

        when (source) {
            is GifReference.FileUri -> builder
                .putString(KEY_SOURCE_TYPE, SOURCE_FILE_URI)
                .putString(KEY_SOURCE_VALUE, source.uri)
            is GifReference.ContentUri -> builder
                .putString(KEY_SOURCE_TYPE, SOURCE_CONTENT_URI)
                .putString(KEY_SOURCE_VALUE, source.uri)
            is GifReference.InMemory -> builder
                .putString(KEY_SOURCE_TYPE, SOURCE_IN_MEMORY)
                .putByteArray(KEY_SOURCE_BYTES, source.bytes)
                .putString(KEY_SOURCE_NAME_HINT, source.fileNameHint)
        }

        return builder.build()
    }

    companion object {
        private const val KEY_BLUEPRINT_ID = "blueprint.id"
        private const val KEY_STREAM = "blueprint.stream"
        private const val KEY_SOURCE_TYPE = "blueprint.source.type"
        private const val KEY_SOURCE_VALUE = "blueprint.source.value"
        private const val KEY_SOURCE_BYTES = "blueprint.source.bytes"
        private const val KEY_SOURCE_NAME_HINT = "blueprint.source.name"
        private const val KEY_BLEND_MODE = "blueprint.blend.mode"
        private const val KEY_BLEND_OPACITY = "blueprint.blend.opacity"
        private const val KEY_REQUESTED_AT = "blueprint.requestedAt"

        private const val SOURCE_FILE_URI = "file_uri"
        private const val SOURCE_CONTENT_URI = "content_uri"
        private const val SOURCE_IN_MEMORY = "in_memory"

        fun fromWorkData(data: Data): GifTranscodeBlueprint {
            val blueprintId = UUID.fromString(data.getString(KEY_BLUEPRINT_ID))
            val stream = StreamId.fromWorkValue(data.getInt(KEY_STREAM, 0))
            val effect = EffectSettings.fromWorkData(data)
            val blendMode = BlendMode.valueOf(data.getString(KEY_BLEND_MODE) ?: BlendMode.Normal.name)
            val opacity = data.getFloat(KEY_BLEND_OPACITY, 1f)
            val requestedAt = data.getLong(KEY_REQUESTED_AT, System.currentTimeMillis())
            val source = when (data.getString(KEY_SOURCE_TYPE)) {
                SOURCE_FILE_URI -> GifReference.FileUri(data.getString(KEY_SOURCE_VALUE)!!)
                SOURCE_CONTENT_URI -> GifReference.ContentUri(data.getString(KEY_SOURCE_VALUE)!!)
                SOURCE_IN_MEMORY -> GifReference.InMemory(
                    bytes = data.getByteArray(KEY_SOURCE_BYTES) ?: ByteArray(0),
                    fileNameHint = data.getString(KEY_SOURCE_NAME_HINT)
                )
                else -> error("Missing source type for blueprint ${blueprintId}")
            }

            return GifTranscodeBlueprint(
                blueprintId = blueprintId,
                streamId = stream,
                source = source,
                effectSettings = effect,
                blendMode = blendMode,
                blendOpacity = opacity,
                requestedAtMillis = requestedAt
            )
        }

        fun sampleBlueprint(streamId: StreamId = StreamId.of(LayerId.Layer1, StreamChannel.A)): GifTranscodeBlueprint {
            val effectDefaults = EffectSettings.defaultSettings(streamId)
            val inMemory = GifReference.InMemory(byteArrayOf(), fileNameHint = "sample.gif")
            return GifTranscodeBlueprint(
                blueprintId = UUID.randomUUID(),
                streamId = streamId,
                source = inMemory,
                effectSettings = effectDefaults
            )
        }
    }
}

/**
 * Models the coarse progress reported by workers so progress bars can animate consistently between
 * queueing and completion.
 */
data class GifWorkProgress(
    val workId: UUID,
    val percent: Int,
    val stage: Stage
) {
    init {
        require(percent in 0..100) { "Progress percent must be between 0 and 100." }
    }

    fun toData(): Data = Data.Builder()
        .putString(KEY_WORK_ID, workId.toString())
        .putInt(KEY_PERCENT, percent)
        .putString(KEY_STAGE, stage.name)
        .build()

    enum class Stage { QUEUED, PREPARING, PALETTE, RENDERING, BLENDING, COMPLETED }

    companion object {
        private const val KEY_WORK_ID = "progress.workId"
        private const val KEY_PERCENT = "progress.percent"
        private const val KEY_STAGE = "progress.stage"

        fun fromData(data: Data): GifWorkProgress {
            val workId = data.getString(KEY_WORK_ID)?.let(UUID::fromString)
                ?: error("Work id missing from progress payload")
            val percent = data.getInt(KEY_PERCENT, 0)
            val stage = data.getString(KEY_STAGE)?.let { Stage.valueOf(it) } ?: Stage.QUEUED
            return GifWorkProgress(workId, percent, stage)
        }
    }
}

/** Tracks the relationship between a blueprint, a WorkManager id, and the latest progress. */
data class GifWorkTracker(
    val blueprint: GifTranscodeBlueprint,
    val workId: UUID,
    val latestProgress: GifWorkProgress = GifWorkProgress(workId, percent = 0, stage = GifWorkProgress.Stage.QUEUED),
    val isComplete: Boolean = false
) {
    fun update(progress: GifWorkProgress): GifWorkTracker = copy(
        latestProgress = progress,
        isComplete = progress.stage == GifWorkProgress.Stage.COMPLETED || progress.percent == 100
    )
}

