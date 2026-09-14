package com.materialagent.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Which credential the server will accept for the WebSocket upgrade. */
enum class AuthMode { TOKEN, PASSWORD }

/**
 * URL arithmetic for the gateway endpoints.
 *
 * Users type things like `192.168.1.27:9119`, `http://example:9119/` or
 * `https://hermes.example.com/hermes`. Normalisation is forgiving on input and
 * strict on output so a typo surfaces as "could not reach" instead of a
 * mysteriously failing socket.
 */
object HermesUrl {

    /** Hosts we can safely assume are plain HTTP when no scheme is given. */
    private val LOOPBACK_PREFIXES = listOf("localhost", "127.0.0.1", "10.0.2.2", "0.0.0.0")

    /**
     * Turns user input into a canonical `http(s)://host[:port][/base]` string,
     * or null when it cannot be parsed.
     */
    fun normalize(input: String): String? {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null

        // Strip by length, not by exact prefix text: the scheme check above is
        // case-insensitive, so `WSS://host` must lose the same seven characters
        // as `wss://host` rather than surviving the strip and becoming a host.
        val scheme = trimmed.substringBefore("://", "").lowercase()
        val rest = if (scheme.isNotEmpty()) trimmed.substring(scheme.length + 3) else trimmed
        val withScheme = when (scheme) {
            "http", "https" -> "$scheme://$rest"
            "ws" -> "http://$rest"
            "wss" -> "https://$rest"
            else -> "http://$trimmed"
        }

        val url = withScheme.toHttpUrlOrNull() ?: return null
        if (url.host.isBlank()) return null
        // Strip a path the user may have pasted from a browser tab.
        val cleaned = url.newBuilder()
            .query(null)
            .fragment(null)
            .build()
        val path = cleaned.encodedPath.trimEnd('/')
            .removeSuffix("/api/ws")
            .removeSuffix("/auth/login")
            .removeSuffix("/login")
        return cleaned.newBuilder().encodedPath(path.ifEmpty { "/" }).build().toString().trimEnd('/')
    }

    /** True when the host is the device itself or the emulator's host alias. */
    fun isLoopback(base: String): Boolean {
        val url = base.toHttpUrlOrNull() ?: return false
        return LOOPBACK_PREFIXES.any { url.host.equals(it, ignoreCase = true) } || url.host.endsWith(".local")
    }

    /**
     * The WebSocket URL for the gateway.
     *
     * `authParam` is the pair the server expects on upgrade: `token` for a
     * loopback bind, `ticket` for a gated one after an HTTP login.
     */
    fun wsUrl(
        base: String,
        authParam: Pair<String, String>? = null,
        profile: String? = null,
    ): String? {
        val httpUrl = base.toHttpUrlOrNull() ?: return null
        val builder = httpUrl.newBuilder()
            .encodedPath(httpUrl.encodedPath.trimEnd('/') + "/api/ws")
            .query(null)
            .fragment(null)
        if (authParam != null && authParam.second.isNotBlank()) {
            builder.addQueryParameter(authParam.first, authParam.second)
        }
        if (!profile.isNullOrBlank()) {
            // `profile` is the query key the server reads from ws.query_params.
            builder.addQueryParameter("profile", profile)
        }
        val resolved = builder.build().toString()
        return if (httpUrl.isHttps) {
            resolved.replaceFirst("https://", "wss://")
        } else {
            resolved.replaceFirst("http://", "ws://")
        }
    }

    fun endpoint(base: String, path: String): HttpUrl? {
        val httpUrl = base.toHttpUrlOrNull() ?: return null
        return httpUrl.newBuilder().encodedPath(httpUrl.encodedPath.trimEnd('/') + path).build()
    }
}
