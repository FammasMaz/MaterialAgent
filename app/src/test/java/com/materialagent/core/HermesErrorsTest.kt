package com.materialagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which gateway errors mean "this session id is gone", because that answer
 * decides whether a lost message gets recovered or thrown away.
 *
 * The app keeps a runtime session handle in memory and reuses it for every turn.
 * The handle dies in two ways, and the recovery in `ChatController.withLiveSession`
 * only re-resumes and retries when the error is classified as gone. Missing
 * either code means the user's message is dropped silently — which is exactly
 * what happened against a real gateway: the submit failed with `4007`, was not
 * treated as recoverable, and never reached the server.
 */
class HermesErrorsTest {

    @Test
    fun aStoredIdUsedWhereARuntimeIdIsNeededIsRecoverable() {
        assertTrue(HermesRpcException(4001, "session not found").isSessionGone)
    }

    @Test
    fun aRuntimeHandleInvalidatedByAReconnectIsRecoverable() {
        // The case that bit: the socket dropped, the gateway forgot the runtime
        // handle, and reusing it answers 4007 rather than 4001.
        assertTrue(HermesRpcException(4007, "session not found").isSessionGone)
    }

    @Test
    fun anActiveSessionConflictIsNotGone() {
        // "Cannot delete an active session" means the session is very much alive;
        // treating it as gone would resume a session we already know exists.
        assertFalse(HermesRpcException(4023, "cannot delete an active session").isSessionGone)
    }

    @Test
    fun unrelatedFailuresAreNotRecoverableThisWay() {
        assertFalse(HermesRpcException(-32000, "internal error").isSessionGone)
        assertFalse(HermesRpcException(null, "no code").isSessionGone)
        assertFalse(HermesRpcException(4002, "unknown config key").isSessionGone)
    }

    @Test
    fun codesAndMessagesSurviveTheRoundTrip() {
        val error = HermesRpcException(4007, "session not found")
        assertEquals(4007, error.code)
        assertEquals("session not found", error.message)
    }
}
