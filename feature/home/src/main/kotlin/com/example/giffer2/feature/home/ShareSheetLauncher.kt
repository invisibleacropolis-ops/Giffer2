package com.example.giffer2.feature.home

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.example.giffer2.feature.home.processing.GifProcessingCoordinator

/**
 * Helper that prepares and launches a share sheet for GIF exports.
 */
class ShareSheetLauncher(private val context: Context) {
    /**
     * Launches an ACTION_SEND intent. Returns true if an activity was started
     * successfully, false if no compatible activity was available.
     */
    fun launch(result: GifProcessingCoordinator.ShareResult): Boolean {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_SUBJECT, result.displayName)
            val uri = result.uri
            if (uri.scheme.equals("data", ignoreCase = true)) {
                type = "text/plain"
                extractBase64Payload(uri)?.let { payload ->
                    putExtra(Intent.EXTRA_TEXT, payload)
                } ?: putExtra(Intent.EXTRA_TEXT, uri.toString())
            } else {
                type = result.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        return try {
            context.startActivity(Intent.createChooser(shareIntent, result.displayName))
            true
        } catch (error: ActivityNotFoundException) {
            false
        }
    }

    private fun extractBase64Payload(uri: Uri): String? {
        val payload = uri.schemeSpecificPart ?: return null
        val marker = "base64,"
        val index = payload.indexOf(marker)
        return if (index >= 0) payload.substring(index + marker.length) else payload
    }
}

@Composable
fun rememberShareSheetLauncher(): ShareSheetLauncher {
    val context = LocalContext.current
    return remember(context) { ShareSheetLauncher(context) }
}
