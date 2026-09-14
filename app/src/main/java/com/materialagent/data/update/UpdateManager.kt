package com.materialagent.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.materialagent.BuildConfig
import com.materialagent.data.SettingsStore
import com.materialagent.update.VersionComparison
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The whole update lifecycle: ask GitHub, download the APK, hand it to the
 * platform installer.
 *
 * It exists because the app ships from GitHub Releases and nowhere else, so
 * "is there a newer build?" is a question the app has to answer for itself.
 * Every decision it makes is published as [state] so the banner and the settings
 * card cannot disagree, and a failure is always reported rather than smoothed
 * into "up to date".
 *
 * Installable only when `BuildConfig.EXTERNAL_UPDATES_ENABLED` is true; every
 * entry point below returns immediately otherwise, so one flag turns the whole
 * updater off without touching call sites.
 */
class UpdateManager(
    private val context: Context,
    private val settings: SettingsStore,
    private val api: GitHubReleaseApiClient,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val updateDir: File get() = File(context.cacheDir, "updates")

    private var downloadJob: Job? = null

    /** The APK path whose install was parked for an "install unknown apps" trip. */
    private var awaitingInstallPermission: String? = null

    val currentVersionName: String get() = BuildConfig.VERSION_NAME

    /**
     * Asks GitHub for the newest release.
     *
     * Automatic callers get the once-a-day gate; the Settings button passes
     * [force] so a deliberate tap always reaches the network.
     */
    suspend fun checkForUpdates(force: Boolean = false): UpdateState {
        if (!BuildConfig.EXTERNAL_UPDATES_ENABLED) return _state.value
        if (_state.value is UpdateState.Downloading) return _state.value

        val preference = settings.snapshot()
        if (!force) {
            if (!preference.autoCheckUpdates) return _state.value
            val sinceLast = System.currentTimeMillis() - settings.lastUpdateCheck()
            if (sinceLast in 0 until CHECK_INTERVAL_MS) return _state.value
        }

        _state.value = UpdateState.Checking
        return api.fetchLatest().fold(
            onSuccess = { update ->
                settings.markUpdateCheck(System.currentTimeMillis())
                _state.value = if (
                    VersionComparison.isNewer(
                        current = currentVersionName,
                        candidate = update.versionName,
                        skipped = preference.skippedVersion,
                    )
                ) {
                    UpdateState.Available(update)
                } else {
                    UpdateState.UpToDate
                }
                _state.value
            },
            onFailure = { error ->
                _state.value = UpdateState.Failed(error.message ?: "Could not reach GitHub")
                _state.value
            },
        )
    }

    /** Starts the download in the container's scope; progress lands in [state]. */
    fun download(update: AppUpdate) {
        if (!BuildConfig.EXTERNAL_UPDATES_ENABLED) return
        if (_state.value is UpdateState.Downloading) return
        downloadJob = scope.launch {
            _state.value = UpdateState.Downloading(progress = 0f, update = update)
            try {
                val apk = fetchApk(update)
                _state.value = UpdateState.ReadyToInstall(update, apk.absolutePath)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = UpdateState.Failed(error.message ?: "Download failed")
            }
        }
    }

    /**
     * Hands a downloaded APK to the platform installer.
     *
     * A device without the "install unknown apps" grant is sent to that system
     * setting first, and the install resumes on return — an updater that quietly
     * does nothing after a permission round-trip reads as broken.
     */
    fun install() {
        if (!BuildConfig.EXTERNAL_UPDATES_ENABLED) return
        val ready = _state.value as? UpdateState.ReadyToInstall ?: return
        val apk = File(ready.apkPath)
        if (!apk.exists()) {
            _state.value = UpdateState.Failed("The downloaded file is gone. Download it again.")
            return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            awaitingInstallPermission = ready.apkPath
            openUnknownSourcesSettings()
            return
        }
        launchInstaller(apk)
    }

    /** Called when the app resumes, to finish an install the user stepped away for. */
    fun onReturnFromSettings() {
        if (!BuildConfig.EXTERNAL_UPDATES_ENABLED) return
        val path = awaitingInstallPermission ?: return
        if (!context.packageManager.canRequestPackageInstalls()) return
        awaitingInstallPermission = null
        val apk = File(path)
        if (apk.exists()) launchInstaller(apk)
    }

    /** Stops an in-flight download and puts the release back up for offer. */
    fun cancelDownload() {
        if (!BuildConfig.EXTERNAL_UPDATES_ENABLED) return
        val current = _state.value
        if (current !is UpdateState.Downloading) return
        downloadJob?.cancel()
        downloadJob = null
        _state.value = UpdateState.Available(current.update)
    }

    /** Remembers the version the user does not want to hear about again. */
    suspend fun skipVersion() {
        if (!BuildConfig.EXTERNAL_UPDATES_ENABLED) return
        val current = _state.value
        if (current !is UpdateState.Available) return
        settings.update { it.copy(skippedVersion = current.update.versionName) }
        _state.value = UpdateState.UpToDate
    }

    /** Hides the banner without silencing the version: it returns on next launch. */
    fun dismiss() {
        if (!BuildConfig.EXTERNAL_UPDATES_ENABLED) return
        val current = _state.value
        if (current is UpdateState.Available || current is UpdateState.ReadyToInstall) {
            _state.value = UpdateState.UpToDate
        }
    }

    private suspend fun fetchApk(update: AppUpdate): File = withContext(Dispatchers.IO) {
        updateDir.mkdirs()
        // A previous release's APK is never reused — it is already installed or
        // superseded — so it only costs cache space while it sits there.
        updateDir.listFiles()?.forEach { stale ->
            if (stale.name.endsWith(".apk")) stale.delete()
        }

        val target = File(updateDir, "MaterialAgent-${update.versionName}.apk")
        val request = Request.Builder().url(update.downloadUrl).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
            val body = response.body ?: throw IOException("Download returned no body")
            val total = body.contentLength()

            body.byteStream().use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (currentCoroutineContext().isActive) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        written += read
                        _state.value = UpdateState.Downloading(
                            progress = if (total > 0) {
                                (written.toFloat() / total).coerceIn(0f, 1f)
                            } else {
                                INDETERMINATE
                            },
                            update = update,
                        )
                    }
                    // A cancelled download must never hand back a truncated APK.
                    if (!currentCoroutineContext().isActive) {
                        throw CancellationException("Update download cancelled")
                    }
                }
            }
        }
        target
    }

    private fun launchInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            _state.value = UpdateState.Failed("No app on this device accepted the installer.")
        }
    }

    private fun openUnknownSourcesSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            _state.value = UpdateState.Failed(
                "Allow \"install unknown apps\" for MaterialAgent to install updates.",
            )
        }
    }

    private companion object {
        /**
         * One automatic check a day: often enough to be useful, rare enough that
         * GitHub's unauthenticated rate limit is never a real concern.
         */
        const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

        /** [UpdateState.Downloading.progress] value meaning "length unknown". */
        const val INDETERMINATE = -1f
    }
}
