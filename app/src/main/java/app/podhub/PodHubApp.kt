package app.podhub

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.podhub.data.AppDatabase
import app.podhub.data.Repository
import app.podhub.download.RefreshWorker
import app.podhub.player.PlayerController
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class PodHubApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var repo: Repository
    lateinit var player: PlayerController

    override fun onCreate() {
        super.onCreate()
        repo = Repository(this, AppDatabase.create(this))
        player = PlayerController(this, repo, appScope)

        appScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(this@PodHubApp)
                FFmpeg.getInstance().init(this@PodHubApp)
                updateYtDlpIfStale()
            } catch (e: Exception) {
                Log.e("PodHub", "Initialisation yt-dlp impossible", e)
            } finally {
                ytdlReady.complete(Unit)
            }
        }

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "refresh",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RefreshWorker>(4, TimeUnit.HOURS).build(),
        )
    }

    /** Terminé quand yt-dlp est initialisé (et mis à jour si besoin) : les téléchargements l'attendent. */
    val ytdlReady = CompletableDeferred<Unit>()

    /** YouTube casse régulièrement les vieilles versions : mise à jour au plus une fois par jour. */
    private fun updateYtDlpIfStale() {
        val prefs = getSharedPreferences("ytdl", MODE_PRIVATE)
        val last = prefs.getLong("lastUpdate", 0)
        if (System.currentTimeMillis() - last < TimeUnit.DAYS.toMillis(1)) return
        try {
            YoutubeDL.getInstance().updateYoutubeDL(this, YoutubeDL.UpdateChannel.STABLE)
            prefs.edit().putLong("lastUpdate", System.currentTimeMillis()).apply()
        } catch (e: Exception) {
            Log.w("PodHub", "Mise à jour yt-dlp impossible", e)
        }
    }

    companion object {
        fun repo(context: Context) = (context.applicationContext as PodHubApp).repo
        fun player(context: Context) = (context.applicationContext as PodHubApp).player
    }
}
