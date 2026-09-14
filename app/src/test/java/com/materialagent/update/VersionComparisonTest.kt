package com.materialagent.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards [VersionComparison], the arithmetic behind "is this release newer?".
 *
 * The invariants: any GitHub tag that names a real version normalises to one
 * integer, and a candidate is offered only when it is strictly greater than the
 * running version — a malformed tag, or a version already skipped by the user,
 * never produces an update prompt.
 */
class VersionComparisonTest {

    // ── versionCode ─────────────────────────────────────────────────────────

    @Test
    fun threePartVersionsPackMajorMinorPatch() {
        assertEquals(1_002_003, VersionComparison.versionCode("1.2.3"))
        assertEquals(2_010_000, VersionComparison.versionCode("2.10.0"))
        assertEquals(10_000_000, VersionComparison.versionCode("10.0.0"))
    }

    @Test
    fun leadingVisIgnoredRegardlessOfCase() {
        assertEquals(1_002_003, VersionComparison.versionCode("v1.2.3"))
        assertEquals(1_002_003, VersionComparison.versionCode("V1.2.3"))
    }

    @Test
    fun twoPartVersionsLeavePatchAtZero() {
        assertEquals(1_002_000, VersionComparison.versionCode("1.2"))
        assertEquals(1_002_000, VersionComparison.versionCode("v1.2"))
    }

    @Test
    fun singlePartVersionIsMajorOnly() {
        assertEquals(1_000_000, VersionComparison.versionCode("1"))
        assertEquals(7_000_000, VersionComparison.versionCode("v7"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals(1_002_003, VersionComparison.versionCode("  1.2.3  "))
    }

    @Test
    fun buildMetadataAfterTheTripleIsDropped() {
        // The debug build's VERSION_NAME is "1.0.0-debug"; if that suffix defeated
        // the parse, the app could not read its own version.
        assertEquals(1_000_000, VersionComparison.versionCode("1.0.0-debug"))
        assertEquals(1_002_003, VersionComparison.versionCode("1.2.3-beta.1"))
        assertEquals(1_002_003, VersionComparison.versionCode("v1.2.3+build7"))
    }

    @Test
    fun unparseableVersionsCollapseToZero() {
        assertEquals(0, VersionComparison.versionCode(""))
        assertEquals(0, VersionComparison.versionCode("   "))
        assertEquals(0, VersionComparison.versionCode("latest"))
        assertEquals(0, VersionComparison.versionCode("v"))
        assertEquals(0, VersionComparison.versionCode("1.2.3.4"))
    }

    // ── isNewer ─────────────────────────────────────────────────────────────

    @Test
    fun newerVersionsAreOffered() {
        assertTrue(VersionComparison.isNewer("1.0.0", "1.0.1"))
        assertTrue(VersionComparison.isNewer("1.0.0", "1.1.0"))
        assertTrue(VersionComparison.isNewer("1.0.0", "2.0.0"))
        assertTrue(VersionComparison.isNewer("1.9.9", "v1.10.0"))
    }

    @Test
    fun olderAndEqualVersionsAreNot() {
        assertFalse(VersionComparison.isNewer("1.2.3", "1.2.2"))
        assertFalse(VersionComparison.isNewer("1.2.3", "1.2.3"))
        assertFalse(VersionComparison.isNewer("1.2.3", "v1.2.3"))
        assertFalse(VersionComparison.isNewer("2.0.0", "1.99.99"))
    }

    @Test
    fun aSkippedVersionIsNotOffered() {
        assertFalse(VersionComparison.isNewer("1.0.0", "1.1.0", skipped = "1.1.0"))
        assertFalse(VersionComparison.isNewer("1.0.0", "v1.1.0", skipped = "1.1"))
        assertTrue(VersionComparison.isNewer("1.0.0", "1.2.0", skipped = "1.1.0"))
        assertTrue(VersionComparison.isNewer("1.0.0", "1.1.0", skipped = null))
    }

    @Test
    fun malformedCandidatesNeverPrompt() {
        assertFalse(VersionComparison.isNewer("1.0.0", ""))
        assertFalse(VersionComparison.isNewer("1.0.0", "latest"))
        assertFalse(VersionComparison.isNewer("1.0.0", "v"))
        assertFalse(VersionComparison.isNewer("1.0.0", "1.2.3.4"))
    }

    @Test
    fun malformedCurrentVersionStillAllowsARealUpdate() {
        // A build that cannot read its own version should not be stranded: any
        // well-formed candidate is newer than an unknown one.
        assertTrue(VersionComparison.isNewer("", "1.0.0"))
        assertTrue(VersionComparison.isNewer("dev", "1.0.0"))
    }
}
