package app.podhub.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.podhub.PodHubApp
import app.podhub.data.*
import app.podhub.download.DownloadWorker
import app.podhub.player.PlayResult
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = PodHubApp.repo(app)
    val player = PodHubApp.player(app)

    val podcasts = repo.db.podcasts().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val latest = repo.db.episodes().observeLatest().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val downloads = repo.db.episodes().observeDownloads().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    init { player.attach() }

    fun toast(msg: String) { _messages.tryEmit(msg) }

    fun podcast(id: Long) = repo.db.podcasts().observe(id)
    fun episodesOf(id: Long) = repo.db.episodes().observeForPodcast(id)
    fun episode(id: Long) = repo.db.episodes().observeWithPodcast(id)

    fun addSource(type: SourceType, url: String, user: String?, pass: String?, onAdded: (Long) -> Unit) =
        work("Source ajoutée") {
            val id = repo.addSource(type, url, user, pass)
            onAdded(id)
        }

    fun refreshAll() = work(null) {
        val n = repo.refreshAll().size
        _messages.emit(if (n == 0) "Aucun nouvel épisode" else "$n nouvel(s) épisode(s)")
    }

    fun refresh(p: Podcast) = work(null) {
        if (!Repository.isVirtual(p)) repo.refresh(p)
    }

    fun play(item: EpisodeWithPodcast) = viewModelScope.launch {
        when (player.play(item)) {
            PlayResult.Started -> Unit
            PlayResult.NeedsDownload -> {
                if (item.episode.downloadState !in setOf(DownloadState.QUEUED, DownloadState.DOWNLOADING)) {
                    DownloadWorker.enqueue(getApplication(), item.episode.id)
                }
                _messages.emit("Conversion en MP3 en cours… l'épisode sera lisible à la fin du téléchargement")
            }
        }
    }

    fun download(e: Episode) = viewModelScope.launch(Dispatchers.IO) { DownloadWorker.enqueue(getApplication(), e.id) }
    fun deleteDownload(e: Episode) = viewModelScope.launch { repo.deleteDownload(e) }
    fun setPlayed(e: Episode, played: Boolean) = viewModelScope.launch(Dispatchers.IO) { repo.db.episodes().setPlayed(e.id, played) }
    fun deletePodcast(p: Podcast) = viewModelScope.launch { repo.deletePodcast(p) }
    fun setAutoDownload(p: Podcast, on: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        repo.db.podcasts().update(p.copy(autoDownload = on))
    }

    fun captureMedia(mediaUrl: String, title: String, pageUrl: String, image: String?) =
        work("Ajouté à la bibliothèque Od3on, téléchargement lancé") {
            repo.addCapturedMedia(mediaUrl, title, pageUrl, image)
        }

    fun updateYtDlp() = work(null) {
        val msg = withContext(Dispatchers.IO) {
            val status = YoutubeDL.getInstance().updateYoutubeDL(getApplication(), YoutubeDL.UpdateChannel.STABLE)
            if (status == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) "yt-dlp est déjà à jour"
            else "yt-dlp mis à jour (${YoutubeDL.getInstance().version(getApplication())})"
        }
        _messages.emit(msg)
    }

    private fun work(success: String?, block: suspend () -> Unit) = viewModelScope.launch {
        _busy.value = true
        try {
            block()
            success?.let { _messages.emit(it) }
        } catch (e: Exception) {
            _messages.emit(e.message ?: "Erreur inconnue")
        } finally {
            _busy.value = false
        }
    }
}
