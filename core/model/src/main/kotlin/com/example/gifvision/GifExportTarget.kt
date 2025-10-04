package com.example.gifvision

import java.util.Locale

/**
 * Describes a GIF artifact that can be exported or shared with external apps.
 *
 * The target centralizes deterministic naming so callers can use the same
 * vocabulary when persisting into app-private caches or MediaStore.
 */
sealed interface GifExportTarget {
    /** Human readable label surfaced in UI copy and logging. */
    val displayName: String

    /** File name applied to cached outputs within the app sandbox. */
    val cacheFileName: String

    /**
     * File name persisted to MediaStore. The suffix always includes the
     * ".gif" extension for downstream apps to infer the MIME type.
     */
    val mediaStoreFileName: String

    /**
     * Downloads relative path used when inserting into MediaStore. The same
     * subdirectory is reused for all exports so users can find the outputs in
     * one folder.
     */
    val downloadsRelativePath: String

    companion object {
        const val MIME_TYPE_GIF: String = "image/gif"
        const val DOWNLOADS_SUBDIRECTORY: String = "GifVision"
    }

    /** Stream level preview generated from FFmpeg rendering. */
    data class StreamPreview(val streamId: StreamId) : GifExportTarget {
        override val displayName: String = "${streamId.displayName} preview"
        override val cacheFileName: String =
            "layer${streamId.layer.index}_stream_${streamId.channel.name.lowercase(Locale.US)}.gif"
        override val mediaStoreFileName: String =
            "layer${streamId.layer.index}_${streamId.channel.name.lowercase(Locale.US)}_preview.gif"
        override val downloadsRelativePath: String = DOWNLOADS_SUBDIRECTORY
    }

    /** Layer blend produced after combining the A & B streams. */
    data class LayerBlend(val layerId: LayerId) : GifExportTarget {
        override val displayName: String = "${layerId.displayName} blend"
        override val cacheFileName: String = "layer${layerId.index}_blend.gif"
        override val mediaStoreFileName: String = "layer${layerId.index}_blend.gif"
        override val downloadsRelativePath: String = DOWNLOADS_SUBDIRECTORY
    }

    /** Final master blend that merges both layer composites. */
    data object MasterBlend : GifExportTarget {
        override val displayName: String = "Master blend"
        override val cacheFileName: String = "master_blend.gif"
        override val mediaStoreFileName: String = "master_blend.gif"
        override val downloadsRelativePath: String = DOWNLOADS_SUBDIRECTORY
    }
}
