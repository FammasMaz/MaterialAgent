package com.materialagent.core

/**
 * An error returned by the Hermes gateway as a JSON-RPC `error` object.
 *
 * Codes seen on the wire so far (see `docs/PROTOCOL.md`):
 *  * `4001` session not found
 *  * `4002` unknown config key
 *  * `4023` cannot delete an active session
 */
class HermesRpcException(
    val code: Int?,
    override val message: String,
) : Exception(message) {

    /** True when the failure means "this session id is gone", not "retry later". */
    val isSessionGone: Boolean get() = code == 4001

    /** True when the server refused because the session is still live. */
    val isActiveSessionConflict: Boolean get() = code == 4023
}

/** Transport-level failure (socket closed, connect timeout, bad URL…). */
class HermesTransportException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
