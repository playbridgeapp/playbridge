package com.playbridge.sender.browser

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import mozilla.components.concept.engine.prompt.PromptRequest

/**
 * Answers a page's `<input type="file">` with the system file picker. Returns the function the
 * prompt handler calls; the picked files go back to the page, and a cancelled pick dismisses
 * the prompt so the page doesn't wait forever.
 */
@Composable
internal fun rememberWebFilePicker(): (PromptRequest.File) -> Unit {
    val context = LocalContext.current
    val pending = remember { mutableStateOf<PromptRequest.File?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val prompt = pending.value ?: return@rememberLauncherForActivityResult
        pending.value = null
        val uris = if (result.resultCode == Activity.RESULT_OK) pickedUris(result.data) else emptyList()
        when {
            uris.isEmpty() -> prompt.onDismiss()
            prompt.isMultipleFilesSelection -> prompt.onMultipleFilesSelected(context, uris.toTypedArray())
            else -> prompt.onSingleFileSelected(context, uris.first())
        }
    }
    return remember(launcher) {
        { prompt ->
            // A page can only wait on one picker; an older unanswered one is given up.
            pending.value?.onDismiss()
            pending.value = prompt
            val types = pickerMimeTypes(prompt.mimeTypes) { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = types.singleOrNull() ?: "*/*"
                if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, prompt.isMultipleFilesSelection)
            }
            try {
                launcher.launch(intent)
            } catch (_: ActivityNotFoundException) {
                pending.value = null
                prompt.onDismiss()
            }
        }
    }
}

/**
 * The MIME types to offer for a file input's `accept` list. Extensions such as `.pdf` become
 * their MIME type; anything unknown, or no list at all, offers every file.
 */
internal fun pickerMimeTypes(accept: Array<out String>, mimeForExtension: (String) -> String?): List<String> {
    val types = accept.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.map { entry ->
        if (entry.startsWith(".")) mimeForExtension(entry.removePrefix(".")) else entry.takeIf { "/" in it }
    }
    if (types.isEmpty() || types.any { it == null || it == "*/*" }) return listOf("*/*")
    return types.filterNotNull().distinct()
}

private fun pickedUris(data: Intent?): List<Uri> {
    if (data == null) return emptyList()
    val clip = data.clipData
    val fromClip = if (clip != null) (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } else emptyList()
    return (fromClip + listOfNotNull(data.data)).distinct()
}
