package com.materialagent.data

import java.util.concurrent.TimeUnit
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * Minimal in-memory cookie jar for the dashboard login handshake.
 *
 * The gated flow is: POST `/auth/password-login` (sets a session cookie) then
 * POST `/api/auth/ws-ticket` (needs that cookie). Nothing else in the app uses
 * cookies, so a process-lifetime jar is enough — and re-logging in on each
 * launch is cheap.
 */
class MemoryCookieJar : CookieJar {
    private val store = mutableMapOf<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val bucket = store.getOrPut(url.host) { mutableListOf() }
        cookies.forEach { fresh ->
            bucket.removeAll { it.name == fresh.name }
            bucket.add(fresh)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val bucket = store[url.host] ?: return emptyList()
        bucket.removeAll { it.expiresAt < now }
        return bucket.filter { it.matches(url) }
    }

    fun clear() = store.clear()
}

object Http {

    /**
     * One client for the whole app. `readTimeout` is generous because a
     * non-streaming RPC (session resume, model catalogue, TTS) can legitimately
     * take tens of seconds on a loaded gateway; the WebSocket has its own,
     * separate read path and is unaffected by this.
     */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .cookieJar(MemoryCookieJar())
        .build()
}
