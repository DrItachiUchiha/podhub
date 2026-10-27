package app.podhub.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.podhub.data.EpisodeWithPodcast
import app.podhub.data.Repository
import app.podhub.data.SourceType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class PlayerUi(
    val episodeId: Long? = null,
    val title: String = "",
    val podcastTitle: String = "",
    val imageUrl: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val buffering: Boolean = false,
)

sealed interface PlayResult {
    data object Started : PlayResult
    data object NeedsDownload : PlayResult
}

class PlayerController(
    private val context: Context,
    private val repo: Repository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(PlayerUi())
    val state: StateFlow<PlayerUi> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var ticker: Job? = null

    private suspend fun controller(): MediaController = withContext(Dispatchers.Main) {
        controller?.let { return@withContext it }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        suspendCancellableCoroutine<MediaController> { cont ->
            future.addListener({ cont.resumeWith(runCatching { future.get() }) }, ContextCompat.getMainExecutor(context))
        }.also { c ->
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = publish(player)
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) onEnded()
                }
            })
            startTicker()
        }
    }

    suspend fun play(item: EpisodeWithPodcast): PlayResult = withContext(Dispatchers.Main) {
        val ep = item.episode
        val local = ep.localPath?.let(::File)?.takeIf { it.exists() }
        // YouTube ne se lit qu'une fois converti en MP3.
        if (local == null && item.podcast.type == SourceType.YOUTUBE) return@withContext PlayResult.NeedsDownload

        val uri = local?.let(Uri::fromFile) ?: Uri.parse(ep.mediaUrl)
        if (local == null) {
            val headers = buildMap {
                Repository.authHeader(item.podcast)?.let { put("Authorization", it) }
                if (item.podcast.type == SourceType.ODEON) {
                    CookieManager.getInstance().getCookie(ep.mediaUrl)?.let { put("Cookie", it) }
                }
            }
            if (headers.isNotEmpty()) StreamHeaders.byUrl[ep.mediaUrl] = headers
        }

        val c = controller()
        saveCurrent(c)
        val media = MediaItem.Builder()
            .setMediaId(ep.id.toString())
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(ep.title)
                    .setArtist(item.podcast.title)
                    .setArtworkUri((ep.imageUrl ?: item.podcast.imageUrl)?.let(Uri::parse))
                    .build()
            ).build()
        val resumeAt = if (ep.played) 0 else ep.positionMs
        c.setMediaItem(media, resumeAt)
        c.prepare()
        c.play()
        _state.value = _state.value.copy(
            episodeId = ep.id, title = ep.title, podcastTitle = item.podcast.title,
            imageUrl = ep.imageUrl ?: item.podcast.imageUrl, durationMs = ep.durationMs,
        )
        PlayResult.Started
    }

    fun togglePlay() = scope.launch(Dispatchers.Main) {
        val c = controller()
        if (c.isPlaying) { c.pause(); saveCurrent(c) } else c.play()
    }

    fun seekBy(deltaMs: Long) = scope.launch(Dispatchers.Main) {
        val c = controller()
        c.seekTo((c.currentPosition + deltaMs).coerceAtLeast(0))
    }

    fun seekTo(ms: Long) = scope.launch(Dispatchers.Main) { controller().seekTo(ms) }

    fun setSpeed(speed: Float) = scope.launch(Dispatchers.Main) {
        controller().playbackParameters = PlaybackParameters(speed)
    }

    /** Reconnecte l'UI à une lecture déjà en cours (appli rouverte). */
    fun attach() = scope.launch(Dispatchers.Main) {
        runCatching { val c = controller(); publish(c); restoreInfo(c) }
    }

    private suspend fun restoreInfo(c: Player) {
        val id = c.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val item = repo.db.episodes().getWithPodcast(id) ?: return
        _state.value = _state.value.copy(
            episodeId = id, title = item.episode.title, podcastTitle = item.podcast.title,
            imageUrl = item.episode.imageUrl ?: item.podcast.imageUrl,
        )
    }

    private fun publish(p: Player) {
        _state.value = _state.value.copy(
            isPlaying = p.isPlaying,
            positionMs = p.currentPosition,
            durationMs = p.duration.takeIf { it > 0 } ?: _state.value.durationMs,
            speed = p.playbackParameters.speed,
            buffering = p.playbackState == Player.STATE_BUFFERING,
        )
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch(Dispatchers.Main) {
            var n = 0
            while (isActive) {
                delay(1000)
                val c = controller ?: continue
                publish(c)
                if (c.isPlaying && ++n % 10 == 0) saveCurrent(c)
            }
        }
    }

    private fun saveCurrent(c: Player) {
        val id = c.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val pos = c.currentPosition
        val dur = c.duration.coerceAtLeast(0)
        scope.launch(Dispatchers.IO) { repo.db.episodes().savePosition(id, pos, dur) }
    }

    private fun onEnded() {
        val id = _state.value.episodeId ?: return
        scope.launch(Dispatchers.IO) { repo.db.episodes().setPlayed(id, true) }
    }
}
