package com.materialagent.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.materialagent.core.AttachmentParams
import com.materialagent.core.model.AttachmentReports
import com.materialagent.core.model.MediaKind
import com.materialagent.core.model.OutgoingAttachment
import com.materialagent.core.model.OutgoingAttachments
import com.materialagent.core.model.StagedAttachment
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * What one batch of attachments staged on the gateway.
 *
 * The two lists are not interchangeable and both are needed after the call:
 * images live in a server-side queue that a failed turn has to release, while
 * file refs have to be woven into the prompt text. Losing either one leaves the
 * user with a picture the agent never sees.
 */
data class StagedAttachments(
    val imagePaths: List<String> = emptyList(),
    val fileRefs: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = imagePaths.isEmpty() && fileRefs.isEmpty()
}

/**
 * Puts the user's picked files on the gateway.
 *
 * Two RPCs, chosen by kind, both carrying the bytes inside the request: the
 * gateway is a different machine, so a `content://` URI means nothing to it. The
 * bytes are read once, encoded once, and sent once — a retry re-reads them from
 * the provider rather than keeping a second copy of a 25 MB file in memory.
 */
class AttachmentSender(
    private val connection: HermesConnection,
    private val resolver: ContentResolver,
) {

    /**
     * Stages [attachments] in order.
     *
     * A failure part-way through leaves image paths already queued on the server,
     * which is not a harmless leftover: the queue is claimed by whatever
     * `prompt.submit` arrives next, so an abandoned image would ride along with a
     * later, unrelated message. The staging loop therefore takes its own images
     * back before reporting failure, and says so if it could not.
     */
    suspend fun stage(
        sessionId: String,
        attachments: List<OutgoingAttachment>,
    ): Result<StagedAttachments> {
        val images = mutableListOf<String>()
        val refs = mutableListOf<String>()

        for (attachment in attachments) {
            val bytes = readBytes(attachment).getOrElse { error ->
                return fail(sessionId, images, attachment, error.message ?: "Could not read the file")
            }

            val cap = OutgoingAttachments.maxBytesFor(attachment.kind)
            if (bytes.size > cap) {
                // A provider that reported no size (0) got this far; the real
                // length is only knowable after a read, so the check is repeated
                // here rather than trusted from the picker.
                return fail(sessionId, images, attachment, OutgoingAttachments
                    .rejectReason(attachment.kind, bytes.size.toLong(), attachment.name)
                    ?: "The file is too large to send.")
            }

            val reply = runCatching { submitAttachment(sessionId, attachment, bytes) }
                .getOrElse { error ->
                    return fail(sessionId, images, attachment, error.message ?: "The server refused it")
                }

            // A refusal is not an RPC error: the server answers with
            // `attached: false` and a reason, so the parsed model is what
            // distinguishes success from failure here.
            val staged = when (attachment.kind) {
                MediaKind.IMAGE -> AttachmentReports.parseImage(reply)
                else -> AttachmentReports.parseFile(reply)
            } ?: return fail(
                sessionId,
                images,
                attachment,
                "The server did not accept it: ${AttachmentReports.refusalMessage(reply)}",
            )

            when (attachment.kind) {
                MediaKind.IMAGE -> images.add(staged.path)
                else -> staged.refText?.takeIf { it.isNotBlank() }?.let { refs.add(it) }
            }
        }

        return Result.success(StagedAttachments(imagePaths = images, fileRefs = refs))
    }

    /**
     * Takes queued images back off the session.
     *
     * Best effort, and it reports how many are *still* queued rather than how
     * many were asked for: an image the server no longer holds (the session was
     * resumed onto a new runtime id, or the turn already consumed it) is a
     * success, not a failure, and counting names instead of outcomes would report
     * a release that did not happen.
     */
    suspend fun releaseImages(sessionId: String, paths: List<String>): Int {
        if (paths.isEmpty()) return 0
        var remaining = 0
        for (path in paths) {
            connection.send("image.detach", AttachmentParams.imageDetach(sessionId, path)).onFailure {
                remaining += 1
            }
        }
        return remaining
    }

    /** Puts one attachment's bytes on the server and hands back its raw reply. */
    private suspend fun submitAttachment(
        sessionId: String,
        attachment: OutgoingAttachment,
        bytes: ByteArray,
    ): JsonObject = if (attachment.kind == MediaKind.IMAGE) {
        connection.request(
            "image.attach_bytes",
            AttachmentParams.imageAttachBytes(
                sessionId = sessionId,
                filename = attachment.name,
                contentBase64 = OutgoingAttachments.base64(bytes),
            ),
            timeoutMs = STAGING_TIMEOUT_MS,
        )
    } else {
        connection.request(
            "file.attach",
            AttachmentParams.fileAttach(
                sessionId = sessionId,
                // The picker's own URI is the honest client-side path. It is
                // deliberately *not* the bare file name: the server tries to
                // resolve this token on its own disk before falling back to the
                // uploaded bytes, and a relative name could match some unrelated
                // file in its working directory.
                path = attachment.uri,
                name = attachment.name,
                dataUrl = OutgoingAttachments.dataUrl(attachment.mimeType, bytes),
            ),
            timeoutMs = STAGING_TIMEOUT_MS,
        )
    }

    private suspend fun fail(
        sessionId: String,
        stagedImages: List<String>,
        attachment: OutgoingAttachment,
        reason: String,
    ): Result<StagedAttachments> {
        val remaining = releaseImages(sessionId, stagedImages)
        val leftover = if (remaining > 0) {
            " ($remaining already-uploaded ${if (remaining == 1) "image" else "images"} could not be withdrawn)"
        } else {
            ""
        }
        return Result.failure(IllegalStateException("\"${attachment.name}\": $reason$leftover"))
    }

    private suspend fun readBytes(attachment: OutgoingAttachment): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(attachment.uri)
                resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw FileNotFoundException("the provider returned no data")
            }
        }

    private companion object {
        /**
         * Generous on purpose: a 25 MB image is ~33 MB of base64, and a phone on
         * a tailnet can take a while over it. A short deadline here would abort
         * uploads that were working, and the server would keep whatever it had
         * already buffered.
         */
        const val STAGING_TIMEOUT_MS = 180_000L
    }
}

/**
 * Describes a picked file well enough to show it and to send it.
 *
 * Goes through `ContentResolver` rather than the URI's own path because a
 * document provider's URI is opaque: `content://com.android.providers…/1234` has
 * no filename in it, and the display name is only available from the provider's
 * metadata. A provider that will not answer is not fatal — the fallback name is
 * ugly but the attachment still works.
 */
fun describeAttachment(context: Context, uri: Uri): OutgoingAttachment {
    val resolver = context.contentResolver
    var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    var size = 0L

    runCatching {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { name = it }
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
    }

    if (name.isBlank()) name = "attachment"
    val mime = resolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }

    return OutgoingAttachment(
        id = uri.toString(),
        uri = uri.toString(),
        name = name,
        mimeType = mime,
        kind = OutgoingAttachments.kindOf(mime, name),
        sizeBytes = size,
    )
}
