package app.podhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.podhub.data.*
import app.podhub.player.PlayerUi
import coil.compose.AsyncImage
import java.text.DateFormat
import java.util.Date

fun SourceType.icon(): ImageVector = when (this) {
    SourceType.RSS -> Icons.Filled.RssFeed
    SourceType.PATREON -> Icons.Filled.Favorite
    SourceType.ODEON -> Icons.Filled.Language
    SourceType.YOUTUBE -> Icons.Filled.SmartDisplay
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return ""
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun formatDate(ms: Long): String =
    if (ms <= 0) "" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

@Composable
fun Artwork(url: String?, size: Dp, type: SourceType? = null) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (type != null) Icon(type.icon(), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun EpisodeRow(
    item: EpisodeWithPodcast,
    showPodcast: Boolean,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onTogglePlayed: () -> Unit,
) {
    val e = item.episode
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(e.imageUrl ?: item.podcast.imageUrl, 56.dp, item.podcast.type)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (showPodcast) {
                Text(item.podcast.title, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                e.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (e.played) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
            )
            val meta = listOfNotNull(
                formatDate(e.pubDate).takeIf { it.isNotEmpty() },
                formatDuration(e.durationMs).takeIf { it.isNotEmpty() },
                if (e.positionMs > 0 && !e.played) "reprise à ${formatDuration(e.positionMs)}" else null,
                if (e.played) "écouté" else null,
            ).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (e.downloadState) {
                DownloadState.QUEUED -> Text("En attente…", style = MaterialTheme.typography.bodySmall)
                DownloadState.DOWNLOADING -> LinearProgressIndicator(
                    progress = { e.downloadProgress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                DownloadState.FAILED -> Text("Échec : ${e.downloadError ?: "inconnu"}", maxLines = 2,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else -> Unit
            }
        }
        when (e.downloadState) {
            DownloadState.DONE -> Icon(Icons.Filled.DownloadDone, "Téléchargé", tint = MaterialTheme.colorScheme.primary)
            DownloadState.NONE, DownloadState.FAILED -> IconButton(onClick = onDownload) {
                Icon(Icons.Outlined.Download, if (item.podcast.type == SourceType.YOUTUBE) "Convertir en MP3" else "Télécharger")
            }
            else -> Unit
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (e.played) "Marquer non écouté" else "Marquer écouté") },
                    onClick = { menu = false; onTogglePlayed() })
                if (e.downloadState != DownloadState.NONE) DropdownMenuItem(
                    text = { Text(if (e.downloadState == DownloadState.DONE) "Supprimer le fichier" else "Annuler le téléchargement") },
                    onClick = { menu = false; onDeleteDownload() })
            }
        }
    }
}

@Composable
fun MiniPlayer(state: PlayerUi, onOpen: () -> Unit, onToggle: () -> Unit) {
    if (state.episodeId == null) return
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column {
            if (state.durationMs > 0) LinearProgressIndicator(
                progress = { (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(2.dp))
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(state.imageUrl, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text(state.podcastTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onToggle) {
                    Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Lecture/pause")
                }
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
