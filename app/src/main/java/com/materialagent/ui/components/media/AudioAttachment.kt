package com.materialagent.ui.components.media

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.materialagent.core.model.MediaRef
import com.materialagent.data.HapticCue
import com.materialagent.ui.rememberCue
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The inline audio player.
 *
 * Audio is the one attachment the user asked for by name, and the one that has
 * to behave like an instrument rather than like a link: a play control that
 * answers immediately, a bar that follows the recording and can be scrubbed,
 * and a clock. Everything here exists to keep those three true while the row is
 * scrolling in and out of a transcript.
 */

/**
 * One audio row's playback.
 *
 * Kept in a plain object rather than in the composable so the MediaPlayer's
 * callbacks, its state machine and its lifetime live in one place. Two things
 * about MediaPlayer make that necessary: it reports nothing on its own (there is
 * no position callback, so the UI has to poll it), and it is a scarce system
 * resource that must be released the moment the row leaves the composition.
 */
@Stable
internal class AudioPlayback(
    private val context: Context,
    private val url: String,
    private val headers: Map<String, String>,
    private val scope: CoroutineScope,
    private val fallback: suspend () -> Result<File>,
) {

    var isPlaying by mutableStateOf(false)
        private set
    var isPreparing by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var player: MediaPlayer? = null

    /** True once streaming was abandoned for the cached copy; a second failure is terminal. */
    private var playingFromCache = false

    /** The in-flight fallback download, so a deliberate retry can replace it. */
    private var fallbackJob: Job? = null

    fun toggle() {
        when {
            // Nothing is loaded yet: the player is created on the first tap rather
            // than with the row, so a transcript full of voice notes holds no
            // codecs at all until one is actually played.
            player == null -> stream()
            isPlaying -> pause()
            else -> resume()
        }
    }

    /** Called on a timer while playing: MediaPlayer has no position callback. */
    fun tick() {
        val current = player ?: return
        if (isPlaying) positionMs = current.currentPosition.toLong().coerceAtLeast(0L)
    }

    fun seekTo(millis: Long) {
        val target = millis.coerceAtLeast(0L).coerceAtMost(durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
        player?.let { runCatching { it.seekTo(target.toInt()) } }
        positionMs = target
    }

    fun release() = discard()

    // ── loading ─────────────────────────────────────────────────────────────

    /**
     * Streams the file straight from the gateway.
     *
     * The cookie has to be handed over as an explicit header here: MediaPlayer
     * fetches on its own, outside OkHttp, so it never sees the client's cookie
     * jar. Range requests are the platform's own, which is what makes scrubbing
     * a remote recording work.
     */
    private fun stream() {
        // A retry the user asked for supersedes a fallback still downloading: it
        // would otherwise land a moment later and hijack the player they just
        // started.
        fallbackJob?.cancel()
        fallbackJob = null
        error = null
        playingFromCache = false
        attach { player -> player.setDataSource(context, Uri.parse(url), headers) }
    }

    private fun playCached(file: File) {
        attach { player -> player.setDataSource(file.absolutePath) }
    }

    private fun attach(configure: (MediaPlayer) -> Unit) {
        // Any player still in hand is released first: the fallback path can arrive
        // while a failed stream's player is still buffering, and replacing a
        // reference without releasing it leaks a codec.
        runCatching { player?.release() }
        isPreparing = true
        val fresh = MediaPlayer()
        player = fresh
        try {
            fresh.setAudioAttributes(
                AudioAttributes.Builder()
                    // The agent's audio is speech and the app is not a music
                    // player: asking for the speech content type gets it the
                    // right ducking behaviour when a call or notification lands.
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            // Each callback checks that it belongs to the player still in hand:
            // releasing a player can leave one callback in flight, and a stale
            // error would otherwise tear down the player that replaced it.
            fresh.setOnPreparedListener { prepared ->
                if (player === prepared) {
                    isPreparing = false
                    durationMs = prepared.duration.toLong().coerceAtLeast(0L)
                    prepared.start()
                    isPlaying = true
                }
            }
            fresh.setOnCompletionListener { finished ->
                if (player === finished) {
                    isPlaying = false
                    positionMs = durationMs
                }
            }
            fresh.setOnErrorListener { failed, _, _ ->
                if (player === failed) onFailed()
                true
            }
            configure(fresh)
            fresh.prepareAsync()
        } catch (failure: Exception) {
            // setDataSource throws for a malformed URL, a refused connection or a
            // missing codec; the fallback below is the same path a stream error
            // takes, because the cause is indistinguishable from here.
            onFailed()
        }
    }

    /**
     * The stream failed. Try the app's own authenticated fetch and play the
     * cached copy instead.
     *
     * This is the one part of media playback that cannot be checked without a
     * device: MediaPlayer's HTTP stack is the platform's, not OkHttp's, so
     * whether the explicit cookie header survives onto the range requests that
     * follow is not something the JVM tests can prove. Rather than guess, a
     * refused stream falls back to a download through the shared client — which
     * definitely carries the sign-in — and plays that. Authenticated playback
     * therefore holds either way, at the cost of waiting for the file.
     */
    private fun onFailed() {
        discard()
        if (playingFromCache) {
            fail("This recording could not be played.")
            return
        }
        playingFromCache = true
        isPreparing = true
        fallbackJob = scope.launch {
            fallback()
                .onSuccess { file ->
                    isPreparing = false
                    playCached(file)
                }
                .onFailure { failure ->
                    fail(failure.message ?: "Could not download this recording.")
                }
        }
    }

    // ── transport ───────────────────────────────────────────────────────────

    private fun pause() {
        runCatching { player?.pause() }
        isPlaying = false
    }

    private fun resume() {
        runCatching { player?.start() }
        isPlaying = true
    }

    private fun fail(message: String) {
        discard()
        error = message
    }

    private fun discard() {
        val current = player
        player = null
        isPlaying = false
        isPreparing = false
        fallbackJob?.cancel()
        fallbackJob = null
        runCatching { current?.release() }
    }
}

/**
 * An audio attachment: play/pause, a scrubber, both clocks and a save action.
 *
 * The player is created on first play and released in [DisposableEffect], so a
 * row that scrolls out of the transcript stops and gives its codec back rather
 * than playing on from somewhere off screen.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AudioAttachment(
    ref: MediaRef,
    url: String,
    caption: String?,
    modifier: Modifier = Modifier,
) {
    val env = LocalMediaEnvironment.current ?: return
    val context = LocalContext.current
    val cue = rememberCue()
    val scope = rememberCoroutineScope()

    val playback = remember(url, env) {
        AudioPlayback(
            context = context.applicationContext,
            url = url,
            headers = MediaAuth.streamHeaders(env.client, url),
            scope = scope,
            fallback = { downloadToCache(env, url, ref.name) },
        )
    }
    DisposableEffect(playback) {
        onDispose { playback.release() }
    }

    // The position has to be pulled: MediaPlayer has no progress callback, and a
    // 250 ms tick is invisible next to the cost of a recomposition per frame.
    LaunchedEffect(playback, playback.isPlaying) {
        while (playback.isPlaying) {
            playback.tick()
            delay(250)
        }
    }

    // While a finger is on the bar the labels follow the finger, not the player,
    // or the two fight on every tick. -1 means "not scrubbing".
    var scrubbedMs by remember(url) { mutableLongStateOf(-1L) }
    val duration = playback.durationMs
    val download = rememberAttachmentDownload(env, url, ref)

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 4.dp)) {
            AudioHeaderRow(
                playing = playback.isPlaying,
                preparing = playback.isPreparing,
                title = caption ?: ref.name,
                // A format label is only news when the caption took the title
                // line; otherwise it repeats the tail of the file name.
                format = ref.extension.uppercase().takeIf { it.isNotBlank() && caption != null },
                problem = playback.error,
                download = download,
                onToggle = {
                    cue(HapticCue.UI_ACTION)
                    playback.toggle()
                },
            )

            AudioTimeline(
                shownMs = if (scrubbedMs >= 0L) scrubbedMs else playback.positionMs,
                durationMs = duration,
                note = download.error ?: download.sizeBytes.takeIf { it > 0L }?.let(::formatMediaSize),
                noteIsError = download.error != null,
                onScrub = { fraction -> scrubbedMs = (fraction * duration).toLong() },
                onScrubCommitted = {
                    if (scrubbedMs >= 0L) {
                        playback.seekTo(scrubbedMs)
                        cue(HapticCue.TOGGLE)
                        scrubbedMs = -1L
                    }
                },
            )
        }
    }
}

/** Play/pause, what is playing, and the save action. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AudioHeaderRow(
    playing: Boolean,
    preparing: Boolean,
    title: String,
    format: String?,
    problem: String?,
    download: AttachmentDownload,
    onToggle: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledIconButton(
            onClick = onToggle,
            // The morphing overload, so the disc's shape is *state*: pill at rest,
            // square under the finger.
            shapes = IconButtonDefaults.shapes(),
            // The row's primary action: a 44dp disc, with M3's 48dp target
            // reserved around it rather than clipped to the visual.
            modifier = Modifier.minimumInteractiveComponentSize().size(44.dp),
        ) {
            if (preparing) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(
                    imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                // Captions are the agent's own words and can be a sentence; the
                // row stays two lines so a long one cannot push the player's
                // controls off the transcript.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = problem ?: format
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (problem != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        AttachmentDownloadButton(
            download = download,
            contentDescription = "Save and open this recording",
        )
    }
}

/** The scrubber and the two clocks. */
@Composable
private fun AudioTimeline(
    shownMs: Long,
    durationMs: Long,
    note: String?,
    noteIsError: Boolean,
    onScrub: (Float) -> Unit,
    onScrubCommitted: () -> Unit,
) {
    Slider(
        value = if (durationMs > 0L) (shownMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
        onValueChange = onScrub,
        // A drag is only committed on release: seeking on every frame of the
        // gesture would re-issue a range request per pixel.
        onValueChangeFinished = onScrubCommitted,
        enabled = durationMs > 0L,
        modifier = Modifier.fillMaxWidth(),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = MediaTime.format(shownMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = if (noteIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Text(
            // An unknown duration is not "0:00": it is the honest state of a
            // stream whose headers have not arrived yet.
            text = if (durationMs > 0L) MediaTime.format(durationMs) else "--:--",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
