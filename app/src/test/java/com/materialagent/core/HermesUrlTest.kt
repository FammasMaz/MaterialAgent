package com.materialagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards [HermesUrl], the "server address" box's arithmetic.
 *
 * The invariant: whatever a person types — bare host, host:port, a dashboard
 * URL pasted from a browser tab, an already-suffixed `/api/ws` URL — normalises
 * to one canonical `http(s)://host[:port][/base]` string, and the WebSocket URL
 * derived from it is always `<base>/api/ws` with the scheme flipped.
 */
class HermesUrlTest {

    // ── normalize ───────────────────────────────────────────────────────────

    @Test
    fun bareHostAndPortGetsPlainHttpScheme() {
        assertEquals("http://192.168.1.27:9119", HermesUrl.normalize("192.168.1.27:9119"))
    }

    @Test
    fun bareHostnameWithPortGetsPlainHttpScheme() {
        assertEquals("http://example:9119", HermesUrl.normalize("example:9119"))
    }

    @Test
    fun explicitSchemeIsPreserved() {
        assertEquals("https://hermes.example.com", HermesUrl.normalize("https://hermes.example.com"))
        assertEquals("http://localhost:9119", HermesUrl.normalize("http://localhost:9119"))
    }

    @Test
    fun trailingSlashesAreStripped() {
        assertEquals("http://example:9119", HermesUrl.normalize("http://example:9119/"))
        assertEquals("https://hermes.example.com", HermesUrl.normalize("https://hermes.example.com/"))
        assertEquals("https://hermes.example.com", HermesUrl.normalize("https://hermes.example.com///"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals(
            "http://192.168.1.27:9119",
            HermesUrl.normalize("   http://192.168.1.27:9119   "),
        )
    }

    @Test
    fun websocketSchemesAreDowngradedToHttp() {
        assertEquals("http://host:9119", HermesUrl.normalize("ws://host:9119"))
        assertEquals("https://host:9119", HermesUrl.normalize("wss://host:9119"))
    }

    /**
     * BUG PROOF — fails today.
     *
     * The scheme check is case-insensitive (`startsWith("wss://", true)`) but
     * the prefix strip is case-sensitive (`removePrefix("wss://")`). So an
     * upper-case scheme survives the strip and is then parsed as a host:
     * `WSS://host` becomes `https://wss//host` instead of `https://host`.
     * See HermesUrl.normalize, the `withScheme` when-block.
     */
    @Test
    fun uppercaseWebSocketSchemesAreNormalised() {
        assertEquals("https://host", HermesUrl.normalize("WSS://host"))
        assertEquals("http://host:9119", HermesUrl.normalize("WS://host:9119"))
    }

    @Test
    fun subpathIsKept() {
        assertEquals(
            "https://hermes.example.com/hermes",
            HermesUrl.normalize("https://hermes.example.com/hermes"),
        )
        assertEquals(
            "https://hermes.example.com/hermes",
            HermesUrl.normalize("https://hermes.example.com/hermes/"),
        )
    }

    @Test
    fun queryAndFragmentPastedFromBrowserAreDropped() {
        assertEquals(
            "https://hermes.example.com",
            HermesUrl.normalize("https://hermes.example.com/?tab=sessions#top"),
        )
    }

    @Test
    fun alreadySuffixedApiWsPathIsRemoved() {
        assertEquals("https://hermes.example.com", HermesUrl.normalize("https://hermes.example.com/api/ws"))
        assertEquals(
            "https://hermes.example.com/hermes",
            HermesUrl.normalize("https://hermes.example.com/hermes/api/ws?token=abc"),
        )
    }

    @Test
    fun dashboardLoginPathsAreRemoved() {
        assertEquals("https://hermes.example.com", HermesUrl.normalize("https://hermes.example.com/login"))
        assertEquals("https://hermes.example.com", HermesUrl.normalize("https://hermes.example.com/auth/login"))
    }

    @Test
    fun ipv6HostIsCarriedThrough() {
        assertEquals("http://[::1]:9119", HermesUrl.normalize("http://[::1]:9119"))
    }

    @Test
    fun blankAndUnparseableInputsReturnNull() {
        assertNull(HermesUrl.normalize(""))
        assertNull(HermesUrl.normalize("   "))
        assertNull(HermesUrl.normalize("/"))
        assertNull(HermesUrl.normalize("not a url"))
    }

    // ── isLoopback ──────────────────────────────────────────────────────────

    @Test
    fun loopbackHostsAreRecognised() {
        assertTrue(HermesUrl.isLoopback("http://localhost:9119"))
        assertTrue(HermesUrl.isLoopback("http://127.0.0.1:9119"))
        assertTrue(HermesUrl.isLoopback("http://10.0.2.2:9119"))
        assertTrue(HermesUrl.isLoopback("http://0.0.0.0:9119"))
        assertTrue(HermesUrl.isLoopback("http://example.local:9119"))
    }

    @Test
    fun remoteHostsAreNotLoopback() {
        assertFalse(HermesUrl.isLoopback("https://hermes.example.com"))
        assertFalse(HermesUrl.isLoopback("http://192.168.1.27:9119"))
        assertFalse(HermesUrl.isLoopback("not a url"))
    }

    // ── wsUrl ───────────────────────────────────────────────────────────────

    @Test
    fun wsUrlAppendsApiWsAndFlipsScheme() {
        assertEquals("ws://host:9119/api/ws", HermesUrl.wsUrl("http://host:9119"))
        assertEquals("wss://hermes.example.com/hermes/api/ws", HermesUrl.wsUrl("https://hermes.example.com/hermes"))
    }

    @Test
    fun wsUrlCarriesAuthParameter() {
        assertEquals(
            "ws://host:9119/api/ws?token=abc123",
            HermesUrl.wsUrl("http://host:9119", "token" to "abc123"),
        )
        val ticket = HermesUrl.wsUrl("https://host", "ticket" to "t-9")
        assertEquals("wss://host/api/ws?ticket=t-9", ticket)
    }

    @Test
    fun wsUrlOmitsBlankAuthValue() {
        assertEquals("ws://host:9119/api/ws", HermesUrl.wsUrl("http://host:9119", "token" to "  "))
    }

    @Test
    fun wsUrlCarriesProfileParameter() {
        assertEquals(
            "ws://host:9119/api/ws?profile=work",
            HermesUrl.wsUrl("http://host:9119", profile = "work"),
        )
        assertEquals(
            "ws://host:9119/api/ws?token=t&profile=work",
            HermesUrl.wsUrl("http://host:9119", "token" to "t", profile = "work"),
        )
    }

    @Test
    fun wsUrlRejectsUnparseableBase() {
        assertNull(HermesUrl.wsUrl("not a url"))
    }

    // ── endpoint ────────────────────────────────────────────────────────────

    @Test
    fun endpointAppendsPathToBase() {
        assertEquals(
            "http://host:9119/api/auth/providers",
            HermesUrl.endpoint("http://host:9119", "/api/auth/providers").toString(),
        )
        assertEquals(
            "https://hermes.example.com/hermes/api/auth/ws-ticket",
            HermesUrl.endpoint("https://hermes.example.com/hermes", "/api/auth/ws-ticket").toString(),
        )
        assertNull(HermesUrl.endpoint("not a url", "/x"))
    }
}
