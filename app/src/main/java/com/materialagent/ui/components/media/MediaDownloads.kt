package com.materialagent.ui.components.media

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.materialagent.core.model.MediaRef
import com.materialagent.data.HapticCue
import com.materialagent.ui.rememberCue
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The Android half of an attachment: turning an authenticated URL into a file in
 * the app's cache, and handing that file to whatever app can open it.
 *
 * Both steps are shared by the audio, video and file rows, because "save this"
 * means the same thing for all three and three private copies of it would drift.
 */

/** The cache subdirectory attachment downloads land in. Also in `res/xml/file_paths.xml`. */
private const val MEDIA_CACHE_DIR = "media"

/**
 * Downloads [url] into the cache under a sanitised [name].
 *
 * Goes through the environment's client, so the request carries the sign-in
 * cookie the gateway demands. A failure is returned rather than thrown: the row
 * shows the reason inline, and "the server refused this file" is something the
 * user should read, not something that should crash a scrolling transcript.
 */
internal suspend fun downloadToCache(
    env: MediaEnvironment,
    url: String,
    name: String,
): Result<File> = withContext(Dispatchers.IO) {
    val cacheDir = env.cacheDir
        ?: return@withContext Result.failure(IllegalStateException("No cache is available."))
    val target = File(File(cacheDir, MEDIA_CACHE_DIR), MediaNames.safe(name))
    try {
        MediaDownload.fetch(env.client, url, target)
        Result.success(target)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}

/**
 * Hands a downloaded file to another app: its viewer first, the share sheet
 * second.
 *
 * A `content://` URI is mandatory — passing a `file://` path to another app
 * throws FileUriExposedException on every Android this app supports — which is
 * why the cache directory is exposed through the same FileProvider the updater
 * uses. Not every file has an app that claims its type, so a refusal falls
 * through to sharing it instead of telling the user nothing happened.
 */
internal fun openDownloaded(context: Context, file: File, mime: String?): Boolean {
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull() ?: return false

    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (runCatching { context.startActivity(view) }.isSuccess) return true

    val share = Intent(Intent.ACTION_SEND).apply {
        type = mime ?: "*/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(share, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(chooser) }.isSuccess
}

/** The MIME type an attachment's extension implies, when the system knows one. */
internal fun mimeOf(ref: MediaRef): String? =
    ref.extension.takeIf { it.isNotBlank() }
        ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }

/**
 * One row's download, with the state its button needs.
 *
 * Held as an object rather than three loose values so the audio, video and file
 * rows can share the whole interaction — the spinner while it runs, the size it
 * learned, the reason it failed — without each of them re-implementing it.
 */
@Stable
class AttachmentDownload internal constructor(
    private val busyState: MutableState<Boolean>,
    private val sizeState: MutableState<Long>,
    private val errorState: MutableState<String?>,
    private val startAction: () -> Unit,
) {
    val busy: Boolean get() = busyState.value

    /** Byte count learned from the download; 0 until one has completed. */
    val sizeBytes: Long get() = sizeState.value

    val error: String? get() = errorState.value

    fun start() = startAction()
}

/**
 * A download action for one attachment.
 *
 * Only the state is remembered, not the action: the action reads the *current*
 * haptic callback and coroutine scope, so changing the haptics preference takes
 * effect on the next tap rather than being frozen at first composition.
 */
@Composable
internal fun rememberAttachmentDownload(
    env: MediaEnvironment,
    url: String,
    ref: MediaRef,
): AttachmentDownload {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cue by rememberUpdatedState(rememberCue())

    val busy = remember(url) { mutableStateOf(false) }
    val size = remember(url) { mutableLongStateOf(0L) }
    val error = remember(url) { mutableStateOf<String?>(null) }

    return AttachmentDownload(busy, size, error) {
        if (!busy.value) {
            busy.value = true
            error.value = null
            cue(HapticCue.UI_ACTION)
            scope.launch {
                downloadToCache(env, url, ref.name)
                    .onSuccess { file ->
                        size.value = file.length()
                        if (openDownloaded(context.applicationContext, file, mimeOf(ref))) {
                            cue(HapticCue.TOOL_DONE)
                        } else {
                            error.value = "Saved to the cache, but no app here can open it."
                        }
                    }
                    .onFailure { failure ->
                        error.value = failure.message ?: "Could not download this file."
                    }
                busy.value = false
            }
        }
    }
}
