package com.materialagent.core.model

import com.materialagent.core.bool
import com.materialagent.core.int
import com.materialagent.core.str
import com.materialagent.core.strAny
import java.util.Base64
import kotlinx.serialization.json.JsonObject

/*
 * Sending a file to the agent.
 *
 * The gateway has no HTTP upload endpoint a chat turn can use — the desktop's
 * remote path is two session-scoped RPCs over the same WebSocket everything else
 * uses, and the app mirrors it exactly:
 *
 *   * an **image** goes up as `image.attach_bytes`. The server writes the bytes
 *     into its own `images/` directory and queues the path in the session; the
 *     *next* `prompt.submit` claims that queue and hands the agent a vision
 *     reference. Nothing about the picture travels in the prompt text.
 *   * anything else goes up as `file.attach`. The server writes it into the
 *     session's `attachments/` directory and hands back a `@file:` reference
 *     which the *client* has to put in the prompt text — there is no queue for
 *     it, so a file that is staged but never referenced is a file the agent
 *     never learns about.
 *
 * Both are verified against a live gateway (see `AttachmentContractTest` for the
 * shapes, `docs/PROTOCOL.md` for the round trip).
 *
 * Android-free on purpose: the picking, the reading and the encoding stay in the
 * data layer, so the wire contract is testable on the JVM.
 */

/**
 * One file the user picked, before it has been sent anywhere.
 *
 * [uri] is the picker's `content://` string rather than an `android.net.Uri`, so
 * this type can live in `core/` and in the transcript. [sizeBytes] is what the
 * provider reported at pick time; a provider that cannot report a size gives 0,
 * which is why the limit check treats 0 as "unknown" rather than "empty".
 */
data class OutgoingAttachment(
    val id: String,
    val uri: String,
    val name: String,
    val mimeType: String,
    val kind: MediaKind,
    val sizeBytes: Long,
)

object OutgoingAttachments {

    /**
     * Bytes an image may be, matching the server's own `_ATTACH_BYTES_MAX_BYTES`.
     *
     * Not arbitrary: the gateway rejects a larger one with error 4018, and a
     * client that only discovers the cap from a rejection has already spent the
     * upload. Enforced here so the user is told before anything moves.
     */
    const val IMAGE_MAX_BYTES = 25L * 1024 * 1024

    /**
     * Bytes a non-image may be.
     *
     * The server's own cap for this path is the WebSocket frame limit (384 MiB),
     * which is far past what is kind to a phone: `file.attach` carries the whole
     * file as one base64 `data_url`, and base64 inflates by a third, so a file of
     * this size is already a ~33 MB string plus a JSON copy of it in memory
     * before a byte reaches the socket. Capped at the image limit for the same
     * order of magnitude, and because a phone is a bad place to send a 300 MB
     * archive from anyway.
     */
    const val FILE_MAX_BYTES = 25L * 1024 * 1024

    private val IMAGE_MIME_PREFIXES = listOf("image/")
    private val AUDIO_MIME_PREFIXES = listOf("audio/")
    private val IMAGE_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "tiff", "tif", "heic", "heif",
    )
    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "amr",
    )

    /**
     * How the server should be told about this file.
     *
     * MIME type first, extension second: a provider that reports
     * `application/octet-stream` for a `.png` must still reach the vision path,
     * and one that reports nothing at all must not be rejected outright. Video is
     * deliberately folded into [MediaKind.FILE] — the gateway has no video-attach
     * RPC, so a clip is staged as a file the agent can point a tool at.
     */
    fun kindOf(mimeType: String, name: String): MediaKind {
        val mime = mimeType.substringBefore(';').trim().lowercase()
        if (IMAGE_MIME_PREFIXES.any { mime.startsWith(it) }) return MediaKind.IMAGE
        if (AUDIO_MIME_PREFIXES.any { mime.startsWith(it) }) return MediaKind.AUDIO

        val extension = name.substringAfterLast('.', "").lowercase()
        return when (extension) {
            in IMAGE_EXTENSIONS -> MediaKind.IMAGE
            in AUDIO_EXTENSIONS -> MediaKind.AUDIO
            else -> MediaKind.FILE
        }
    }

    /** The ceiling for [kind]; images and everything else are capped separately. */
    fun maxBytesFor(kind: MediaKind): Long =
        if (kind == MediaKind.IMAGE) IMAGE_MAX_BYTES else FILE_MAX_BYTES

    /**
     * Why this file cannot be sent, or null when it can.
     *
     * A rejected pick has to say which file and which limit, because the user is
     * looking at a list of several and "too large" alone leaves them guessing
     * which one to remove.
     */
    fun rejectReason(kind: MediaKind, sizeBytes: Long, name: String): String? {
        if (sizeBytes <= 0) return null // The provider did not report one; let the server decide.
        val max = maxBytesFor(kind)
        if (sizeBytes <= max) return null
        return "\"$name\" is ${megabytes(sizeBytes)} MB; the limit is ${megabytes(max)} MB."
    }

    /** The file's bytes as the `<mime>;base64,<data>` URL `file.attach` expects. */
    fun dataUrl(mimeType: String, bytes: ByteArray): String {
        val mime = mimeType.substringBefore(';').trim().ifBlank { "application/octet-stream" }
        return "data:$mime;base64," + base64(bytes)
    }

    /**
     * Base64 for `image.attach_bytes`.
     *
     * Raw, with no `data:` wrapper: the desktop sends it this way and the server
     * strips a wrapper if one is present, so the shorter form is simply less to
     * put on the wire.
     */
    fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun megabytes(bytes: Long): Long = bytes / (1024 * 1024)
}

/**
 * What the gateway handed back for one staged attachment.
 *
 * [refText] is set only for a file — it is the `@file:<path>` token the prompt
 * must carry. [count] is the number of images now queued on the session, which is
 * what the UI needs to say "2 of 3 attached" while a batch is going up.
 */
data class StagedAttachment(
    val path: String,
    val refText: String? = null,
    val count: Int? = null,
)

/**
 * Reads the two attach replies.
 *
 * Both are read defensively rather than deserialised: the gateway returns a
 * `message` instead of a `path` on some refusals, and a client that assumed
 * `path` would show a blank chip for a file the server actually rejected.
 */
object AttachmentReports {

    /** `image.attach_bytes` — `{attached, path, count, name, text, bytes, …}`. */
    fun parseImage(json: JsonObject): StagedAttachment? {
        if (json.bool("attached") != true) return null
        val path = json.str("path") ?: return null
        return StagedAttachment(path = path, count = json.int("count"))
    }

    /** `file.attach` — `{attached, name, path, ref_path, ref_text, uploaded}`. */
    fun parseFile(json: JsonObject): StagedAttachment? {
        if (json.bool("attached") != true) return null
        val path = json.str("path") ?: return null
        return StagedAttachment(path = path, refText = json.str("ref_text"))
    }

    /**
     * Why the server would not take the file, in its own words when it offered
     * any.
     *
     * A refusal is the one attachment reply the user has to act on, so "no
     * reason given" is a real answer and has to be distinguishable from a reason
     * the server actually supplied.
     */
    fun refusalMessage(json: JsonObject): String =
        json.strAny("message", "error", "detail").orEmpty().trim().ifEmpty { "no reason given" }
}

/**
 * Builds the text a turn is actually submitted with.
 *
 * Files have no server-side queue, so their refs have to lead the prompt; the
 * desktop joins refs, any extra context blocks and the visible text with a blank
 * line between each, and the gateway's own `@file:` handling is what turns the
 * leading refs into readable artifacts.
 *
 * An image with no words at all still has to become a prompt — the user attached
 * a picture and pressed send, which is a question even when they typed nothing.
 */
object AttachmentPrompt {

    const val IMAGE_ONLY_TEXT = "What do you see in this image?"

    fun compose(text: String, fileRefs: List<String>, hasImage: Boolean): String {
        val refs = fileRefs.filter { it.isNotBlank() }.joinToString("\n")
        val body = listOf(refs, text.trim()).filter { it.isNotEmpty() }.joinToString("\n\n")
        return body.ifEmpty { if (hasImage) IMAGE_ONLY_TEXT else "" }
    }
}

/**
 * One attachment reference as the gateway stores it inside a user turn.
 *
 * [name] is the server's own file name, which is what the user sent even when it
 * is not the name they sent: an image is stored as `upload_<timestamp>_<n>.png`
 * on the way in.
 */
data class StoredRef(val kind: MediaKind, val path: String) {
    val name: String get() = path.substringAfterLast('/')
}

/** A stored user turn: the words, and the references wound into them. */
data class UserTurnText(val text: String, val refs: List<StoredRef>)

/**
 * Reads the `@image:` / `@file:` lines the gateway leaves in a stored user turn.
 *
 * The references are an implementation detail of *submitting* a turn, but the
 * server keeps them in the message it stores, so reopening a conversation hands
 * them back as ordinary text. Left alone they render as
 * `@image:/home/…/upload_20260914_155013_1.png` in the middle of the user's own
 * sentence — a server path shown to someone who attached a photo.
 *
 * Only a line that is *nothing but* a reference is removed, and the reference is
 * returned rather than discarded so the row can still say what was attached. A
 * user who types "@file:/etc/hosts is misconfigured" keeps their sentence,
 * because that line is not a bare reference.
 */
object AttachmentRefs {

    private val refLine = Regex("""^@(image|file):(.+)$""")

    fun split(text: String): UserTurnText {
        val refs = mutableListOf<StoredRef>()
        val prose = text.lines().filterNot { line ->
            val match = refLine.matchEntire(line.trim()) ?: return@filterNot false
            val (kindToken, rawPath) = match.destructured
            refs += StoredRef(
                kind = if (kindToken == "image") MediaKind.IMAGE else MediaKind.FILE,
                path = unquote(rawPath.trim()),
            )
            true
        }
        return UserTurnText(text = prose.joinToString("\n").trim(), refs = refs)
    }

    /**
     * Strips the quoting the server applies to a path containing whitespace or
     * brackets, so the chip shows a name and not a row of backticks.
     */
    private fun unquote(value: String): String {
        if (value.length < 2) return value
        val first = value.first()
        val last = value.last()
        val paired = when (first) {
            '`' -> last == '`'
            '"' -> last == '"'
            '\'' -> last == '\''
            else -> false
        }
        return if (paired) value.substring(1, value.length - 1) else value
    }
}
