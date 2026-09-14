package com.materialagent.data.update

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Guards [Sha256], the arithmetic that decides whether a downloaded APK is the
 * file the release published.
 *
 * The invariants: hashing a file and hashing its bytes agree, and a digest can be
 * read out of both shapes the release workflow publishes — a `sha256sum` file and
 * a `SHA256:` line in the release notes — with nothing else treated as a digest.
 */
class Sha256Test {

    @get:Rule
    val folder = TemporaryFolder()

    private val digest = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

    @Test
    fun hashingAFileMatchesHashingItsBytes() {
        val bytes = "abc".toByteArray()
        val file: File = folder.newFile("asset.apk").apply { writeBytes(bytes) }

        assertEquals(Sha256.of(bytes), Sha256.of(file))
    }

    @Test
    fun hashingIsTheKnownSha256OfTheInput() {
        // The published reference vector for "abc", so a broken hex encoder or a
        // wrong algorithm shows up here rather than as an unexplained mismatch.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.of("abc".toByteArray()),
        )
    }

    @Test
    fun readsADigestOutOfAChecksumAsset() {
        val asset = "$digest  MaterialAgent-1.0.0-beta.9-signed.apk\n"

        assertEquals(digest, Sha256.parse(asset))
    }

    @Test
    fun readsALabelledDigestOutOfAReleaseBody() {
        val body = "## What's new\n\nFixed things.\n\n### Checksum\n```\nSHA256: $digest\n```\n"

        assertEquals(digest, Sha256.parse(body))
    }

    @Test
    fun aLabelledDigestOutranksAnEarlierUnlabelledOne() {
        val other = "0".repeat(64)
        val body = "Built from $other\n\nSHA256: $digest\n"

        assertEquals(digest, Sha256.parse(body))
    }

    @Test
    fun normalisesUppercaseAndWhitespace() {
        assertEquals(digest, Sha256.normalize("  ${digest.uppercase()}  "))
    }

    @Test
    fun refusesAnythingThatIsNotAFullHexDigest() {
        assertNull(Sha256.normalize(null))
        assertNull(Sha256.normalize(""))
        assertNull(Sha256.normalize(digest.dropLast(1)))
        assertNull(Sha256.normalize("z".repeat(64)))
    }

    @Test
    fun findsNoDigestWhereThereIsNone() {
        assertNull(Sha256.parse(null))
        assertNull(Sha256.parse(""))
        assertNull(Sha256.parse("## What's new\n\nNothing to see here.\n"))
        assertEquals(digest, Sha256.parse("garbage  $digest  garbage"))
    }
}