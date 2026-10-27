package app.podhub.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class SourceType(val label: String) {
    RSS("Flux RSS"),
    PATREON("Patreon"),
    ODEON("Od3on"),
    YOUTUBE("YouTube");
}

enum class DownloadState { NONE, QUEUED, DOWNLOADING, DONE, FAILED }

@Entity(tableName = "podcasts", indices = [Index(value = ["feedUrl"], unique = true)])
data class Podcast(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: SourceType,
    val title: String,
    val feedUrl: String,
    val imageUrl: String? = null,
    val description: String? = null,
    val author: String? = null,
    // Identifiants HTTP Basic optionnels (flux privés). Stockés en clair, usage perso.
    val username: String? = null,
    val password: String? = null,
    val autoDownload: Boolean = false,
    val lastRefreshed: Long = 0,
)

@Entity(
    tableName = "episodes",
    indices = [Index(value = ["podcastId", "guid"], unique = true), Index("pubDate")],
    foreignKeys = [ForeignKey(
        entity = Podcast::class,
        parentColumns = ["id"],
        childColumns = ["podcastId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class Episode(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val podcastId: Long,
    val guid: String,
    val title: String,
    val description: String? = null,
    val pubDate: Long = 0,
    /** URL du fichier audio (RSS) ou de la vidéo (YouTube). */
    val mediaUrl: String,
    val durationMs: Long = 0,
    val imageUrl: String? = null,
    val localPath: String? = null,
    val downloadState: DownloadState = DownloadState.NONE,
    val downloadProgress: Int = 0,
    val downloadError: String? = null,
    val positionMs: Long = 0,
    val played: Boolean = false,
)

data class EpisodeWithPodcast(
    @Embedded val episode: Episode,
    @Relation(parentColumn = "podcastId", entityColumn = "id") val podcast: Podcast,
)

class Converters {
    @TypeConverter fun fromSource(t: SourceType) = t.name
    @TypeConverter fun toSource(s: String) = SourceType.valueOf(s)
    @TypeConverter fun fromDl(t: DownloadState) = t.name
    @TypeConverter fun toDl(s: String) = DownloadState.valueOf(s)
}

@Dao
interface PodcastDao {
    @Query("SELECT * FROM podcasts ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<Podcast>>

    @Query("SELECT * FROM podcasts")
    suspend fun all(): List<Podcast>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    fun observe(id: Long): Flow<Podcast?>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    suspend fun get(id: Long): Podcast?

    @Query("SELECT * FROM podcasts WHERE feedUrl = :url")
    suspend fun byUrl(url: String): Podcast?

    @Insert suspend fun insert(p: Podcast): Long
    @Update suspend fun update(p: Podcast)
    @Delete suspend fun delete(p: Podcast)
}

@Dao
interface EpisodeDao {
    @Transaction
    @Query("SELECT * FROM episodes ORDER BY pubDate DESC LIMIT 300")
    fun observeLatest(): Flow<List<EpisodeWithPodcast>>

    @Transaction
    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY pubDate DESC")
    fun observeForPodcast(podcastId: Long): Flow<List<EpisodeWithPodcast>>

    @Transaction
    @Query("SELECT * FROM episodes WHERE downloadState != 'NONE' ORDER BY pubDate DESC")
    fun observeDownloads(): Flow<List<EpisodeWithPodcast>>

    @Transaction
    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun getWithPodcast(id: Long): EpisodeWithPodcast?

    @Transaction
    @Query("SELECT * FROM episodes WHERE id = :id")
    fun observeWithPodcast(id: Long): Flow<EpisodeWithPodcast?>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun get(id: Long): Episode?

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId")
    suspend fun episodesOf(podcastId: Long): List<Episode>

    @Query("SELECT guid FROM episodes WHERE podcastId = :podcastId")
    suspend fun guids(podcastId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(list: List<Episode>): List<Long>

    @Query("UPDATE episodes SET downloadState = :state, downloadProgress = :progress, downloadError = :error WHERE id = :id")
    suspend fun setDownload(id: Long, state: DownloadState, progress: Int, error: String? = null)

    @Query("UPDATE episodes SET downloadState = 'DONE', downloadProgress = 100, localPath = :path, downloadError = NULL WHERE id = :id")
    suspend fun setDownloaded(id: Long, path: String)

    @Query("UPDATE episodes SET downloadState = 'NONE', downloadProgress = 0, localPath = NULL WHERE id = :id")
    suspend fun clearDownload(id: Long)

    @Query("UPDATE episodes SET positionMs = :pos, durationMs = CASE WHEN :dur > 0 THEN :dur ELSE durationMs END WHERE id = :id")
    suspend fun savePosition(id: Long, pos: Long, dur: Long)

    @Query("UPDATE episodes SET played = :played, positionMs = 0 WHERE id = :id")
    suspend fun setPlayed(id: Long, played: Boolean)
}

@Database(entities = [Podcast::class, Episode::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun podcasts(): PodcastDao
    abstract fun episodes(): EpisodeDao

    companion object {
        fun create(context: Context) =
            Room.databaseBuilder(context, AppDatabase::class.java, "podhub.db").build()
    }
}
