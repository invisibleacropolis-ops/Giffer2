package com.example.giffer2.core.ffmpeg

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import com.example.giffer2.core.model.GifConversionLogEntry
import com.example.giffer2.core.model.GifConversionResult
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Wraps the FFmpegKit command execution that powers video-to-GIF conversion. The converter keeps
 * Android-specific file handling and FFmpeg command building out of the UI layer so that Compose
 * screens can focus purely on state rendering.
 */
object FfmpegGifConverter {
    private val conversionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Launches a conversion task. All callbacks are delivered on the main thread.
     */
    fun convertVideoToGif(
        context: Context,
        videoUri: Uri,
        onLog: (GifConversionLogEntry) -> Unit,
        onResult: (GifConversionResult) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())

        fun postLog(message: String) {
            mainHandler.post { onLog(GifConversionLogEntry(message)) }
            Log.d("FfmpegGifConverter", message)
        }

        fun postResult(path: String?, error: String?) {
            mainHandler.post { onResult(GifConversionResult(path, error)) }
        }

        conversionScope.launch {
            postLog("Validating selected video before conversion...")
            val metadata = extractVideoMetadata(context, videoUri).getOrElse { throwable ->
                postLog(
                    "Video validation failed: ${throwable.localizedMessage ?: throwable.javaClass.simpleName}"
                )
                postResult(null, "We couldn't read the selected video. Please choose a different file.")
                return@launch
            }

            postLog(
                "Video metadata - name: ${metadata.displayName ?: "unknown"}, " +
                    "size: ${metadata.sizeBytes?.let { formatFileSize(it) } ?: "unknown"}, " +
                    "duration: ${metadata.durationMillis?.let { formatDuration(it) } ?: "unknown"}, " +
                    "dimensions: ${metadata.width ?: "?"}x${metadata.height ?: "?"}"
            )

            val tempVideoFile = createTempFileFromUri(context, videoUri, "input_video").getOrElse { throwable ->
                postLog(
                    "Failed to cache video locally: ${throwable.localizedMessage ?: throwable.javaClass.simpleName}"
                )
                postResult(null, "Unable to prepare the selected video for conversion. Please try again.")
                return@launch
            }

            postLog(
                "Temporary input copied to: ${tempVideoFile.absolutePath} " +
                    "(${formatFileSize(tempVideoFile.length())})"
            )

            val outputDir = File(context.cacheDir, "gifs")
            if (!outputDir.exists()) {
                if (outputDir.mkdirs()) {
                    postLog("Created GIF cache directory at ${outputDir.absolutePath}")
                } else {
                    postLog("Failed to create GIF cache directory at ${outputDir.absolutePath}")
                    tempVideoFile.delete()
                    postResult(null, "Unable to prepare GIF output directory.")
                    return@launch
                }
            }

            val outputGifFile = File(outputDir, "output_${System.currentTimeMillis()}.gif")
            val outputPath = outputGifFile.absolutePath

            val command = "-i \"${tempVideoFile.absolutePath}\" -vf \"fps=10,scale=320:-1:flags=lanczos\" -c:v gif -y \"$outputPath\""

            postLog("GIF will be written to: $outputPath")
            postLog("Executing command: $command")

            FFmpegKit.executeAsync(
                command,
                { session ->
                    val returnCode = session.returnCode
                    postLog("FFmpeg process exited with state ${session.state} and rc $returnCode")

                    tempVideoFile.delete()

                    if (ReturnCode.isSuccess(returnCode)) {
                        postLog("GIF conversion successful: $outputPath")
                        postResult(outputPath, null)
                    } else if (ReturnCode.isCancel(returnCode)) {
                        postLog("GIF conversion cancelled.")
                        postResult(null, "Conversion cancelled.")
                    } else {
                        postLog("GIF conversion failed. RC: $returnCode. Error: ${session.failStackTrace}")
                        postResult(null, "Conversion failed (RC: $returnCode). Check logs.")
                    }
                },
                { log ->
                    log.message?.let { message ->
                        if (message.isNotBlank()) {
                            postLog(message)
                        }
                    }
                },
                { statistics: Statistics ->
                    postLog(
                        "Statistics - time: ${statistics.time}, bitrate: ${statistics.bitrate}, " +
                            "speed: ${statistics.speed}, frame: ${statistics.videoFrameNumber}"
                    )
                }
            )
        }
    }

    private fun extractVideoMetadata(context: Context, uri: Uri): Result<VideoMetadata> {
        return runCatching {
            var displayName: String? = null
            var sizeBytes: Long? = null
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        displayName = cursor.getString(nameIndex)
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                        sizeBytes = cursor.getLong(sizeIndex)
                    }
                }
            }

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()

                if (duration == null || duration <= 0) {
                    throw IllegalArgumentException("The selected file is missing required video duration metadata.")
                }

                VideoMetadata(
                    displayName = displayName,
                    sizeBytes = sizeBytes,
                    durationMillis = duration,
                    width = width,
                    height = height
                )
            } finally {
                retriever.release()
            }
        }
    }

    private fun createTempFileFromUri(context: Context, uri: Uri, prefix: String): Result<File> {
        return runCatching {
            val extension = getFileExtensionFromUri(context, uri) ?: "tmp"
            val safeExtension = if (extension.isBlank() || extension.length > 5) "tmp" else extension
            val tempFile = File.createTempFile("${prefix}_", ".$safeExtension", context.cacheDir)

            val totalBytes = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(tempFile).use { fos ->
                    val copied = inputStream.copyTo(fos)
                    try {
                        fos.fd.sync()
                    } catch (ignored: IOException) {
                        // sync may fail on some filesystems; swallow but retain copied bytes count
                    }
                    copied
                }
            } ?: throw IOException("Unable to open input stream for URI: $uri")

            if (totalBytes <= 0L) {
                tempFile.delete()
                throw IOException("No bytes were read from the provided URI.")
            }

            if (!tempFile.exists() || tempFile.length() <= 0L) {
                val existingLength = tempFile.length()
                tempFile.delete()
                throw IOException("Cached video file is empty (length=$existingLength).")
            }

            tempFile
        }.onFailure { throwable ->
            Log.e("CreateTempFile", "Error creating temp file from URI: $uri", throwable)
        }
    }

    private fun getFileExtensionFromUri(context: Context, uri: Uri): String? {
        var extension: String? = null
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        val displayName = it.getString(nameIndex)
                        if (displayName.contains('.')) {
                            extension = displayName.substringAfterLast('.', "")
                        }
                    }
                }
            }
        }
        if (extension == null) {
            uri.path?.let { path ->
                if (path.contains('.')) {
                    extension = path.substringAfterLast('.', "")
                }
            }
        }
        if (extension.isNullOrEmpty()) {
            val mimeType = context.contentResolver.getType(uri)
            mimeType?.let {
                extension = it.substringAfterLast('/', "")
            }
        }
        return extension?.lowercase(Locale.ROOT)?.replace("[^a-zA-Z0-9]".toRegex(), "")
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }

    private fun formatDuration(durationMillis: Long): String {
        if (durationMillis <= 0) return "0 s"
        val seconds = durationMillis / 1000.0
        return if (seconds >= 60) {
            String.format(Locale.US, "%.1f min", seconds / 60.0)
        } else {
            String.format(Locale.US, "%.1f s", seconds)
        }
    }

    private data class VideoMetadata(
        val displayName: String?,
        val sizeBytes: Long?,
        val durationMillis: Long?,
        val width: Int?,
        val height: Int?
    )
}
