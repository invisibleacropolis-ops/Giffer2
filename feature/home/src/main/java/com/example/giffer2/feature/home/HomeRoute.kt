package com.example.giffer2.feature.home

import android.content.ContentValues
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
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
import com.example.giffer2.core.ffmpeg.FfmpegGifConverter
import com.example.giffer2.core.model.GifConversionLogEntry
import com.example.giffer2.core.model.GifConversionResult
import com.example.giffer2.core.model.GifConversionState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Top-level entry point for the home screen. The composable orchestrates user interaction, FFmpeg
 * conversions, and persistence while delegating heavy work to the [FfmpegGifConverter].
 */
@Composable
fun HomeRoute(modifier: Modifier = Modifier) {
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var conversionState by remember { mutableStateOf<GifConversionState>(GifConversionState.Idle) }
    var saveStatus by remember { mutableStateOf("") }
    var previewErrorMessage by remember { mutableStateOf<String?>(null) }
    var isLogExpanded by remember { mutableStateOf(false) }
    var gifPath by remember { mutableStateOf<String?>(null) }
    val logMessages = remember { mutableStateListOf<GifConversionLogEntry>() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val selectVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            videoUri = uri
            conversionState = GifConversionState.Idle
            saveStatus = ""
            previewErrorMessage = null
            gifPath = null
            logMessages.clear()
            isLogExpanded = false
        }
    )

    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }

    val conversionStatusMessage = when (val state = conversionState) {
        GifConversionState.Idle -> if (gifPath != null) "GIF ready!" else ""
        GifConversionState.Running -> "Converting..."
        is GifConversionState.Completed -> "GIF ready!"
        is GifConversionState.Failed -> "Error: ${state.errorMessage ?: "Unknown error"}"
    }
    val isLoading = conversionState is GifConversionState.Running

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
                    conversionState = GifConversionState.Idle
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
            Button(
                onClick = {
                    conversionState = GifConversionState.Running
                    gifPath = null
                    saveStatus = ""
                    logMessages.clear()
                    isLogExpanded = true
                    FfmpegGifConverter.convertVideoToGif(
                        context = context,
                        videoUri = uri,
                        onLog = { entry ->
                            if (entry.message.isNotBlank()) {
                                logMessages.add(entry)
                                while (logMessages.size > 500) {
                                    logMessages.removeAt(0)
                                }
                            }
                        }
                    ) { result ->
                        handleConversionResult(result, onStateUpdate = { conversionState = it })
                        gifPath = result.outputPath
                    }
                },
                enabled = !isLoading
            ) {
                Text("Convert to GIF")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when {
            isLoading -> {
                CircularProgressIndicator()
                Text(conversionStatusMessage)
            }
            gifPath != null -> {
                Text(conversionStatusMessage)
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
            }
            conversionStatusMessage.isNotBlank() -> {
                Text(conversionStatusMessage)
            }
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
                        logMessages.forEach { logEntry ->
                            Text(logEntry.message)
                        }
                    }
                }
            }
        }
    }
}

private fun handleConversionResult(
    result: GifConversionResult,
    onStateUpdate: (GifConversionState) -> Unit
) {
    val newState = if (result.outputPath != null) {
        GifConversionState.Completed(result.outputPath)
    } else {
        GifConversionState.Failed(result.errorMessage)
    }
    onStateUpdate(newState)
}

@Composable
private fun VideoPreview(
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
private fun GifPreview(
    gifPath: String,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier
) {
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

private fun saveGifToGallery(context: Context, gifFile: File): Result<Uri> {
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

private fun getFileName(context: Context, uri: Uri): String? {
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

@Preview(showBackground = true)
@Composable
private fun HomeRoutePreview() {
    MaterialTheme {
        HomeRoute()
    }
}
