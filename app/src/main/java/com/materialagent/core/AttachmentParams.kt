package com.materialagent.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Request bodies for the gateway's attachment RPCs.
 *
 * Every key here was read off the server's own implementation and then confirmed
 * against a running gateway, because these methods fail *quietly*: an unknown key
 * is ignored, not rejected. `session.create` already demonstrated the shape of
 * that trap (a renamed parameter simply does nothing), and the interaction RPCs
 * have the same property — `approval.respond` reads `choice` and defaults to
 * `deny`, so a wrong key denies instead of erroring.
 *
 * The four methods below are the whole attachment contract:
 *
 *  * `image.attach_bytes` — bytes in, a gateway-side path out, queued in the
 *    session for the next `prompt.submit`.
 *  * `file.attach` — bytes in, a `@file:` reference out that the client must put
 *    in the prompt itself.
 *  * `image.detach` — takes one path back out of that image queue.
 *  * `prompt.submit` — the turn that consumes the queue.
 */
object AttachmentParams {

    /**
     * Stages image bytes on the gateway.
     *
     * [contentBase64] is raw base64; the server also accepts a
     * `data:image/png;base64,` wrapper and strips it, but the desktop sends it
     * bare and there is no reason to put a third more bytes on the wire.
     *
     * [filename] is only a hint — the server prefers its extension and falls
     * back to sniffing magic bytes, then to `.png`. Passing the real name keeps
     * the stored file recognisable in the agent's workspace.
     */
    fun imageAttachBytes(sessionId: String, filename: String, contentBase64: String): JsonObject =
        buildJsonObject {
            put("session_id", JsonPrimitive(sessionId))
            put("filename", JsonPrimitive(filename))
            put("content_base64", JsonPrimitive(contentBase64))
        }

    /**
     * Stages a non-image file and returns its `@file:` reference.
     *
     * Three fields matter and each has a failure mode:
     *
     *  * `data_url` carries the bytes. Without it the server tries to resolve
     *    [path] on *its own* filesystem and fails with `file not found on gateway
     *    and no data_url provided`.
     *  * `name` is what the file is called once staged. It is passed explicitly
     *    because the fallback is the last segment of [path], which for a
     *    `content://` URI is a meaningless id.
     *  * [path] is the client-side path, sent for fidelity with the desktop and
     *    never expected to exist on the server. It must not be a *relative* name:
     *    the server resolves it against its own working directory first, and a
     *    bare `photo.jpg` could match an unrelated file already there, which would
     *    silently attach the wrong file instead of uploading this one.
     */
    fun fileAttach(sessionId: String, path: String, name: String, dataUrl: String): JsonObject =
        buildJsonObject {
            put("session_id", JsonPrimitive(sessionId))
            put("path", JsonPrimitive(path))
            put("name", JsonPrimitive(name))
            put("data_url", JsonPrimitive(dataUrl))
        }

    /**
     * Takes one image back out of the session's queue.
     *
     * Needed because the queue outlives a failed turn: if a batch of three images
     * is staged and the submit then fails, those images are still waiting and the
     * *next* message, whatever it is, would carry them. Releasing them is what
     * makes "try again" mean the same thing to the user and to the server.
     */
    fun imageDetach(sessionId: String, path: String): JsonObject = buildJsonObject {
        put("session_id", JsonPrimitive(sessionId))
        put("path", JsonPrimitive(path))
    }

    /** The turn itself. [text] must already carry any `@file:` references. */
    fun promptSubmit(sessionId: String, text: String): JsonObject = buildJsonObject {
        put("session_id", JsonPrimitive(sessionId))
        put("text", JsonPrimitive(text))
    }
}
