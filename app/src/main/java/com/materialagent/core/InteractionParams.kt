package com.materialagent.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Request bodies for the gateway's interaction responses.
 *
 * Each method reads a *different* key, and falls back to a default when the key
 * it wants is missing: `approval.respond` reads `choice` and would otherwise
 * resolve as `"deny"`, `clarify.respond` reads `answer` and would otherwise
 * answer with an empty question, `sudo.respond` reads `password`, `secret.respond`
 * reads `value`. A single shared `"response"` field therefore reached the server,
 * was ignored, and looked like it had worked.
 *
 * Only `approval.respond` resolves its session explicitly; the others find the
 * session through the gateway's pending-request registry. These builders are the
 * one place those shapes are written down, and the live test drives them so the
 * app cannot drift from the server without failing.
 */
object InteractionParams {

    /** `choice` is the server's own vocabulary: `once`, `session`, `always`, `deny`. */
    fun approval(sessionId: String, requestId: String, choice: String): JsonObject = buildJsonObject {
        put("session_id", JsonPrimitive(sessionId))
        put("request_id", JsonPrimitive(requestId))
        put("choice", JsonPrimitive(choice))
    }

    fun clarify(requestId: String, answer: String): JsonObject = buildJsonObject {
        put("request_id", JsonPrimitive(requestId))
        put("answer", JsonPrimitive(answer))
    }

    fun sudo(requestId: String, password: String): JsonObject = buildJsonObject {
        put("request_id", JsonPrimitive(requestId))
        put("password", JsonPrimitive(password))
    }

    fun secret(requestId: String, value: String): JsonObject = buildJsonObject {
        put("request_id", JsonPrimitive(requestId))
        put("value", JsonPrimitive(value))
    }
}
