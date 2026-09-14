package com.materialagent.core

/**
 * An error returned by the Hermes gateway as a JSON-RPC `error` object.
 *
 * Codes seen on the wire so far (see `docs/PROTOCOL.md`):
 *  * `4001` session not found
 *  * `4002` unknown config key
 *  * `4007` session not found for a runtime handle that has been invalidated
 *  * `4023` cannot delete an active session
 */
class HermesRpcException(
    val code: Int?,
    override val message: String,
) : Exception(message) {

    /**
     * True when the gateway no longer knows this session id.
     *
     * Two codes mean that, and both have to be here. `4001` is the plain
     * "session not found" a *stored* id gets when it is used for an operation that
     * needs a runtime one. `4007` is what a runtime handle returns once it has
     * been invalidated — a closed session, or, the case that actually bites, a
     * handle the client is still holding after the socket dropped and the gateway
     * forgot it. The recovery path keys off this flag: while only `4001` counted,
     * a submit from a resumed app failed with `4007`, was not treated as
     * recoverable, and the user's message disappeared with no error they could
     * act on.
     */
    val isSessionGone: Boolean get() = code == 4001 || code == 4007

    /** True when the server refused because the session is still live. */
    val isActiveSessionConflict: Boolean get() = code == 4023
}

/** Transport-level failure (socket closed, connect timeout, bad URL…). */
class HermesTransportException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
