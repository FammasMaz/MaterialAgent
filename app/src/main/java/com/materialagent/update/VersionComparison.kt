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
     * True when [candidate] is strictly newer than [current] and the user has
     * not already asked to skip it.
     *
     * A tag that cannot be read is never an update: a malformed release name must
     * not turn into a download prompt on every launch.
     */
    fun isNewer(current: String, candidate: String, skipped: String? = null): Boolean {
        val candidateCode = versionCode(candidate)
        if (candidateCode <= 0) return false
        if (skipped != null && versionCode(skipped) == candidateCode) return false
        return candidateCode > versionCode(current)
    }
}
