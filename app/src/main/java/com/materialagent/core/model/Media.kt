package com.materialagent.core.model

import java.io.ByteArrayOutputStream
import java.net.URLEncoder

/** What a `MEDIA:` attachment is, decided by its file extension. */
enum class MediaKind { IMAGE, AUDIO, VIDEO, FILE }

/**
 * One file the agent attached to its answer.
 *
 * The agent does not send attachments as payloads — it writes `MEDIA:<path>`
 * markers into its own assistant text and lets the client fetch the bytes. So a
 * ref is a path plus whatever caption followed it on the same line, and the
 * fetch URLs are derived from it on demand (see [MediaUrls]).
 */
data class MediaRef(
    val path: String,
    val caption: String?,
    val kind: MediaKind,
) {
    /**
     * The last path segment, percent-decoded — what a download should be named.
     *
     * A trailing slash means the marker pointed at a directory, and a directory
     * makes a poor attachment name, so it falls back rather than surfacing an
     * empty label.
     */
    val name: String
        get() = percentDecode(path.substringAfterLast('/')).ifBlank { "attachment" }

    /** Lowercase extension without the dot, or "" when the name has none. */
    val extension: String get() = extensionOf(path)
}

/** Assistant text with its markers removed, plus the attachments they named. */
data class MediaExtraction(val text: String, val media: List<MediaRef>)

/**
 * Parses the `MEDIA:<path>` markers the agent writes into its answer text.
 *
 * The marker is part of the prose the agent sends, so every consumer has to take
 * it back out again: the user must never see a raw `MEDIA:/tmp/x.png` line, and
 * the same text has to survive a reconnect through session history. A caption
 * may follow the path on the same line, and a single answer may carry several.
 *
 * Android-free on purpose, so the whole thing is testable on the JVM.
 */
object MediaMarkers {

    private const val MARKER = "MEDIA:"

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")
    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac")
    private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "webm", "mkv")

    /** A `MEDIA:` that has started to arrive mid-stream, up to and including its colon. */
    private val PARTIAL_MARKER = Regex("(?<!\\S)M(?:E(?:D(?:I(?:A:?)?)?)?)?\$")

    /** The kind an absolute path's extension implies; anything unknown is a file. */
    fun kindOf(path: String): MediaKind = when (extensionOf(path)) {
        in IMAGE_EXTENSIONS -> MediaKind.IMAGE
        in AUDIO_EXTENSIONS -> MediaKind.AUDIO
        in VIDEO_EXTENSIONS -> MediaKind.VIDEO
        else -> MediaKind.FILE
    }

    /**
     * Splits one answer into display text and attachments.
     *
     * Text with no marker is returned untouched, so a turn that only talks about
     * `MEDIA:` (a bare marker with no path is not a marker) reads exactly as the
     * agent wrote it.
     */
    fun extract(text: String): MediaExtraction {
        if (!text.contains(MARKER)) return MediaExtraction(text, emptyList())

        val media = mutableListOf<MediaRef>()
        val kept = mutableListOf<String>()
        var stripped = false
        for (line in text.split('\n')) {
            val parsed = parseLine(line)
            if (parsed == null) {
                kept.add(line)
                continue
            }
            stripped = true
            media.addAll(parsed.media)
            kept.add(parsed.text)
        }
        // Every "MEDIA:" in the text was bare (no path follows), so nothing was
        // removed and the original text still stands.
        if (!stripped) return MediaExtraction(text, emptyList())

        return MediaExtraction(tidy(kept), media)
    }

    /**
     * Hides markers in text that is still arriving.
     *
     * Nothing is attached until `message.complete`, but the deltas carry the raw
     * marker as it streams in, and a marker can be split across two of them
     * ("MEDIA:/tmp/ver" + "sion.pdf"). That is why this runs over the whole raw
     * stream each time rather than over the newest chunk: the caller keeps the
     * raw text and this returns what may be shown of it.
     *
     * A line is cut from its first marker to its end, so the caption goes too.
     * On the last line an unfinished fragment is cut as well — a bare `MEDIA`, a
     * `MEDIA:`, or a path still being typed — because the delta that finishes it
     * has not arrived yet. Lines with nothing to hide are returned untouched: the
     * text is still growing, and trimming it here would eat the space that
     * separates two halves of a word.
     */
    fun stripStreaming(raw: String): String {
        if (raw.isEmpty() || !raw.contains('M')) return raw

        val lines = raw.split('\n')
        return lines.mapIndexed { index, line ->
            val at = markerIndex(line)
            if (at >= 0) return@mapIndexed line.substring(0, at).trimEnd()
            if (index != lines.lastIndex) return@mapIndexed line
            val partial = PARTIAL_MARKER.find(line)?.range?.first
            if (partial == null) line else line.substring(0, partial).trimEnd()
        }.joinToString("\n")
    }

    // ── parsing ─────────────────────────────────────────────────────────────

    /** One line with its markers taken out, and the refs they named. */
    private data class ParsedLine(val text: String, val media: List<MediaRef>)

    /**
     * Pulls every marker out of one line, returning the refs it named, or null
     * when the line holds none.
     *
     * A caption runs from a marker's path to the next marker on that line, so
     * `MEDIA:/a.png first MEDIA:/b.png second` yields two captions instead of
     * reading the second marker as the first one's caption.
     */
    private fun parseLine(line: String): ParsedLine? {
        val starts = mutableListOf<Int>()
        var from = 0
        while (true) {
            val at = markerIndex(line, from)
            if (at < 0) break
            starts.add(at)
            from = at + MARKER.length
        }
        if (starts.isEmpty()) return null

        val media = starts.mapIndexed { index, start ->
            val pathStart = start + MARKER.length
            val pathEnd = pathEnd(line, pathStart)
            val regionEnd = if (index + 1 < starts.size) starts[index + 1] else line.length
            val path = line.substring(pathStart, pathEnd)
            MediaRef(
                path = path,
                caption = line.substring(pathEnd, regionEnd).trim().ifBlank { null },
                kind = kindOf(path),
            )
        }
        return ParsedLine(text = line.substring(0, starts.first()).trimEnd(), media = media)
    }

    /**
     * Index of the next `MEDIA:` that actually names something.
     *
     * The marker has to start a line or follow whitespace: without that a URL or
     * an identifier that merely contains "MEDIA:/x" would turn into an
     * attachment. And a bare `MEDIA:` with nothing after it is prose, not a
     * marker — the agent writes the colon as part of a path, so a path is
     * required.
     */
    private fun markerIndex(line: String, from: Int = 0): Int {
        var at = line.indexOf(MARKER, from)
        while (at >= 0) {
            val preceded = at == 0 || line[at - 1].isWhitespace()
            if (preceded && pathEnd(line, at + MARKER.length) > at + MARKER.length) return at
            at = line.indexOf(MARKER, at + MARKER.length)
        }
        return -1
    }

    /** End of the path run that begins at [start]; paths stop at whitespace. */
    private fun pathEnd(line: String, start: Int): Int {
        var end = start
        while (end < line.length && !line[end].isWhitespace()) end++
        return end
    }

    /**
     * Cleans the text the markers were cut out of.
     *
     * A marker usually sits on a line of its own, and removing it leaves that
     * line blank — often with a blank line on either side, which reads as a
     * hole in the answer. Blank runs collapse to one, the ends are trimmed, and
     * no line keeps trailing whitespace.
     */
    private fun tidy(lines: List<String>): String {
        val kept = mutableListOf<String>()
        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.isEmpty() && (kept.isEmpty() || kept.last().isEmpty())) continue
            kept.add(line)
        }
        while (kept.isNotEmpty() && kept.last().isEmpty()) kept.removeAt(kept.size - 1)
        return kept.joinToString("\n")
    }
}

/** Lowercase extension of a path's last segment, without the dot. */
private fun extensionOf(path: String): String {
    val segment = path.substringAfterLast('/')
    val dot = segment.lastIndexOf('.')
    // A leading dot marks a hidden file (".env"), not an extension.
    if (dot <= 0 || dot == segment.length - 1) return ""
    return segment.substring(dot + 1).lowercase()
}

/**
 * Decodes `%XX` escapes, leaving everything else — including `+` — alone.
 * Storage paths are not form data, so a literal `+` in a filename must stay a
 * `+` rather than become a space.
 */
private fun percentDecode(value: String): String {
    if (!value.contains('%')) return value
    val bytes = ByteArrayOutputStream(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '%' && index + 2 < value.length) {
            val high = Character.digit(value[index + 1], 16)
            val low = Character.digit(value[index + 2], 16)
            if (high >= 0 && low >= 0) {
                bytes.write(high shl 4 or low)
                index += 3
                continue
            }
        }
        for (byte in char.toString().toByteArray(Charsets.UTF_8)) bytes.write(byte.toInt())
        index++
    }
    return String(bytes.toByteArray(), Charsets.UTF_8)
}

/**
 * URLs for fetching an attachment back from the gateway.
 *
 * The three endpoints take the file's absolute path as a `path` query parameter,
 * so the path travels encoded and never as URL structure: an unencoded `#` would
 * truncate the request into a fragment and an `&` would invent a second
 * parameter.
 */
object MediaUrls {

    private const val IMAGE_PATH = "/api/media"
    private const val AUDIO_PATH = "/api/files/stream"
    private const val DOWNLOAD_PATH = "/api/files/download"

    /** Image bytes as a data URL, for files under the gateway's image roots. */
    fun forImage(baseUrl: String, ref: MediaRef): String = build(baseUrl, IMAGE_PATH, ref.path)

    /** The audio bytes with range support — the player's source. */
    fun forAudio(baseUrl: String, ref: MediaRef): String = build(baseUrl, AUDIO_PATH, ref.path)

    /** The bytes as an attachment, for anything the user saves. */
    fun forDownload(baseUrl: String, ref: MediaRef): String = build(baseUrl, DOWNLOAD_PATH, ref.path)

    private fun build(baseUrl: String, endpoint: String, path: String): String =
        baseUrl.trimEnd('/') + endpoint + "?path=" + encodeQuery(path)

    private fun encodeQuery(value: String): String =
        // URLEncoder is form encoding: it emits "+" for a space, which the
        // gateway's query parser may or may not read as one. %20 is unambiguous.
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
