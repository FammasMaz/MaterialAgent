package com.materialagent.data.update

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Guards [ApkVerifier], the gate between a download and the installer.
 *
 * The invariants: a file reaches the installer only when it hashes to the digest
 * the release published, declares this app's own package name, and carries the
 * same signing certificate as the installed app. Every other combination is
 * refused — an update that fails any of these could not be installed anyway, and
 * a *substituted* one would be indistinguishable from the real thing by then.
 */
class ApkVerifierTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun acceptsAFileMatchingThePublishedDigestPackageAndSigner() {
        val bytes = "a plausible apk".toByteArray()
        val inspector = FakeInspector()
        val apk = apkContaining(bytes)

        ApkVerifier(inspector).verify(apk, Sha256.of(bytes))
    }

    @Test
    fun rejectsAFileWhoseBytesDifferFromThePublishedDigest() {
        val apk = apkContaining("what the server actually sent".toByteArray())
        val published = Sha256.of("what the release published".toByteArray())

        val failure = expectRejected(ApkVerifier(FakeInspector()), apk, published)

        assertTrue(failure.message!!.contains("checksum"))
    }

    @Test
    fun rejectsAnArchiveBuiltForADifferentApplication() {
        val bytes = "a plausible apk".toByteArray()
        val inspector = FakeInspector(archiveApplicationId = "com.someone.else")

        val failure = expectRejected(ApkVerifier(inspector), apkContaining(bytes), Sha256.of(bytes))

        assertTrue(failure.message!!.contains("com.someone.else"))
        assertTrue(failure.message!!.contains("com.materialagent"))
    }

    @Test
    fun rejectsAnArchiveSignedByADifferentKey() {
        val bytes = "a plausible apk".toByteArray()
        val inspector = FakeInspector(archiveSigners = listOf(SIGNER_OTHER))

        val failure = expectRejected(ApkVerifier(inspector), apkContaining(bytes), Sha256.of(bytes))

        assertTrue(failure.message!!.contains("signed by a different key"))
    }

    @Test
    fun rejectsAReleaseThatPublishedNoDigestRatherThanGuessing() {
        val apk = apkContaining("a plausible apk".toByteArray())

        val failure = expectRejected(ApkVerifier(FakeInspector()), apk, expectedSha256 = null)

        assertTrue(failure.message!!.contains("no SHA-256 checksum"))
    }

    @Test
    fun rejectsAFileThatIsNotAReadablePackage() {
        val bytes = "a plausible apk".toByteArray()
        val inspector = FakeInspector(archiveApplicationId = null)

        val failure = expectRejected(ApkVerifier(inspector), apkContaining(bytes), Sha256.of(bytes))

        assertTrue(failure.message!!.contains("not a readable Android package"))
    }

    @Test
    fun rejectsAnArchiveWithNoSigningCertificate() {
        val bytes = "a plausible apk".toByteArray()
        val inspector = FakeInspector(archiveSigners = emptyList())

        val failure = expectRejected(ApkVerifier(inspector), apkContaining(bytes), Sha256.of(bytes))

        assertTrue(failure.message!!.contains("no signing certificate"))
    }

    @Test
    fun rejectsAMultiSignerArchiveThatOnlyPartiallyMatches() {
        val bytes = "a plausible apk".toByteArray()
        // Same primary signer, one extra: Android would still refuse the upgrade,
        // so a superset is not a match.
        val inspector = FakeInspector(archiveSigners = listOf(SIGNER_THIS_APP, SIGNER_OTHER))

        val failure = expectRejected(ApkVerifier(inspector), apkContaining(bytes), Sha256.of(bytes))

        assertTrue(failure.message!!.contains("signed by a different key"))
    }

    @Test
    fun rejectsWhenTheInstalledAppReportsNoSignerToCompareAgainst() {
        val bytes = "a plausible apk".toByteArray()
        val inspector = FakeInspector(installedSigners = emptyList())

        val failure = expectRejected(ApkVerifier(inspector), apkContaining(bytes), Sha256.of(bytes))

        assertTrue(failure.message!!.contains("signed by a different key"))
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun apkContaining(bytes: ByteArray): File =
        folder.newFile("update.apk").apply { writeBytes(bytes) }

    private fun expectRejected(
        verifier: ApkVerifier,
        apk: File,
        expectedSha256: String?,
    ): ApkVerificationException {
        try {
            verifier.verify(apk, expectedSha256)
        } catch (failure: ApkVerificationException) {
            return failure
        }
        fail("Expected the APK to be rejected")
        error("unreachable")
    }

    /**
     * Stands in for `PackageManager`, which cannot be reached from a JVM test —
     * and would be the wrong thing to mock anyway: what is under test is the
     * decision, not the platform's ability to read a manifest.
     */
    private class FakeInspector(
        private val installedApplicationId: String = "com.materialagent",
        private val installedSigners: List<String> = listOf(SIGNER_THIS_APP),
        private val archiveApplicationId: String? = "com.materialagent",
        private val archiveSigners: List<String> = listOf(SIGNER_THIS_APP),
    ) : ApkInspector {

        override fun installedApplicationId(): String = installedApplicationId

        override fun installedSigners(): List<String> = installedSigners

        override fun applicationIdOf(apk: File): String? = archiveApplicationId

        override fun signersOf(apk: File): List<String> = archiveSigners
    }

    private companion object {
        /** Both are 64-character hex strings; nothing here depends on their value. */
        const val SIGNER_THIS_APP = "1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f"
        const val SIGNER_OTHER = "2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e"
    }
}