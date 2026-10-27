package app.podhub.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.work.*
import app.podhub.PodHubApp
import app.podhub.data.DownloadState
import app.podhub.data.Repository
import app.podhub.data.SourceType
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val KEY_ID = "episodeId"
        private const val CHANNEL = "downloads"

        suspend fun enqueue(context: Context, episodeId: Long) {
            PodHubApp.repo(context).db.episodes().setDownload(episodeId, DownloadState.QUEUED, 0)
            val req = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(KEY_ID to episodeId))
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
                .addTag("dl")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("dl-$episodeId", ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context, episodeId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork("dl-$episodeId")
            runCatching { YoutubeDL.getInstance().destroyProcessById("dl-$episodeId") }
        }
    }

    private val repo get() = PodHubApp.repo(applicationContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getLong(KEY_ID, -1)
        val item = repo.db.episodes().getWithPodcast(id) ?: return@withContext Result.failure()
        val ep = item.episode
        runCatching { setForeground(foregroundInfo(ep.title, 0)) }
        repo.db.episodes().setDownload(id, DownloadState.DOWNLOADING, 0)
        try {
            val p = item.podcast
            val headers = buildMap {
                Repository.authHeader(p)?.let { put("Authorization", it) }
                if (p.type == SourceType.ODEON) {
                    CookieManager.getInstance().getCookie(ep.mediaUrl)?.let { put("Cookie", it) }
                    ep.description?.takeIf { it.startsWith("http") }?.let { put("Referer", it) }
                }
            }
            // YouTube et les flux segmentés (HLS) passent par yt-dlp + ffmpeg, le reste en HTTP direct.
            val file = if (p.type == SourceType.YOUTUBE || ".m3u8" in ep.mediaUrl) {
                downloadWithYtDlp(id, ep.mediaUrl, ep.title, headers)
            } else {
                downloadHttp(id, ep.mediaUrl, ep.title, headers)
            }
            repo.db.episodes().setDownloaded(id, file.absolutePath)
            Result.success()
        } catch (e: Exception) {
            if (isStopped) {
                repo.db.episodes().setDownload(id, DownloadState.NONE, 0)
                return@withContext Result.failure()
            }
            repo.db.episodes().setDownload(id, DownloadState.FAILED, 0, e.message?.take(300))
            if (runAttemptCount < 2 && e is IOException) Result.retry() else Result.failure()
        }
    }

    private suspend fun downloadHttp(id: Long, url: String, title: String, headers: Map<String, String>): File {
        val req = Request.Builder().url(url).header("User-Agent", Repository.USER_AGENT)
            .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        repo.http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Erreur HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Réponse vide")
            val ext = extensionFor(body.contentType()?.toString(), url)
            val target = File(repo.downloadDir(), "ep$id.$ext")
            val tmp = File(target.path + ".part")
            val total = body.contentLength()
            var done = 0L
            var lastPct = -1
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (isStopped) throw IOException("Annulé")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct && pct % 2 == 0) {
                                lastPct = pct
                                repo.db.episodes().setDownload(id, DownloadState.DOWNLOADING, pct)
                                runCatching { setForeground(foregroundInfo(title, pct)) }
                            }
                        }
                    }
                }
            }
            if (!tmp.renameTo(target)) throw IOException("Écriture impossible")
            return target
        }
    }

    /** Extraction audio + conversion MP3 avec yt-dlp et ffmpeg embarqués. */
    private suspend fun downloadWithYtDlp(id: Long, url: String, title: String, headers: Map<String, String>): File {
        (applicationContext as PodHubApp).ytdlReady.await()
        val isYoutube = "youtu" in url
        return try {
            runYtDlp(id, url, title, headers, extractorArgs = null)
        } catch (e: Exception) {
            if (!isYoutube || isStopped) throw cleanYtDlpError(e)
            // 2e essai : clients YouTube qui ne demandent ni runtime JS ni jeton PO.
            try {
                runYtDlp(id, url, title, headers, extractorArgs = "youtube:player_client=android_vr,tv_simply,web_safari")
            } catch (e2: Exception) {
                throw cleanYtDlpError(e2)
            }
        }
    }

    /** yt-dlp renvoie tout stderr (avertissements compris) : on ne garde que les lignes ERROR. */
    private fun cleanYtDlpError(e: Exception): Exception {
        val lines = e.message.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
        val msg = lines.filter { it.startsWith("ERROR") }.joinToString("\n").ifEmpty {
            lines.lastOrNull { !it.startsWith("WARNING") } ?: lines.lastOrNull() ?: "Erreur yt-dlp"
        }
        return Exception(msg.removePrefix("ERROR: ").take(300))
    }

    private fun runYtDlp(id: Long, url: String, title: String, headers: Map<String, String>, extractorArgs: String?): File {
        val dir = repo.downloadDir()
        val request = YoutubeDLRequest(url).apply {
            headers.forEach { (k, v) -> addOption("--add-header", "$k:$v") }
            extractorArgs?.let { addOption("--extractor-args", it) }
            // Compte YouTube connecté => accès aux vidéos membres / achetées.
            if ("youtu" in url) YoutubeAuth.cookieFile(applicationContext)?.let { addOption("--cookies", it.absolutePath) }
            addOption("--no-warnings")
            addOption("-x")
            addOption("--audio-format", "mp3")
            addOption("--audio-quality", "5") // VBR ~130 kb/s, bon compromis pour la voix
            addOption("--no-playlist")
            addOption("--embed-metadata")
            addOption("-o", File(dir, "yt$id.%(ext)s").absolutePath)
        }
        var lastPct = -1
        YoutubeDL.getInstance().execute(request, "dl-$id") { progress, _, _ ->
            val pct = progress.toInt().coerceIn(0, 100)
            if (pct != lastPct && pct % 2 == 0) {
                lastPct = pct
                runBlocking {
                    repo.db.episodes().setDownload(id, DownloadState.DOWNLOADING, pct)
                    runCatching { setForeground(foregroundInfo(title, pct)) }
                }
            }
        }
        val mp3 = File(dir, "yt$id.mp3")
        if (!mp3.exists()) throw IOException("Conversion MP3 échouée")
        return mp3
    }

    private fun extensionFor(contentType: String?, url: String): String {
        val fromUrl = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        if (fromUrl in setOf("mp3", "m4a", "aac", "ogg", "opus", "mp4", "wav", "flac", "webm")) return fromUrl
        return when {
            contentType == null -> "mp3"
            "mpeg" in contentType -> "mp3"
            "mp4" in contentType || "m4a" in contentType -> "m4a"
            "ogg" in contentType -> "ogg"
            "wav" in contentType -> "wav"
            else -> "mp3"
        }
    }

    private fun foregroundInfo(title: String, pct: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Téléchargements", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Téléchargement")
            .setContentText(title)
            .setProgress(100, pct, pct == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        val notifId = id.hashCode()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(notifId, n)
    }
}

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        PodHubApp.repo(applicationContext).refreshAll()
        return Result.success()
    }
}
