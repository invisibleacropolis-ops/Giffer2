package com.example.giffer2

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.ImageLoader
import coil.compose.rememberAsyncImagePainter
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import com.example.giffer2.ui.theme.Giffer2Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("Giffer2App", "MainActivity onCreate called") // Added for debugging
        enableEdgeToEdge()
        setContent {
            Giffer2Theme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    VideoToGifApp(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun VideoToGifApp(modifier: Modifier = Modifier) {
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var gifPath by remember { mutableStateOf<String?>(null) }
    var conversionStatus by remember { mutableStateOf("") }
    var saveStatus by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val logMessages = remember { mutableStateListOf<String>() }
    var isLogExpanded by remember { mutableStateOf(false) }
    var previewErrorMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val selectVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            videoUri = uri
            gifPath = null // Reset previous GIF path
            conversionStatus = ""
            saveStatus = ""
            logMessages.clear()
            isLogExpanded = false
            previewErrorMessage = null
        }
    )

    // ImageLoader for Coil with GIF support
    val imageLoader = ImageLoader.Builder(context)
        .components {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .build()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(onClick = { selectVideoLauncher.launch("video/*") }) {
            Text("Select Video")
        }

        videoUri?.let { uri ->
            Text("Selected: ${getFileName(context, uri) ?: "Unknown file"}")
            Spacer(modifier = Modifier.height(16.dp))
            VideoPreview(
                uri = uri,
                onPlaybackStart = { previewErrorMessage = null },
                onPlaybackError = { message ->
                    previewErrorMessage = message
                    conversionStatus = ""
                }
            )
            previewErrorMessage?.let { errorMessage ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                isLoading = true
                conversionStatus = "Converting..."
                gifPath = null
                saveStatus = ""
                logMessages.clear()
                isLogExpanded = true
                convertVideoToGif(
                    context = context,
                    videoUri = uri,
                    onLog = { message ->
                        if (message.isNotBlank()) {
                            logMessages.add(message)
                            while (logMessages.size > 500) {
                                logMessages.removeAt(0)
                            }
                        }
                    }
                ) { outputPath, error ->
                    isLoading = false
                    if (outputPath != null) {
                        gifPath = outputPath
                        conversionStatus = "GIF ready!"
                    } else {
                        conversionStatus = "Error: ${error ?: "Unknown error"}"
                    }
                }
            }, enabled = !isLoading && videoUri != null) {
                Text("Convert to GIF")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            CircularProgressIndicator()
            Text(conversionStatus)
        } else if (gifPath != null) {
            Text(conversionStatus)
            Spacer(modifier = Modifier.height(8.dp))
            GifPreview(gifPath = gifPath!!, imageLoader = imageLoader)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                val file = File(gifPath!!)
                if (!file.exists()) {
                    saveStatus = "GIF file missing."
                    return@Button
                }
                scope.launch {
                    saveStatus = "Saving..."
                    val result = withContext(Dispatchers.IO) {
                        saveGifToGallery(context, file)
                    }
                    saveStatus = result.fold(
                        onSuccess = { savedUri -> "GIF saved to gallery. Location: $savedUri" },
                        onFailure = { throwable ->
                            Log.e("SaveGif", "Failed to save GIF", throwable)
                            "Failed to save GIF: ${throwable.message ?: "Unknown error"}"
                        }
                    )
                }
            }) {
                Text("Save GIF")
            }
            if (saveStatus.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(saveStatus)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Temporary file: $gifPath")
        } else {
            Text(conversionStatus)
        }

        if (logMessages.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { isLogExpanded = !isLogExpanded }) {
                Text(if (isLogExpanded) "Hide FFmpeg Logs" else "Show FFmpeg Logs")
            }
            if (isLogExpanded) {
                val logScrollState = rememberScrollState()
                LaunchedEffect(logMessages.size) {
                    logScrollState.animateScrollTo(logScrollState.maxValue)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 150.dp, max = 300.dp),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 2.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(logScrollState)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        logMessages.forEach { logLine ->
                            Text(logLine)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VideoPreview(
    uri: Uri,
    modifier: Modifier = Modifier,
    onPlaybackStart: () -> Unit,
    onPlaybackError: (String) -> Unit
) {
    val videoViewHolder = remember { mutableStateOf<VideoView?>(null) }
    val hasReportedError = remember(uri) { mutableStateOf(false) }
    val hasReportedStart = remember(uri) { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { videoViewHolder.value?.stopPlayback() }
    }

    fun handlePlaybackStart(mediaPlayer: MediaPlayer, view: VideoView) {
        mediaPlayer.isLooping = true
        if (!hasReportedStart.value) {
            hasReportedStart.value = true
            hasReportedError.value = false
            onPlaybackStart()
        }
        view.start()
        view.seekTo(1)
    }

    fun reportError(message: String): Boolean {
        if (!hasReportedError.value) {
            hasReportedError.value = true
            hasReportedStart.value = false
            videoViewHolder.value?.stopPlayback()
            onPlaybackError(message)
        }
        return true
    }

    fun safeSetVideoUri(view: VideoView) {
        try {
            view.tag = uri.toString()
            view.setVideoURI(uri)
        } catch (exception: Exception) {
            val readableMessage = exception.localizedMessage ?: exception.javaClass.simpleName
            reportError("Unable to preview the selected video: $readableMessage")
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                VideoView(context).apply {
                    val controller = MediaController(context)
                    controller.setAnchorView(this)
                    setMediaController(controller)
                    setOnPreparedListener { mediaPlayer ->
                        handlePlaybackStart(mediaPlayer, this)
                    }
                    setOnErrorListener { _, what, extra ->
                        reportError("Unable to preview the selected video (MediaPlayer error $what/$extra).")
                    }
                    safeSetVideoUri(this)
                    videoViewHolder.value = this
                }
            },
            update = { view ->
                videoViewHolder.value = view
                view.setOnPreparedListener { mediaPlayer ->
                    handlePlaybackStart(mediaPlayer, view)
                }
                view.setOnErrorListener { _, what, extra ->
                    reportError("Unable to preview the selected video (MediaPlayer error $what/$extra).")
                }
                if (view.tag != uri.toString()) {
                    hasReportedStart.value = false
                    hasReportedError.value = false
                    safeSetVideoUri(view)
                } else if (!view.isPlaying && !hasReportedError.value) {
                    view.start()
                }
            }
        )
    }
}

@Composable
fun GifPreview(gifPath: String, imageLoader: ImageLoader, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = rememberAsyncImagePainter(File(gifPath), imageLoader),
            contentDescription = "Generated GIF preview",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    }
}

private val conversionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

data class VideoMetadata(
    val displayName: String?,
    val sizeBytes: Long?,
    val durationMillis: Long?,
    val width: Int?,
    val height: Int?
)

fun extractVideoMetadata(context: Context, uri: Uri): Result<VideoMetadata> {
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

fun formatFileSize(bytes: Long): String {
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

fun formatDuration(durationMillis: Long): String {
    if (durationMillis <= 0) return "0 s"
    val seconds = durationMillis / 1000.0
    return if (seconds >= 60) {
        String.format(Locale.US, "%.1f min", seconds / 60.0)
    } else {
        String.format(Locale.US, "%.1f s", seconds)
    }
}

fun saveGifToGallery(context: Context, gifFile: File): Result<Uri> {
    return runCatching {
        if (!gifFile.exists() || !gifFile.canRead()) {
            throw IllegalArgumentException("GIF file is not accessible.")
        }

        val resolver = context.contentResolver
        val displayName = "GIF_${System.currentTimeMillis()}"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$displayName.gif")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/gif")
            put(MediaStore.MediaColumns.SIZE, gifFile.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${android.os.Environment.DIRECTORY_PICTURES}/Giffer"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val savedUri = resolver.insert(collectionUri, contentValues)
            ?: throw IllegalStateException("Unable to create MediaStore entry.")

        resolver.openOutputStream(savedUri)?.use { outputStream ->
            gifFile.inputStream().use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        } ?: throw IllegalStateException("Unable to open output stream for GIF.")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pendingUpdate = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(savedUri, pendingUpdate, null, null)
        }

        savedUri
    }
}

fun getFileName(context: Context, uri: Uri): String? {
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                     return it.getString(nameIndex)
                }
            }
        }
    }
    return uri.path?.substringAfterLast('/')
}

fun convertVideoToGif(
    context: Context,
    videoUri: Uri,
    onLog: (String) -> Unit,
    callback: (String?, String?) -> Unit
) {
    val mainHandler = Handler(Looper.getMainLooper())

    fun postLog(message: String) {
        mainHandler.post { onLog(message) }
        Log.d("FFmpegKit", message)
    }

    fun postResult(path: String?, error: String?) {
        mainHandler.post { callback(path, error) }
    }

    conversionScope.launch {
        postLog("Validating selected video before conversion...")
        val metadata = extractVideoMetadata(context, videoUri).getOrElse { throwable ->
            postLog("Video validation failed: ${throwable.localizedMessage ?: throwable.javaClass.simpleName}")
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
            postLog("Failed to cache video locally: ${throwable.localizedMessage ?: throwable.javaClass.simpleName}")
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

fun createTempFileFromUri(context: Context, uri: Uri, prefix: String): Result<File> {
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

fun getFileExtensionFromUri(context: Context, uri: Uri): String? {
    var extension: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    val displayName = it.getString(nameIndex)
                    if (displayName.contains(".")) {
                        extension = displayName.substringAfterLast('.', "")
                    }
                }
            }
        }
    }
    if (extension == null) {
        uri.path?.let { path ->
            if (path.contains(".")) {
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
    return extension?.lowercase()?.replace("[^a-zA-Z0-9]".toRegex(), "") // Sanitize extension
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    Giffer2Theme {
        VideoToGifApp()
    }
}
