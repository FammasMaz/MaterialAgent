package com.materialagent.update

/**
 * The version arithmetic behind "is this release newer than the one running?".
 *
 * It deliberately imports nothing from Android: a plain JVM test is the only way
 * to prove this logic without publishing a release and installing it by hand.
 *
 * A version code is fixed point — `major * 1_000_000 + minor * 1_000 + patch` —
 * so ordering two releases is one integer compare instead of string parsing.
 */
object VersionComparison {

    /**
     * Parses `1.2.3`, `v1.2.3`, `1.2` or `1` into its comparable code, and
     * returns `0` for anything that is not a version.
     *
     * Metadata after the numeric triple is dropped (`1.2.3-debug`, `1.2.3+b7`),
     * because the debug build carries a `-debug` suffix in `BuildConfig.VERSION_NAME`
     * and that suffix must not stop the app reading its own version.
     */
    fun versionCode(version: String): Int {
        val numeric = version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
        val parts = numeric.split(".").map { it.toIntOrNull() }
        if (parts.isEmpty() || parts.size > 3 || parts.any { it == null }) return 0
        val (major, minor, patch) = when (parts.size) {
            1 -> Triple(parts[0]!!, 0, 0)
            2 -> Triple(parts[0]!!, parts[1]!!, 0)
            else -> Triple(parts[0]!!, parts[1]!!, parts[2]!!)
        }
        return major * 1_000_000 + minor * 1_000 + patch
    }

    /**
     * Semver precedence between two versions: negative when [a] is older, zero when
     * they are the same version, positive when [a] is newer. Null when either cannot
     * be read, so a malformed tag can never win a comparison by accident.
     *
     * The numeric triple alone is not enough once there are beta builds: to a code
     * that is only `major * 1e6 + minor * 1e3 + patch`, `1.0.0-beta.1` and
     * `1.0.0-beta.2` are the same release, so a tester on the first beta would never
     * be offered the second. Pre-release identifiers are ordered the way semver
     * orders them, which also puts any beta below its own stable release.
     */
    fun compare(a: String, b: String): Int? {
        val left = parse(a) ?: return null
        val right = parse(b) ?: return null
        for (i in 0 until 3) {
            val order = left.core[i].compareTo(right.core[i])
            if (order != 0) return order
        }
        val lp = left.preRelease
        val rp = right.preRelease
        // A release outranks its own pre-releases: 1.0.0 > 1.0.0-beta.2.
        if (lp.isEmpty() && rp.isEmpty()) return 0
        if (lp.isEmpty()) return 1
        if (rp.isEmpty()) return -1
        for (i in 0 until minOf(lp.size, rp.size)) {
            val order = compareIdentifier(lp[i], rp[i])
            if (order != 0) return order
        }
        // Everything shared matched, so the longer list is the later version.
        return lp.size.compareTo(rp.size)
    }

    /** Numeric identifiers compare as numbers and rank below alphanumeric ones. */
    private fun compareIdentifier(a: String, b: String): Int {
        val an = a.toIntOrNull()
        val bn = b.toIntOrNull()
        return when {
            an != null && bn != null -> an.compareTo(bn)
            an != null -> -1
            bn != null -> 1
            else -> a.compareTo(b)
        }
    }

    private data class Parsed(val core: List<Int>, val preRelease: List<String>)

    /**
     * Parses `1.2.3-beta.1`, `v1.2`, or `1.2.3-debug` into core numbers plus any
     * genuine pre-release identifiers.
     *
     * A trailing `-debug` is the debug *build variant*, not a pre-release — the
     * fallback release path publishes a debug-signed APK whose `VERSION_NAME` is
     * `1.0.0-debug`, and reading that as a pre-release would rank it below
     * `1.0.0-beta.1` and silently stop the updater from ever prompting. Build
     * metadata (`+b7`) is dropped for the same reason.
     */
    private fun parse(version: String): Parsed? {
        val trimmed = version.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        if (trimmed.isBlank()) return null
        val withoutVariant = trimmed.removeSuffix("-debug").removeSuffix("-dbg")
        val corePart = withoutVariant.substringBefore('-')
        val preRelease = withoutVariant.substringAfter('-', "")
            .split('.')
            .filter { it.isNotBlank() }
        val parts = corePart.split('.').map { it.toIntOrNull() ?: return null }
        if (parts.isEmpty() || parts.size > 3) return null
        val core = listOf(
            parts.getOrElse(0) { 0 },
            parts.getOrElse(1) { 0 },
            parts.getOrElse(2) { 0 },
        )
        return Parsed(core = core, preRelease = preRelease)
    }

    /**
     * True when [candidate] is strictly newer than [current] and the user has
     * not already asked to skip it.
     *
     * A tag that cannot be read is never an update: a malformed release name must
     * not turn into a download prompt on every launch.
     */
    fun isNewer(current: String, candidate: String, skipped: String? = null): Boolean {
        if (versionCode(candidate) <= 0) return false
        if (skipped != null && compare(skipped, candidate) == 0) return false
        // A build that cannot read its own version must not be stranded, so an
        // unreadable `current` accepts any well-formed candidate. `candidate` is
        // already known to be readable here, so a null means `current` is at fault.
        val order = compare(candidate, current) ?: return true
        return order > 0
    }
}
