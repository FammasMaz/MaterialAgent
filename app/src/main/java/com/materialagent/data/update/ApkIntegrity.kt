package com.materialagent.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * SHA-256 arithmetic for release assets.
 *
 * Deliberately hand-rolled over `MessageDigest` rather than a digest helper from
 * a dependency: this is the check that decides whether an installer sees a file,
 * so it should be as short and as readable as the primitive allows.
 */
object Sha256 {

    private const val HEX_DIGITS = "0123456789abcdef"
    private val ALL_HEX = Regex("[0-9a-f]{64}")

    /**
     * A labelled digest, which is how the release notes carry it (`SHA256: <hex>`).
     */
    private val LABELLED = Regex("(?i)sha256\\s*:?\\s*([0-9a-f]{64})")

    /**
     * The first bare 64-character hex token, which is what a `sha256sum` file
     * holds (`<hex>  <filename>`).
     */
    private val ANY = Regex("(?i)(?<![0-9a-f])([0-9a-f]{64})(?![0-9a-f])")

    fun of(bytes: ByteArray): String = MessageDigest.getInstance(ALGORITHM).digest(bytes).toHex()

    /** Streams [file] rather than reading it whole — an APK is tens of megabytes. */
    fun of(file: File): String {
        val digest = MessageDigest.getInstance(ALGORITHM)
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    /**
     * Extracts a digest from a checksum asset or a release body, or null when the
     * text carries none. A labelled digest wins, so a body that happens to quote
     * some other long hex string cannot outrank the one under `SHA256:`.
     */
    fun parse(text: String?): String? {
        if (text.isNullOrBlank()) return null
        LABELLED.find(text)?.let { return it.groupValues[1].lowercase() }
        return ANY.find(text)?.groupValues?.get(1)?.lowercase()
    }

    /** A 64-character hex digest in canonical (lowercase) form, or null. */
    fun normalize(value: String?): String? =
        value?.trim()?.lowercase()?.takeIf { ALL_HEX.matches(it) }

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            out.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
        }
        return out.toString()
    }

    private const val ALGORITHM = "SHA-256"
}

/**
 * The only hosts an update may be fetched from.
 *
 * Releases are published on GitHub and nowhere else, so a download that does not
 * come from GitHub is by definition not one of ours. The allow-list covers the
 * API host, the release host, and the asset CDN GitHub redirects downloads to —
 * matched by suffix because GitHub has already renamed that CDN once.
 */
object ReleaseHosts {

    private const val ASSET_CDN_SUFFIX = ".githubusercontent.com"
    private val EXACT = setOf("github.com", "api.github.com")

    /** True when [url] is an HTTPS URL on a GitHub-controlled host. */
    fun allows(url: String?): Boolean {
        val parsed = url?.toHttpUrlOrNull() ?: return false
        if (!parsed.isHttps) return false
        val host = parsed.host.lowercase()
        return host in EXACT || host.endsWith(ASSET_CDN_SUFFIX)
    }

    /** The host of [url], for a message that says where a refused download came from. */
    fun hostOf(url: String?): String? = url?.toHttpUrlOrNull()?.host
}

/**
 * The platform facts an APK check needs, isolated behind an interface so the
 * check itself is plain-JVM testable.
 */
interface ApkInspector {

    /** The application id the running app is installed as. */
    fun installedApplicationId(): String

    /** Lowercase hex SHA-256 of each certificate the installed app is signed with. */
    fun installedSigners(): List<String>

    /** The application id declared inside [apk], or null when it cannot be read. */
    fun applicationIdOf(apk: File): String?

    /** Lowercase hex SHA-256 of each certificate [apk] is signed with. */
    fun signersOf(apk: File): List<String>
}

/**
 * [ApkInspector] over the real `PackageManager`.
 *
 * Reading an archive's manifest is the only way to learn what a file *is* before
 * an installer does: the application id and the signing certificate live inside
 * the APK, and neither the URL it came from nor its size says anything about them.
 */
class PackageManagerApkInspector(private val context: Context) : ApkInspector {

    override fun installedApplicationId(): String = context.packageName

    override fun installedSigners(): List<String> =
        runCatching { signers(packageInfo(context.packageName)) }.getOrDefault(emptyList())

    override fun applicationIdOf(apk: File): String? =
        runCatching { archiveInfo(apk)?.packageName }.getOrNull()

    override fun signersOf(apk: File): List<String> =
        runCatching { signers(archiveInfo(apk)) }.getOrDefault(emptyList())

    @Suppress("DEPRECATION")
    private fun archiveInfo(apk: File): PackageInfo? =
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, signatureFlags())

    @Suppress("DEPRECATION")
    private fun packageInfo(name: String): PackageInfo? =
        context.packageManager.getPackageInfo(name, signatureFlags())

    /**
     * `GET_SIGNING_CERTIFICATES` only populates `signingInfo` from API 28; below
     * that the older `GET_SIGNATURES` flag is the only one that yields anything.
     */
    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo?): List<String> {
        if (info == null) return emptyList()
        val modern = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            null
        }
        val certificates = modern ?: info.signatures
        return certificates?.map { Sha256.of(it.toByteArray()) }.orEmpty()
    }
}

/** An APK that failed an integrity check, worded for the person who will read it. */
class ApkVerificationException(message: String) : Exception(message)

/**
 * Decides whether a downloaded file may be offered to the installer.
 *
 * Three questions, in order: is the file byte-for-byte the one the release
 * published, is it the same application this one replaces, and is it signed by
 * the same key. Android enforces the last two itself — but only after the
 * installer has started and the user has committed to the update, so a failure
 * there reads as "can't install" with no explanation. Answering them here turns
 * that into a sentence that says what went wrong, and refuses the file before the
 * platform ever sees it.
 */
class ApkVerifier(
    private val inspector: ApkInspector,
    private val expectedApplicationId: String = inspector.installedApplicationId(),
) {

    /**
     * Throws [ApkVerificationException] on the first failed check; returns
     * normally only when every check passed.
     */
    fun verify(apk: File, expectedSha256: String?) {
        val expected = Sha256.normalize(expectedSha256)
            ?: throw ApkVerificationException(
                "That release publishes no SHA-256 checksum, so the download cannot be " +
                    "verified. Nothing was installed.",
            )

        if (Sha256.of(apk) != expected) {
            throw ApkVerificationException(
                "The downloaded file does not match the checksum that release published. " +
                    "It was discarded.",
            )
        }

        val applicationId = inspector.applicationIdOf(apk)
            ?: throw ApkVerificationException(
                "The downloaded file is not a readable Android package. It was discarded.",
            )
        if (applicationId != expectedApplicationId) {
            throw ApkVerificationException(
                "The downloaded update is built for \"$applicationId\", but this app is " +
                    "installed as \"$expectedApplicationId\", so it could never replace it. " +
                    "It was discarded.",
            )
        }

        val archiveSigners = inspector.signersOf(apk).map { it.lowercase() }.toSet()
        if (archiveSigners.isEmpty()) {
            throw ApkVerificationException(
                "The downloaded file carries no signing certificate. It was discarded.",
            )
        }

        val installedSigners = inspector.installedSigners().map { it.lowercase() }.toSet()
        if (installedSigners.isEmpty() || archiveSigners != installedSigners) {
            throw ApkVerificationException(
                "The downloaded update is signed by a different key than the installed " +
                    "app, so Android would refuse it. It was discarded — install this " +
                    "version by hand from the release page instead.",
            )
        }
    }
}