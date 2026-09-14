package com.materialagent.core

import com.materialagent.core.model.GatewayEvent
import com.materialagent.core.model.Skin
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the gateway push envelope and [GatewayEvent]'s field mapping.
 *
 * The invariant: a raw newline-delimited JSON frame is split into the same
 * five fields the production reader uses (`type`, `session_id`, `seq`,
 * `payload`), every payload accessor reads the exact wire names the server
 * emits, unknown event types survive parsing, and malformed frames are dropped
 * rather than thrown.
 *
 * [parsePush] mirrors the parsing half of `HermesClient.handleDocument`; the
 * real socket path is exercised end to end in `HermesClientTest`.
 */
class WireEventTest {

    @Test
    fun messageDeltaMapsTextAndEnvelope() {
        val event = parsePush(
            """{"jsonrpc":"2.0","method":"event","params":{
               "type":"message.delta","session_id":"s-1","seq":12,
               "payload":{"text":"Hel"}}}""",
        )!!
        assertEquals(GatewayEvent.MESSAGE_DELTA, event.type)
        assertEquals("s-1", event.sessionId)
        assertEquals(12, event.seq)
        assertEquals("Hel", event.text)
    }

    @Test
    fun messageStartCarriesOnlyTheEnvelope() {
        val event = parsePush(
            """{"jsonrpc":"2.0","method":"event","params":{
               "type":"message.start","session_id":"s-1","seq":1,"payload":{}}}""",
        )!!
        assertEquals(GatewayEvent.MESSAGE_START, event.type)
        assertEquals("s-1", event.sessionId)
        assertEquals(1, event.seq)
    }

    @Test
    fun messageCompleteMapsTextAndStatus() {
        val event = parsePush(
            """{"jsonrpc":"2.0","method":"event","params":{
               "type":"message.complete","session_id":"s-1","seq":20,
               "payload":{"text":"Done.","status":"complete","reasoning":"because"}}}""",
        )!!
        assertEquals(GatewayEvent.MESSAGE_COMPLETE, event.type)
        assertEquals("Done.", event.text)
        assertEquals("complete", event.status)
        assertEquals("because", event.payload?.str("reasoning"))
    }

    @Test
    fun thinkingDeltaMapsText() {
        val event = parsePush(
            """{"jsonrpc":"2.0","method":"event","params":{
               "type":"thinking.delta","session_id":"s-1","seq":2,
               "payload":{"text":"Reading files…"}}}""",
        )!!
        assertEquals(GatewayEvent.THINKING_DELTA, event.type)
        assertEquals("Reading files…", event.text)
    }

    @Test
    fun toolStartMapsNameIdContextAndArgs() {
        val event = parsePush(
            """{"jsonrpc":"2.0","method":"event","params":{
               "type":"tool.start","session_id":"s-1","seq":5,
               "payload":{"name":"terminal","tool_id":"call-7",
                          "context":"ls -la","args":{"command":"ls -la"}}}}""",
        )!!
        assertEquals(GatewayEvent.TOOL_START, event.type)
        assertEquals("terminal", event.name)
        assertEquals("call-7", event.toolId)
        assertEquals("ls -la", event.payload?.str("context"))
        assertEquals("ls -la", event.args?.str("command"))
    }

    @Test
    fun toolCompleteFallsBackToToolCallId() {
        val event = parsePush(
            """{"jsonrpc":"2.0","method":"event","params":{
               "type":"tool.complete","session_id":"s-1","seq":6,
               "payload":{"name":"terminal","tool_call_id":"call-7","result":"exit 0"}}}""",
        )!!
        assertEquals(GatewayEvent.TOOL_COMPLETE, event.type)
        assertEquals("call-7", event.toolId)
        assertEquals("exit 0", event.payload?.get("result").renderResult())
    }

    @Test
    fun approvalRequestMapsRequestIdAndPolicy() {
        val event = parsePush(
            """{"jsonrpc":"2.0","method":"event","params":{
               "type":"approval.request","session_id":"s-1","seq":9,
               "payload":{"request_id":"req-1","command":"rm -rf /tmp/x",
                          "description":"Delete scratch dir","allow_permanent":true}}}""",
        )!!
        assertEquals(GatewayEvent.APPROVAL_REQUEST, event.type)
        assertEquals("req-1", event.requestId)
        assertEquals(true, event.payload?.bool("allow_permanent"))
        assertEquals("rm -rf /tmp/x", event.payload?.str("command"))
    }

    @Test
    fun turnErrorMapsMessage() {
        val event = parsePush(
            """{"jsonrpc":"2.0","method":"event","params":{
               "type":"turn.error","session_id":"s-1","seq":30,
               "payload":{"message":"model refused"}}}""",
        )!!
        assertEquals(GatewayEvent.TURN_ERROR, event.type)
        // NB: GatewayEvent.text only reads "text"; turn.error carries its copy in
        // "message", which the reducer reads via strAny("text", "message").
        assertEquals("model refused", event.payload?.strAny("text", "message"))
    }

    @Test
    fun globalEventHasNoSessionOrSeq() {
        val event = parsePush(
            """{"jsonrpc":"2.0","method":"event","params":{
               "type":"gateway.ready",
               "payload":{"version":"1.4.0","replay_epoch":"ep-1",
                          "skin":{"name":"hermes","colors":{"ui_accent":"#ffcc00"}}}}}""",
        )!!
        assertEquals(GatewayEvent.GATEWAY_READY, event.type)
        assertNull(event.sessionId)
        assertNull(event.seq)
        assertEquals("1.4.0", event.payload?.str("version"))
        val skin = Skin.from(event.payload)!!
        assertEquals("hermes", skin.name)
        assertEquals("#ffcc00", skin.accent)
    }

    @Test
    fun unknownEventTypeParsesWithoutThrowing() {
        val event = parsePush(
            """{"jsonrpc":"2.0","method":"event","params":{
               "type":"some.future.event","session_id":"s-1","seq":99,
               "payload":{"text":"novel","extra":{"nested":true}}}}""",
        )!!
        assertEquals("some.future.event", event.type)
        assertEquals("novel", event.text)
        assertEquals("s-1", event.sessionId)
        assertTrue(event.payload?.obj("extra")?.bool("nested") == true)
    }

    @Test
    fun nonEventFramesAreNotTreatedAsPushes() {
        assertNull(parsePush("""{"jsonrpc":"2.0","id":"a1","result":{}}"""))
    }

    @Test
    fun malformedFramesAreDropped() {
        assertNull(parsePush("not json at all"))
        assertNull(parsePush("""{"jsonrpc":"2.0","method":"event"}"""))
        assertNull(parsePush("""{"jsonrpc":"2.0","method":"event","params":{"seq":1}}"""))
        assertNull(parsePush("""[1,2,3]"""))
    }

    @Test
    fun blankSessionIdIsNormalisedToNull() {
        val event = parsePush(
            """{"jsonrpc":"2.0","method":"event","params":{
               "type":"sessions.changed","session_id":"","payload":{}}}""",
        )!!
        assertNull(event.sessionId)
        assertEquals(GatewayEvent.SESSIONS_CHANGED, event.type)
    }

    /** Mirrors the parsing half of `HermesClient.handleDocument`. */
    private fun parsePush(raw: String): GatewayEvent? {
        val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return null
        val obj = element.objOrNull() ?: return null
        if (obj.str("method") != "event") return null
        val params = obj.obj("params") ?: return null
        val type = params.str("type") ?: return null
        return GatewayEvent(
            type = type,
            sessionId = params.str("session_id")?.takeIf { it.isNotBlank() },
            seq = params.int("seq"),
            payload = params.obj("payload"),
        )
    }
}
