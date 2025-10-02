package com.example.giffer2

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.ImageLoader
import coil.compose.rememberAsyncImagePainter
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import com.example.giffer2.ui.theme.Giffer2Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import android.os.Build
import android.content.ContentValues
import android.provider.MediaStore
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.ui.viewinterop.AndroidView
import android.os.Handler
import android.os.Looper

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            VideoPreview(uri = uri)
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
fun VideoPreview(uri: Uri, modifier: Modifier = Modifier) {
    val videoViewHolder = remember { mutableStateOf<VideoView?>(null) }

    DisposableEffect(Unit) {
        onDispose { videoViewHolder.value?.stopPlayback() }
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
                    tag = uri.toString()
                    setVideoURI(uri)
                    setOnPreparedListener { mediaPlayer ->
                        mediaPlayer.isLooping = true
                        start()
                        seekTo(1)
                    }
                    videoViewHolder.value = this
                }
            },
            update = { view ->
                videoViewHolder.value = view
                if (view.tag != uri.toString()) {
                    view.tag = uri.toString()
                    view.setVideoURI(uri)
                }
                view.setOnPreparedListener { mediaPlayer ->
                    mediaPlayer.isLooping = true
                    view.start()
                    view.seekTo(1)
                }
                if (!view.isPlaying) {
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

    val tempVideoFile = createTempFileFromUri(context, videoUri, "input_video")
    if (tempVideoFile == null) {
        postResult(null, "Failed to create temporary video file from URI.")
        return
    }

    val outputDir = File(context.cacheDir, "gifs")
    if (!outputDir.exists()) {
        outputDir.mkdirs()
    }
    val outputGifFile = File(outputDir, "output_${System.currentTimeMillis()}.gif")
    val outputPath = outputGifFile.absolutePath

    // FFmpeg command: -i input_video_path -vf "fps=10,scale=320:-1:flags=lanczos" -c:v gif -y output_gif_path
    // -y overwrites output file if it exists
    // fps=10: sets GIF to 10 frames per second
    // scale=320:-1: resizes width to 320px, height is auto-adjusted maintaining aspect ratio
    // flags=lanczos: lanczos resampling algorithm for scaling, good quality
    val command = "-i \"${tempVideoFile.absolutePath}\" -vf \"fps=10,scale=320:-1:flags=lanczos\" -c:v gif -y \"$outputPath\""

    postLog("Temporary input copied to: ${tempVideoFile.absolutePath}")
    postLog("GIF will be written to: $outputPath")
    postLog("Executing command: $command")

    FFmpegKit.executeAsync(
        command,
        { session ->
            val returnCode = session.returnCode
            postLog("FFmpeg process exited with state ${session.state} and rc $returnCode")

            tempVideoFile.delete() // Clean up temp video file

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

fun createTempFileFromUri(context: Context, uri: Uri, prefix: String): File? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val extension = getFileExtensionFromUri(context, uri) ?: "tmp"
            // Ensure a valid extension, default to .tmp if URI doesn't provide one
            val safeExtension = if (extension.isBlank() || extension.length > 5) "tmp" else extension
            val tempFile = File.createTempFile("${prefix}_", ".$safeExtension", context.cacheDir)

            FileOutputStream(tempFile).use { fos ->
                inputStream.copyTo(fos)
            }
            tempFile
        }
    } catch (e: Exception) {
        Log.e("CreateTempFile", "Error creating temp file from URI: $uri", e)
        null
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
