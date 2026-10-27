package app.podhub.data

import android.content.Context
import app.podhub.PodHubApp
import app.podhub.download.DownloadWorker
import app.podhub.download.YoutubeAuth
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import app.podhub.feed.FeedParser
import app.podhub.feed.ParsedEpisode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class Repository(private val context: Context, val db: AppDatabase) {

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    companion object {
        const val YT_SINGLES_URL = "podhub://youtube/videos"
        const val ODEON_LIBRARY_URL = "podhub://od3on/bibliotheque"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"

        private val YT_VIDEO = Regex("""(?:youtube\.com/(?:watch\?v=|shorts/|live/)|youtu\.be/)([\w-]{11})""")
        private val YT_PLAYLIST = Regex("""[?&]list=([\w-]+)""")
        private val YT_CHANNEL_ID = Regex("""youtube\.com/channel/(UC[\w-]{22})""")

        fun authHeader(p: Podcast): String? =
            if (!p.username.isNullOrBlank()) Credentials.basic(p.username, p.password.orEmpty()) else null

        fun isVirtual(p: Podcast) = p.feedUrl.startsWith("podhub://")
    }

    // ---------- Ajout de sources ----------

    /** Ajoute une source. Renvoie l'id du podcast (ou de la bibliothèque concernée). */
    suspend fun addSource(type: SourceType, rawUrl: String, user: String?, pass: String?): Long =
        withContext(Dispatchers.IO) {
            val url = rawUrl.trim()
            if (type == SourceType.YOUTUBE || YT_VIDEO.containsMatchIn(url) || "youtube.com/" in url) {
                return@withContext addYoutube(url)
            }
            db.podcasts().byUrl(url)?.let { return@withContext it.id }
            val probe = Podcast(type = type, title = "", feedUrl = url,
                username = user?.takeIf { it.isNotBlank() }, password = pass?.takeIf { it.isNotBlank() })
            val feed = fetchFeed(probe)
            val id = db.podcasts().insert(
                probe.copy(
                    title = feed.title, description = feed.description, author = feed.author,
                    imageUrl = feed.imageUrl, lastRefreshed = System.currentTimeMillis(),
                )
            )
            insertEpisodes(id, feed.episodes)
            id
        }

    private suspend fun addYoutube(url: String): Long {
        // Une vidéo isolée (sans playlist) => bibliothèque « Vidéos YouTube » + téléchargement MP3.
        val videoId = YT_VIDEO.find(url)?.groupValues?.get(1)
        val playlistId = YT_PLAYLIST.find(url)?.groupValues?.get(1)
        if (videoId != null && playlistId == null) {
            val lib = virtualPodcast(YT_SINGLES_URL, SourceType.YOUTUBE, "Vidéos YouTube")
            val title = fetchYoutubeTitle(videoId) ?: "Vidéo $videoId"
            val ep = ParsedEpisode(
                guid = videoId, title = title, description = url, pubDate = System.currentTimeMillis(),
                mediaUrl = "https://www.youtube.com/watch?v=$videoId", durationMs = 0,
                imageUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            )
            val ids = insertEpisodes(lib.id, listOf(ep))
            val epId = ids.firstOrNull { it > 0 }
                ?: db.episodes().episodesOf(lib.id).first { it.guid == videoId }.id
            DownloadWorker.enqueue(context, epId)
            return lib.id
        }
        // Chaîne ou playlist => abonnement via le flux Atom officiel de YouTube.
        val feedUrl = when {
            playlistId != null -> "https://www.youtube.com/feeds/videos.xml?playlist_id=$playlistId"
            else -> "https://www.youtube.com/feeds/videos.xml?channel_id=${resolveChannelId(url)}"
        }
        db.podcasts().byUrl(feedUrl)?.let { return it.id }
        val probe = Podcast(type = SourceType.YOUTUBE, title = "", feedUrl = feedUrl)
        val feed = fetchFeed(probe)
        val image = runCatching { fetchYoutubeAvatar(url) }.getOrNull()
        val id = db.podcasts().insert(
            probe.copy(title = feed.title, author = feed.author, imageUrl = image ?: feed.episodes.firstOrNull()?.imageUrl,
                lastRefreshed = System.currentTimeMillis())
        )
        insertEpisodes(id, feed.episodes + fetchMembersOnly(feedUrl))
        return id
    }

    /**
     * Vidéos réservées aux membres : absentes du flux Atom public. YouTube les regroupe dans la
     * playlist « UUMO » + id de chaîne, lisible avec yt-dlp quand le compte est connecté.
     */
    private suspend fun fetchMembersOnly(feedUrl: String): List<ParsedEpisode> {
        val channelId = Regex("""channel_id=(UC[\w-]{22})""").find(feedUrl)?.groupValues?.get(1) ?: return emptyList()
        val cookies = YoutubeAuth.cookieFile(context) ?: return emptyList()
        (context.applicationContext as PodHubApp).ytdlReady.await()
        return runCatching {
            val req = YoutubeDLRequest("https://www.youtube.com/playlist?list=UUMO${channelId.substring(2)}").apply {
                addOption("--flat-playlist")
                addOption("-J")
                addOption("--playlist-end", "30")
                addOption("--no-warnings")
                addOption("--cookies", cookies.absolutePath)
            }
            val entries = JSONObject(YoutubeDL.getInstance().execute(req).out).optJSONArray("entries") ?: JSONArray()
            val now = System.currentTimeMillis()
            (0 until entries.length()).mapNotNull { i ->
                val e = entries.getJSONObject(i)
                val vid = e.optString("id").takeIf { it.length == 11 } ?: return@mapNotNull null
                val ts = e.optLong("timestamp", 0).takeIf { it > 0 }?.times(1000)
                ParsedEpisode(
                    guid = vid,
                    title = "🔒 " + e.optString("title", "Vidéo membres"),
                    description = e.optString("description").takeIf { it.isNotBlank() },
                    pubDate = ts ?: (now - i * 60_000L), // pas de date en mode rapide : on garde l'ordre
                    mediaUrl = "https://www.youtube.com/watch?v=$vid",
                    durationMs = (e.optDouble("duration", 0.0) * 1000).toLong(),
                    imageUrl = "https://i.ytimg.com/vi/$vid/hqdefault.jpg",
                )
            }
        }.getOrElse { emptyList() } // pas membre de cette chaîne : rien à ajouter
    }

    private fun resolveChannelId(url: String): String {
        YT_CHANNEL_ID.find(url)?.let { return it.groupValues[1] }
        // @handle, /c/nom, /user/nom : on lit la page de la chaîne pour trouver l'identifiant UC...
        val pageUrl = if (url.startsWith("http")) url else "https://www.youtube.com/${url.removePrefix("/")}"
        val html = getText(pageUrl, ytCookies = true)
        val m = Regex("""youtube\.com/channel/(UC[\w-]{22})""").find(html)
            ?: Regex(""""(?:channelId|externalId)":"(UC[\w-]{22})"""").find(html)
            ?: throw IOException("Chaîne YouTube introuvable : $url")
        return m.groupValues[1]
    }

    private fun fetchYoutubeTitle(videoId: String): String? = runCatching {
        val json = getText("https://www.youtube.com/oembed?format=json&url=https://www.youtube.com/watch?v=$videoId")
        org.json.JSONObject(json).getString("title")
    }.getOrNull()

    private fun fetchYoutubeAvatar(url: String): String? {
        if (!url.startsWith("http")) return null
        val html = getText(url, ytCookies = true)
        return Regex("""<meta property="og:image" content="([^"]+)"""").find(html)?.groupValues?.get(1)
    }

    /** Ajoute un média capturé sur od3on (ou toute page web) dans la bibliothèque Od3on. */
    suspend fun addCapturedMedia(mediaUrl: String, title: String, pageUrl: String, imageUrl: String?): Long =
        withContext(Dispatchers.IO) {
            val lib = virtualPodcast(ODEON_LIBRARY_URL, SourceType.ODEON, "Od3on")
            val ep = ParsedEpisode(
                guid = mediaUrl.substringBefore('?'), title = title.ifBlank { "Audio od3on" }, description = pageUrl,
                pubDate = System.currentTimeMillis(), mediaUrl = mediaUrl, durationMs = 0, imageUrl = imageUrl,
            )
            insertEpisodes(lib.id, listOf(ep))
            val epId = db.episodes().episodesOf(lib.id).first { it.guid == ep.guid }.id
            // Téléchargé tout de suite : les liens od3on sont souvent signés et expirent.
            DownloadWorker.enqueue(context, epId)
            epId
        }

    private suspend fun virtualPodcast(url: String, type: SourceType, title: String): Podcast {
        db.podcasts().byUrl(url)?.let { return it }
        val id = db.podcasts().insert(Podcast(type = type, title = title, feedUrl = url))
        return db.podcasts().get(id)!!
    }

    // ---------- Rafraîchissement ----------

    /** Rafraîchit toutes les sources ; renvoie les ids des nouveaux épisodes à télécharger automatiquement. */
    suspend fun refreshAll(): List<Long> = withContext(Dispatchers.IO) {
        db.podcasts().all().filterNot(::isVirtual).flatMap { p ->
            runCatching { refresh(p) }.getOrDefault(emptyList())
        }
    }

    suspend fun refresh(p: Podcast): List<Long> = withContext(Dispatchers.IO) {
        val feed = fetchFeed(p)
        db.podcasts().update(p.copy(
            title = feed.title.ifBlank { p.title },
            imageUrl = p.imageUrl ?: feed.imageUrl,
            description = feed.description ?: p.description,
            lastRefreshed = System.currentTimeMillis(),
        ))
        val members = if (p.type == SourceType.YOUTUBE) fetchMembersOnly(p.feedUrl) else emptyList()
        val newIds = insertEpisodes(p.id, feed.episodes + members).filter { it > 0 }
        if (p.autoDownload) newIds.forEach { DownloadWorker.enqueue(context, it) }
        newIds
    }

    private suspend fun insertEpisodes(podcastId: Long, list: List<ParsedEpisode>): List<Long> {
        val known = db.episodes().guids(podcastId).toHashSet()
        val fresh = list.filter { it.guid !in known }.map {
            Episode(
                podcastId = podcastId, guid = it.guid, title = it.title, description = it.description,
                pubDate = it.pubDate, mediaUrl = it.mediaUrl, durationMs = it.durationMs, imageUrl = it.imageUrl,
            )
        }
        return if (fresh.isEmpty()) emptyList() else db.episodes().insertAll(fresh)
    }

    private fun fetchFeed(p: Podcast) = http.newCall(
        Request.Builder().url(p.feedUrl).header("User-Agent", USER_AGENT).apply {
            authHeader(p)?.let { header("Authorization", it) }
        }.build()
    ).execute().use { resp ->
        if (resp.code == 401 || resp.code == 403) throw IOException("Accès refusé (${resp.code}) : vérifie l'URL ou les identifiants")
        if (!resp.isSuccessful) throw IOException("Erreur HTTP ${resp.code}")
        val body = resp.body ?: throw IOException("Réponse vide")
        val type = body.contentType()?.toString().orEmpty()
        if ("html" in type) throw IOException("Cette URL est une page web, pas un flux RSS")
        FeedParser.parse(body.byteStream())
    }

    private fun getText(url: String, ytCookies: Boolean = false): String = http.newCall(
        Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .apply { if (ytCookies) header("Cookie", "CONSENT=YES+1; SOCS=CAI") }
            .header("Accept-Language", "fr,en;q=0.8").build()
    ).execute().use { r ->
        if (!r.isSuccessful) throw IOException("Erreur HTTP ${r.code}")
        r.body!!.string()
    }

    // ---------- Gestion ----------

    suspend fun deletePodcast(p: Podcast) = withContext(Dispatchers.IO) {
        db.episodes().episodesOf(p.id).forEach { e -> e.localPath?.let { File(it).delete() } }
        db.podcasts().delete(p)
    }

    suspend fun deleteDownload(e: Episode) = withContext(Dispatchers.IO) {
        DownloadWorker.cancel(context, e.id)
        e.localPath?.let { File(it).delete() }
        db.episodes().clearDownload(e.id)
    }

    fun downloadDir(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "episodes").apply { mkdirs() }
}
