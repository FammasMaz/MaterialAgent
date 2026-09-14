package com.materialagent.core

import com.materialagent.core.model.AttachmentPrompt
import com.materialagent.core.model.AttachmentRefs
import com.materialagent.core.model.AttachmentReports
import com.materialagent.core.model.MediaKind
import com.materialagent.core.model.OutgoingAttachments
import com.materialagent.core.model.StoredRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the attachment contract: [AttachmentParams], [AttachmentReports] and the
 * pure parts of [OutgoingAttachments].
 *
 * This is the one place a field name can be wrong without anything complaining.
 * The gateway ignores parameters it does not recognise — `session.create` already
 * proved that, and `approval.respond` defaults a missing `choice` to `deny` — so
 * a renamed key does not produce an error, it produces a turn with no
 * attachment, or a file the agent never hears about. Pinning the key *sets* here
 * is what turns that class of silent drift into a failing build.
 *
 * The values are checked as well as the names, because two of these fields are
 * not interchangeable: `content_base64` must be raw base64 (the server strips a
 * `data:` wrapper, so a mistake is invisible) and `file.attach` must carry
 * `data_url`, without which it silently switches to resolving `path` on the
 * *server's* disk.
 */
class AttachmentContractTest {

    // ── request bodies ──────────────────────────────────────────────────────

    @Test
    fun imageAttachBytesNamesTheThreeFieldsTheServerReads() {
        val params = AttachmentParams.imageAttachBytes(
            sessionId = "rt-1",
            filename = "photo.png",
            contentBase64 = "AAEC",
        )

        assertEquals(setOf("session_id", "filename", "content_base64"), params.keys)
        assertEquals("rt-1", params.str("session_id"))
        assertEquals("photo.png", params.str("filename"))
        assertEquals("AAEC", params.str("content_base64"))
    }

    @Test
    fun fileAttachCarriesBytesAsADataUrlAndNamesTheFileSeparately() {
        val params = AttachmentParams.fileAttach(
            sessionId = "rt-1",
            path = "content://provider/doc/7",
            name = "notes.txt",
            dataUrl = "data:text/plain;base64,aGk=",
        )

        assertEquals(setOf("session_id", "path", "name", "data_url"), params.keys)
        assertEquals("rt-1", params.str("session_id"))
        assertEquals("content://provider/doc/7", params.str("path"))
        assertEquals("notes.txt", params.str("name"))
        assertEquals("data:text/plain;base64,aGk=", params.str("data_url"))
    }

    @Test
    fun imageDetachNamesThePathToRemove() {
        val params = AttachmentParams.imageDetach(sessionId = "rt-1", path = "/home/u/.hermes/images/a.png")

        assertEquals(setOf("session_id", "path"), params.keys)
        assertEquals("/home/u/.hermes/images/a.png", params.str("path"))
    }

    @Test
    fun promptSubmitCarriesTheComposedText() {
        val params = AttachmentParams.promptSubmit(sessionId = "rt-1", text = "@file:/x/a.txt\n\nread it")

        assertEquals(setOf("session_id", "text"), params.keys)
        assertEquals("@file:/x/a.txt\n\nread it", params.str("text"))
    }

    // ── replies ─────────────────────────────────────────────────────────────

    @Test
    fun anAttachedImageReportsItsPathAndQueueDepth() {
        val reply = HermesJson.parseToJsonElement(
            """{"attached":true,"path":"/home/u/.hermes/images/a.png","count":2,
                "name":"a.png","text":"[The user attached an image]"}""",
        ).objOrNull()!!

        val staged = AttachmentReports.parseImage(reply)!!

        assertEquals("/home/u/.hermes/images/a.png", staged.path)
        assertEquals(2, staged.count)
        // An image carries no prompt reference: the queue is claimed by the next
        // submit, so a ref_text here would be invented.
        assertNull(staged.refText)
    }

    @Test
    fun aRefusedImageIsNotReportedAsStaged() {
        val reply = HermesJson.parseToJsonElement(
            """{"attached":false,"message":"unsupported image type","code":4016}""",
        ).objOrNull()!!

        assertNull("a refusal must not become a chip in the transcript", AttachmentReports.parseImage(reply))
    }

    @Test
    fun anAttachedFileReportsTheReferenceThePromptMustCarry() {
        // `ref_path` (and therefore `ref_text`) is workspace-relative when the
        // staged file lands inside the session workspace and absolute when it
        // does not — a live gateway answered `@file:/home/…/attachments/x.txt`.
        // Both shapes are real; what the client must not do is rebuild the
        // reference from `name`, because the server is the one that knows where
        // it put the file.
        val reply = HermesJson.parseToJsonElement(
            """{"attached":true,"name":"notes.txt",
                "path":"/home/u/.hermes/attachments/notes.txt",
                "ref_path":"attachments/notes.txt",
                "ref_text":"@file:attachments/notes.txt","uploaded":true}""",
        ).objOrNull()!!

        val staged = AttachmentReports.parseFile(reply)!!

        assertEquals("/home/u/.hermes/attachments/notes.txt", staged.path)
        assertEquals("@file:attachments/notes.txt", staged.refText)
    }

    @Test
    fun aFileReplyWithoutAPathIsNotStaged() {
        // The server answers some refusals with a `message` and no `path`; a chip
        // built from that would show a blank name for a file nobody received.
        val reply = HermesJson.parseToJsonElement(
            """{"attached":true,"message":"could not stage"}""",
        ).objOrNull()!!

        assertNull(AttachmentReports.parseFile(reply))
    }

    @Test
    fun aRefusalIsReportedInTheServersOwnWords() {
        // The user has to act on a refusal, so the server's reason reaches them
        // verbatim — and a server that explains nothing says so.
        assertEquals(
            "unsupported image type",
            AttachmentReports.refusalMessage(
                HermesJson.parseToJsonElement("""{"attached":false,"message":"unsupported image type"}""")
                    .objOrNull()!!,
            ),
        )
        assertEquals(
            "no reason given",
            AttachmentReports.refusalMessage(
                HermesJson.parseToJsonElement("""{"attached":false,"code":4016}""").objOrNull()!!,
            ),
        )
    }

    // ── prompt composition ─────────────────────────────────────────────────

    @Test
    fun fileReferencesLeadThePromptAboveTheUsersOwnWords() {
        val prompt = AttachmentPrompt.compose(
            text = "summarise this",
            fileRefs = listOf("@file:attachments/a.pdf"),
            hasImage = false,
        )

        assertEquals("@file:attachments/a.pdf\n\nsummarise this", prompt)
    }

    @Test
    fun severalReferencesEachGetTheirOwnLine() {
        val prompt = AttachmentPrompt.compose(
            text = "what are these?",
            fileRefs = listOf("@file:attachments/a.txt", "@file:attachments/b.csv"),
            hasImage = false,
        )

        assertEquals("@file:attachments/a.txt\n@file:attachments/b.csv\n\nwhat are these?", prompt)
    }

    @Test
    fun aPictureWithNoWordsStillBecomesAPrompt() {
        // Pressing send on a bare photo is a question, and an empty `text` is
        // rejected by the gateway; the desktop sends the same stand-in.
        val prompt = AttachmentPrompt.compose(text = "   ", fileRefs = emptyList(), hasImage = true)

        assertEquals(AttachmentPrompt.IMAGE_ONLY_TEXT, prompt)
    }

    @Test
    fun blankReferencesAreDroppedRatherThanLeftAsEmptyLines() {
        val prompt = AttachmentPrompt.compose(
            text = "hello",
            fileRefs = listOf("", "  "),
            hasImage = false,
        )

        assertEquals("hello", prompt)
    }

    @Test
    fun nothingToSayAndNothingAttachedComposesToNothing() {
        assertEquals("", AttachmentPrompt.compose(text = "", fileRefs = emptyList(), hasImage = false))
    }

    // ── classification ─────────────────────────────────────────────────────

    @Test
    fun aNonImageMimeTypeStillFallsBackToTheExtension() {
        // Providers routinely report `application/octet-stream` for a photo; the
        // vision path is chosen by the server from the extension, so guessing
        // "file" here would quietly cost the user the ability to ask about it.
        assertEquals(MediaKind.IMAGE, OutgoingAttachments.kindOf("application/octet-stream", "IMG_1234.JPG"))
        assertEquals(MediaKind.IMAGE, OutgoingAttachments.kindOf("", "screenshot.webp"))
        assertEquals(MediaKind.AUDIO, OutgoingAttachments.kindOf("", "voice-note.M4A"))
    }

    @Test
    fun theMimeTypeWinsWhenItKnowsMoreThanTheName() {
        assertEquals(MediaKind.IMAGE, OutgoingAttachments.kindOf("image/heic", "no-extension"))
        assertEquals(MediaKind.AUDIO, OutgoingAttachments.kindOf("audio/ogg; codecs=opus", "clip"))
    }

    @Test
    fun videoIsStagedAsAFileBecauseTheGatewayHasNoVideoAttach() {
        assertEquals(MediaKind.FILE, OutgoingAttachments.kindOf("video/mp4", "clip.mp4"))
        assertEquals(MediaKind.FILE, OutgoingAttachments.kindOf("application/pdf", "paper.pdf"))
        assertEquals(MediaKind.FILE, OutgoingAttachments.kindOf("", "archive.zip"))
    }

    @Test
    fun aHeicPhotoIsAnImage() {
        // Extensions the server accepts but the app has no thumbnailer for: the
        // upload still has to take the vision path.
        assertEquals(MediaKind.IMAGE, OutgoingAttachments.kindOf("image/heif", "photo.heic"))
    }

    // ── payloads ───────────────────────────────────────────────────────────

    @Test
    fun aDataUrlNamesTheMimeTypeAndBase64sTheBytes() {
        val url = OutgoingAttachments.dataUrl("text/plain", "hi".toByteArray())

        assertEquals("data:text/plain;base64,aGk=", url)
    }

    @Test
    fun anUnknownMimeTypeBecomesTheGenericBinaryType() {
        // A blank or absent type produces `data:;base64,…`, which some parsers
        // reject outright; `application/octet-stream` is the defined answer.
        assertEquals(
            "data:application/octet-stream;base64,aGk=",
            OutgoingAttachments.dataUrl("   ", "hi".toByteArray()),
        )
    }

    @Test
    fun mimeParametersAreStrippedFromTheDataUrl() {
        assertEquals(
            "data:audio/ogg;base64,aGk=",
            OutgoingAttachments.dataUrl("audio/ogg; codecs=opus", "hi".toByteArray()),
        )
    }

    @Test
    fun imageBytesAreSentAsRawBase64() {
        // The server also accepts a `data:image/png;base64,` wrapper and strips
        // it, so sending one costs a third more bytes on the wire for nothing.
        assertEquals("aGk=", OutgoingAttachments.base64("hi".toByteArray()))
        assertTrue(!OutgoingAttachments.base64("hi".toByteArray()).startsWith("data:"))
    }

    // ── limits ─────────────────────────────────────────────────────────────

    @Test
    fun aFileExactlyAtTheCapIsAccepted() {
        assertNull(
            OutgoingAttachments.rejectReason(
                MediaKind.IMAGE,
                OutgoingAttachments.IMAGE_MAX_BYTES,
                "photo.png",
            ),
        )
    }

    @Test
    fun aFileOverTheCapIsNamedAlongWithTheLimit() {
        val reason = OutgoingAttachments.rejectReason(
            MediaKind.IMAGE,
            OutgoingAttachments.IMAGE_MAX_BYTES + 1,
            "huge.png",
        )

        assertTrue("the refusal must name the file: $reason", reason!!.contains("huge.png"))
        assertTrue("the refusal must state the limit: $reason", reason.contains("25 MB"))
    }

    @Test
    fun anUnknownSizeIsLeftToTheServerRatherThanRefused() {
        // A provider that reports no size gives 0. Refusing on 0 would reject
        // perfectly good files; the send path re-checks the real length after the
        // read, which is the only place it is knowable.
        assertNull(OutgoingAttachments.rejectReason(MediaKind.FILE, 0, "unknown.bin"))
    }

    @Test
    fun imagesAndOtherFilesHaveSeparateCaps() {
        assertEquals(OutgoingAttachments.IMAGE_MAX_BYTES, OutgoingAttachments.maxBytesFor(MediaKind.IMAGE))
        assertEquals(OutgoingAttachments.FILE_MAX_BYTES, OutgoingAttachments.maxBytesFor(MediaKind.AUDIO))
        assertEquals(OutgoingAttachments.FILE_MAX_BYTES, OutgoingAttachments.maxBytesFor(MediaKind.FILE))
        assertEquals(OutgoingAttachments.FILE_MAX_BYTES, OutgoingAttachments.maxBytesFor(MediaKind.VIDEO))
    }

    // ── stored references ─────────────────────────────────────────────────

    @Test
    fun aStoredImageReferenceLeavesTheUsersWordsAlone() {
        // The gateway appends its own `@image:` line to the turn it stores, so a
        // reopened conversation has to read the words back out of a message that
        // also contains a server path. The path is not the user's sentence and
        // must not be shown as if it were.
        val turn = AttachmentRefs.split(
            "What colour stripes are in order?\n" +
                "@image:/home/u/.hermes/images/upload_20260914_155013_1.png",
        )
        assertEquals("What colour stripes are in order?", turn.text)
        assertEquals(
            listOf(StoredRef(MediaKind.IMAGE, "/home/u/.hermes/images/upload_20260914_155013_1.png")),
            turn.refs,
        )
        assertEquals("upload_20260914_155013_1.png", turn.refs.single().name)
    }

    @Test
    fun aStoredFileReferenceIsRecognisedByItsKindsOwnToken() {
        val turn = AttachmentRefs.split(
            "@file:/home/u/.hermes/attachments/notes.txt\n\nRead this and summarise it.",
        )
        assertEquals("Read this and summarise it.", turn.text)
        assertEquals(listOf(MediaKind.FILE), turn.refs.map { it.kind })
        assertEquals("notes.txt", turn.refs.single().name)
    }

    @Test
    fun anImageOnlyTurnKeepsItsReferenceRatherThanBecomingEmpty() {
        // With nothing but a reference there are no words to show, and dropping
        // the line anyway would leave a blank bubble that says the user sent
        // nothing. The reference is what that turn was.
        val turn = AttachmentRefs.split("@image:/home/u/.hermes/images/upload_1.png")
        assertEquals("", turn.text)
        assertEquals(1, turn.refs.size)
    }

    @Test
    fun proseThatMerelyMentionsAReferenceIsNotStripped() {
        // The rule is a line that is *nothing but* a reference. A user writing
        // about a path keeps their sentence — silently deleting half of it would
        // be a worse bug than showing a path.
        val sentence = "Why does @file:/etc/hosts get read twice here?"
        val turn = AttachmentRefs.split(sentence)
        assertEquals(sentence, turn.text)
        assertEquals(emptyList<StoredRef>(), turn.refs)
    }

    @Test
    fun aQuotedReferencePathReportsAnUnquotedName() {
        // The server quotes a path containing whitespace, so the chip has to
        // unwrap it or the user reads a name wrapped in backticks.
        val turn = AttachmentRefs.split("@file:`/home/u/my documents/quarterly report.pdf`")
        assertEquals("/home/u/my documents/quarterly report.pdf", turn.refs.single().path)
        assertEquals("quarterly report.pdf", turn.refs.single().name)
    }

    @Test
    fun blankLinesAroundTheReferencesDoNotSurviveIntoTheBubble() {
        val turn = AttachmentRefs.split("@file:/u/a.txt\n\n\nHello.\n")
        assertEquals("Hello.", turn.text)
    }

}
