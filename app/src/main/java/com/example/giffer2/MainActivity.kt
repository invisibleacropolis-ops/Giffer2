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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.rememberAsyncImagePainter
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.giffer2.ui.theme.Giffer2Theme
import java.io.File
import java.io.FileOutputStream

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
    var isLoading by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val selectVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            videoUri = uri
            gifPath = null // Reset previous GIF path
            conversionStatus = ""
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
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(onClick = { selectVideoLauncher.launch("video/*") }) {
            Text("Select Video")
        }

        videoUri?.let { uri ->
            Text("Selected: ${getFileName(context, uri) ?: "Unknown file"}")
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                isLoading = true
                conversionStatus = "Converting..."
                gifPath = null
                convertVideoToGif(context, uri) { outputPath, error ->
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
            Image(
                painter = rememberAsyncImagePainter(File(gifPath!!), imageLoader),
                contentDescription = "Generated GIF",
                modifier = Modifier.size(200.dp) // Adjust size as needed
            )
            Text("GIF saved to: $gifPath")
        } else {
            Text(conversionStatus)
        }
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

fun convertVideoToGif(context: Context, videoUri: Uri, callback: (String?, String?) -> Unit) {
    val tempVideoFile = createTempFileFromUri(context, videoUri, "input_video")
    if (tempVideoFile == null) {
        callback(null, "Failed to create temporary video file from URI.")
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

    Log.d("FFmpegKit", "Executing command: $command")

    FFmpegKit.executeAsync(command) { session ->
        val returnCode = session.returnCode
        val logs = session.allLogsAsString
        Log.d("FFmpegKit", "FFmpeg process exited with state ${session.state} and rc ${returnCode}.")
        Log.d("FFmpegKit", "Logs:\n$logs")

        tempVideoFile.delete() // Clean up temp video file

        if (ReturnCode.isSuccess(returnCode)) {
            Log.i("FFmpegKit", "GIF conversion successful: $outputPath")
            callback(outputPath, null)
        } else if (ReturnCode.isCancel(returnCode)) {
            Log.w("FFmpegKit", "GIF conversion cancelled.")
            callback(null, "Conversion cancelled.")
        } else {
            Log.e("FFmpegKit", "GIF conversion failed. RC: $returnCode. Error: ${session.failStackTrace}")
            callback(null, "Conversion failed (RC: $returnCode). Check logs.")
        }
    }
}

fun createTempFileFromUri(context: Context, uri: Uri, prefix: String): File? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val extension = getFileExtensionFromUri(context, uri) ?: "tmp"
        // Ensure a valid extension, default to .tmp if URI doesn't provide one
        val safeExtension = if (extension.isBlank() || extension.length > 5) "tmp" else extension
        val tempFile = File.createTempFile("${prefix}_", ".$safeExtension", context.cacheDir)

        FileOutputStream(tempFile).use { fos ->
            inputStream.copyTo(fos)
        }
        inputStream.close()
        tempFile
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
