package com.materialagent.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards [ReleaseHosts], the download pin.
 *
 * The invariant: an update may only be fetched over HTTPS from a host GitHub
 * controls. Everything else — another scheme, another domain, a look-alike
 * domain, a plain address — is refused, because releases are published on GitHub
 * and nowhere else.
 */
class ReleaseHostsTest {

    @Test
    fun allowsTheApiAndReleaseHosts() {
        assertTrue(ReleaseHosts.allows("https://api.github.com/repos/a/b/releases"))
        assertTrue(ReleaseHosts.allows("https://github.com/a/b/releases/download/v1/app.apk"))
    }

    @Test
    fun allowsTheAssetCdnGitHubRedirectsTo() {
        // Both names have served release assets; GitHub has renamed the CDN once
        // already, so the policy is on the namespace rather than one hostname.
        assertTrue(ReleaseHosts.allows("https://objects.githubusercontent.com/some/path"))
        assertTrue(ReleaseHosts.allows("https://release-assets.githubusercontent.com/some/path"))
    }

    @Test
    fun refusesAnythingOffTheGitHubNamespace() {
        assertFalse(ReleaseHosts.allows("https://example.com/app.apk"))
        assertFalse(ReleaseHosts.allows("https://github.com.evil.example/app.apk"))
        assertFalse(ReleaseHosts.allows("https://githubusercontent.com/app.apk"))
    }

    @Test
    fun refusesCleartextEvenFromGitHub() {
        assertFalse(ReleaseHosts.allows("http://github.com/a/b/app.apk"))
        assertFalse(ReleaseHosts.allows("http://objects.githubusercontent.com/a/b/app.apk"))
    }

    @Test
    fun refusesWhatCannotBeParsed() {
        assertFalse(ReleaseHosts.allows(null))
        assertFalse(ReleaseHosts.allows(""))
        assertFalse(ReleaseHosts.allows("not a url"))
    }

    @Test
    fun namesTheHostOfARefusedUrl() {
        assertEquals("example.com", ReleaseHosts.hostOf("https://example.com/app.apk"))
        assertEquals(null, ReleaseHosts.hostOf("not a url"))
    }
}