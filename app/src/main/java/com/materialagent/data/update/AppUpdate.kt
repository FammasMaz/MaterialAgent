package com.materialagent.data.update

/**
 * One release read back from GitHub.
 *
 * [downloadUrl] points at the release *asset*, not the release page: the app
 * installs the APK itself, so nobody ever needs to open a browser tab for it.
 */
data class AppUpdate(
    val versionName: String,
    val versionCode: Int,
    val releaseName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val assetName: String,
    val assetSize: Long,
    val publishedAt: String,
    /** True for a beta or release candidate, so the UI can say so instead of "update". */
    val isPreRelease: Boolean = false,
    /**
     * The SHA-256 the release published for [downloadUrl], or null when it
     * published none. A null here is not "skip the check": the download is
     * refused, because an unverifiable APK is one nobody can vouch for.
     */
    val sha256: String? = null,
    /**
     * `browser_download_url` of the `<apk>.sha256` asset, when the release has
     * one. The workflow writes that asset in the same step that hashes the file,
     * so it is preferred over the digest quoted in the release body.
     */
    val sha256Url: String? = null,
)

/**
 * Everything the updater can be doing, as one immutable value the UI collects.
 *
 * There is no `Installing` state on purpose: the platform installer is a separate
 * screen and this process is stopped behind it, so a state set there would never
 * be observed. The update stays [ReadyToInstall] until it is applied or retried.
 */
sealed interface UpdateState {

    /** Nothing has been asked of the updater yet. */
    data object Idle : UpdateState

    /** A GitHub request is in flight. */
    data object Checking : UpdateState

    /** GitHub answered and there is nothing newer to install. */
    data object UpToDate : UpdateState

    /** A newer release exists and can be downloaded. */
    data class Available(val update: AppUpdate) : UpdateState

    /**
     * The APK is downloading.
     *
     * [progress] is `0f..1f`, or negative when the response carried no content
     * length — the UI draws an indeterminate bar instead of a fake 0%.
     */
    data class Downloading(val progress: Float, val update: AppUpdate) : UpdateState

    /** The APK is on disk and the installer can be launched for it. */
    data class ReadyToInstall(val update: AppUpdate, val apkPath: String) : UpdateState

    /** Something failed; [message] is shown to the user rather than swallowed. */
    data class Failed(val message: String, val canRetry: Boolean = true) : UpdateState
}

/** True when this state is worth interrupting the user with a banner. */
val UpdateState.bannerVisible: Boolean
    get() = this is UpdateState.Available ||
        this is UpdateState.Downloading ||
        this is UpdateState.ReadyToInstall
