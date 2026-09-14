package com.materialagent.core

import com.materialagent.core.model.MediaKind
import com.materialagent.core.model.MediaMarkers
import com.materialagent.core.model.MediaRef
import com.materialagent.core.model.MediaUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/**
 * Guards [MediaMarkers] and [MediaUrls], the `MEDIA:` marker contract.
 *
 * The agent delivers files by writing `MEDIA:<absolute path>` into its own answer
 * text, with an optional caption after the path on the same line and one marker
 * per file. The invariants: the marker itself never reaches the reader, every
 * marker that names something becomes a typed ref, text with no marker is
 * untouched, and the fetch URLs carry the path as one encoded query parameter.
 */
class MediaMarkersTest {

    // ── extraction ──────────────────────────────────────────────────────────

    @Test
    fun aLoneMarkerOnItsOwnLineBecomesAFileRef() {
        val result = MediaMarkers.extract("Report is ready:\n\nMEDIA:/tmp/report.pdf\n")

        assertEquals("Report is ready:", result.text)
        val ref = result.media.single()
        assertEquals("/tmp/report.pdf", ref.path)
        assertNull(ref.caption)
        assertEquals(MediaKind.FILE, ref.kind)
    }

    @Test
    fun severalMarkersOnePerLineAllBecomeRefs() {
        val result = MediaMarkers.extract(
            "Three things:\nMEDIA:/tmp/shot.png\nMEDIA:/tmp/voice.mp3\nMEDIA:/tmp/clip.mp4",
        )

        assertEquals("Three things:", result.text)
        assertEquals(
            listOf(MediaKind.IMAGE, MediaKind.AUDIO, MediaKind.VIDEO),
            result.media.map { it.kind },
        )
        assertEquals(
            listOf("/tmp/shot.png", "/tmp/voice.mp3", "/tmp/clip.mp4"),
            result.media.map { it.path },
        )
    }

    @Test
    fun textAfterThePathOnTheSameLineIsTheCaption() {
        val result = MediaMarkers.extract("MEDIA:/x.png This Caption")

        assertEquals("", result.text)
        assertEquals("This Caption", result.media.single().caption)
    }

    @Test
    fun aMarkerAfterProseKeepsTheProse() {
        val result = MediaMarkers.extract("Here is the shot MEDIA:/tmp/shot.png")

        assertEquals("Here is the shot", result.text)
        assertEquals("/tmp/shot.png", result.media.single().path)
        assertNull(result.media.single().caption)
    }

    @Test
    fun proseBeforeAndACaptionAfterAMidLineMarkerBothSurvive() {
        val result = MediaMarkers.extract("Here: MEDIA:/a.png the caption")

        assertEquals("Here:", result.text)
        assertEquals("the caption", result.media.single().caption)
    }

    /**
     * Two markers can share a line. Each caption stops at the next marker rather
     * than swallowing it, which would otherwise turn the second path into the
     * first marker's caption and leave an attachment never named.
     */
    @Test
    fun twoMarkersOnOneLineEachKeepTheirOwnCaption() {
        val result = MediaMarkers.extract("MEDIA:/a.png First MEDIA:/b.png Second")

        assertEquals("", result.text)
        assertEquals(2, result.media.size)
        assertEquals("First", result.media[0].caption)
        assertEquals("Second", result.media[1].caption)
        assertEquals("/b.png", result.media[1].path)
    }

    @Test
    fun textWithoutAMarkerIsReturnedByteIdentical() {
        val text = "No attachments.\n\n  Indented line with trailing spaces  \n"
        val result = MediaMarkers.extract(text)

        assertEquals(text, result.text)
        assertTrue(result.media.isEmpty())
    }

    /**
     * `MEDIA:` with nothing behind it is prose — the agent writes the colon as
     * part of a path — so "use the MEDIA: prefix" must stay as written.
     */
    @Test
    fun aBareMarkerIsNotAMarker() {
        val text = "Send it with MEDIA: and then the path."
        val result = MediaMarkers.extract(text)

        assertEquals(text, result.text)
        assertTrue(result.media.isEmpty())
    }

    @Test
    fun extensionCaseDoesNotChangeTheKind() {
        val result = MediaMarkers.extract(
            "MEDIA:/tmp/SHOT.HEIC\nMEDIA:/tmp/Clip.MP4\nMEDIA:/tmp/Voice.OGG",
        )

        assertEquals(
            listOf(MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.AUDIO),
            result.media.map { it.kind },
        )
        assertEquals("heic", result.media[0].extension)
        assertEquals("mp4", result.media[1].extension)
    }

    @Test
    fun anUnknownExtensionIsAFile() {
        val result = MediaMarkers.extract("MEDIA:/tmp/data.xyz\nMEDIA:/tmp/README")

        assertEquals(listOf(MediaKind.FILE, MediaKind.FILE), result.media.map { it.kind })
        assertEquals("xyz", result.media[0].extension)
        assertEquals("", result.media[1].extension)
    }

    @Test
    fun anAnswerThatIsOnlyAnAttachmentHasNoProse() {
        val result = MediaMarkers.extract("MEDIA:/tmp/report.pdf")

        assertEquals("", result.text)
        assertEquals(1, result.media.size)
    }

    @Test
    fun nameIsDecodedAndDirectoryPathsFallBack() {
        assertEquals("My Report.pdf", MediaRef("/tmp/My%20Report.pdf", null, MediaKind.FILE).name)
        // A literal plus is part of a filename, not a space.
        assertEquals("a+b.png", MediaRef("/tmp/a+b.png", null, MediaKind.IMAGE).name)
        assertEquals("attachment", MediaRef("/tmp/folder/", null, MediaKind.FILE).name)
        assertEquals("attachment", MediaRef("", null, MediaKind.FILE).name)
    }

    // ── streaming ───────────────────────────────────────────────────────────

    @Test
    fun streamingHidesAMarkerAndItsCaption() {
        assertEquals("", MediaMarkers.stripStreaming("MEDIA:/tmp/report.pdf"))
        assertEquals("Look at this", MediaMarkers.stripStreaming("Look at this MEDIA:/tmp/report.pdf"))
        assertEquals(
            "Look at this",
            MediaMarkers.stripStreaming("Look at this MEDIA:/tmp/report.png the caption"),
        )
    }

    @Test
    fun streamingHidesAMarkerThatIsStillBeingTyped() {
        assertEquals("Working", MediaMarkers.stripStreaming("Working M"))
        assertEquals("Working", MediaMarkers.stripStreaming("Working MED"))
        assertEquals("Working", MediaMarkers.stripStreaming("Working MEDIA"))
        assertEquals("Working", MediaMarkers.stripStreaming("Working MEDIA:"))
        assertEquals("Working", MediaMarkers.stripStreaming("Working MEDIA:/tmp/rep"))
    }

    /**
     * Deltas split wherever the model's tokens end, so a marker can arrive in two
     * halves. The path has to be recognised on the second half — the first half
     * alone is not a finished marker, and showing it would flash a raw path.
     */
    @Test
    fun streamingRecognisesAMarkerSplitAcrossDeltas() {
        assertEquals("Here it is", MediaMarkers.stripStreaming("Here it is MEDIA:/tmp/ver" + "sion.pdf"))
        assertEquals("Here it is", MediaMarkers.stripStreaming("Here it is MEDIA" + ":/tmp/version.pdf"))
    }

    @Test
    fun streamingLeavesTextWithNoMarkerAlone() {
        val text = "Hello world, no marker here."
        assertEquals(text, MediaMarkers.stripStreaming(text))
        // A trailing space is what separates this delta from the next word.
        assertEquals("Hello ", MediaMarkers.stripStreaming("Hello "))
    }

    /** Only the marker line is emptied while streaming; the final text replaces it. */
    @Test
    fun streamingEmptiesTheMarkerLineAndKeepsTheRest() {
        assertEquals(
            "before\n\nafter",
            MediaMarkers.stripStreaming("before\nMEDIA:/tmp/x.png\nafter"),
        )
    }

    // ── URLs ────────────────────────────────────────────────────────────────

    private val awkward = MediaRef("/tmp/my report#1 & caf\u00e9.png", null, MediaKind.IMAGE)
    private val awkwardQuery = "path=%2Ftmp%2Fmy%20report%231%20%26%20caf%C3%A9.png"

    @Test
    fun imageUrlCarriesThePathEncodedInTheQuery() {
        assertEquals("http://host:9119/api/media?$awkwardQuery", MediaUrls.forImage("http://host:9119", awkward))
        // A trailing slash on the configured base must not double up.
        assertEquals("http://host:9119/api/media?$awkwardQuery", MediaUrls.forImage("http://host:9119/", awkward))
        assertEquals(
            "https://hermes.example.com/hermes/api/media?$awkwardQuery",
            MediaUrls.forImage("https://hermes.example.com/hermes/", awkward),
        )
    }

    @Test
    fun audioAndDownloadUrlsUseTheirOwnEndpoints() {
        val note = MediaRef("/tmp/voice note.m4a", null, MediaKind.AUDIO)

        assertEquals(
            "http://host:9119/api/files/stream?path=%2Ftmp%2Fvoice%20note.m4a",
            MediaUrls.forAudio("http://host:9119", note),
        )
        assertEquals(
            "http://host:9119/api/files/download?path=%2Ftmp%2Fvoice%20note.m4a",
            MediaUrls.forDownload("http://host:9119/", note),
        )
    }

    @Test
    fun theEncodedQueryDecodesBackToThePath() {
        val url = MediaUrls.forImage("http://host:9119", awkward)

        // `#` and `&` are what make encoding matter: unencoded they would end the
        // path at a fragment and invent a second parameter.
        assertFalse(url.substringAfter('?').contains('&'))
        assertEquals(awkward.path, URLDecoder.decode(url.substringAfter("path="), "UTF-8"))
    }
}
