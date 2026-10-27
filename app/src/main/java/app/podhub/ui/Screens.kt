package app.podhub.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podhub.data.*

private enum class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Feed("Nouveautés", Icons.Filled.NewReleases),
    Library("Abonnements", Icons.Filled.LibraryMusic),
    Downloads("Téléchargés", Icons.Filled.DownloadForOffline),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: MainViewModel,
    snackbar: SnackbarHostState,
    onOpenPodcast: (Long) -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenOdeon: (String?) -> Unit,
    onOpenYoutubeAccount: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.Feed) }
    var showAdd by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val player by vm.player.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PodHub") },
                actions = {
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    IconButton(onClick = vm::refreshAll, enabled = !busy) { Icon(Icons.Filled.Refresh, "Actualiser") }
                    IconButton(onClick = { onOpenOdeon(null) }) { Icon(Icons.Filled.Language, "Od3on") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Compte YouTube (vidéos membres)") },
                                leadingIcon = { Icon(Icons.Filled.AccountCircle, null) },
                                onClick = { menu = false; onOpenYoutubeAccount() })
                            DropdownMenuItem(
                                text = { Text("Mettre à jour yt-dlp") },
                                leadingIcon = { Icon(Icons.Filled.SystemUpdate, null) },
                                onClick = { menu = false; vm.updateYtDlp() })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Filled.Add, "Ajouter une source") }
        },
        bottomBar = {
            Column {
                MiniPlayer(player, onOpen = onOpenPlayer, onToggle = { vm.player.togglePlay() })
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t, onClick = { tab = t },
                            icon = { Icon(t.icon, null) }, label = { Text(t.label) })
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.Feed -> EpisodeList(vm, vm.latest.collectAsStateWithLifecycle().value, true, player.episodeId) {
                    EmptyState(Icons.Filled.Podcasts, "Aucun épisode", "Ajoute un flux RSS, un podcast Patreon, une chaîne YouTube ou un audio od3on avec le bouton +.")
                }
                Tab.Library -> Library(vm.podcasts.collectAsStateWithLifecycle().value, onOpenPodcast)
                Tab.Downloads -> EpisodeList(vm, vm.downloads.collectAsStateWithLifecycle().value, true, player.episodeId) {
                    EmptyState(Icons.Filled.DownloadForOffline, "Rien de téléchargé", "Les MP3 YouTube, les audios od3on et les épisodes téléchargés apparaissent ici.")
                }
            }
        }
    }

    if (showAdd) AddSourceDialog(
        busy = busy,
        onDismiss = { showAdd = false },
        onOpenOdeon = { showAdd = false; onOpenOdeon(null) },
        onAdd = { type, url, user, pass ->
            vm.addSource(type, url, user, pass) { showAdd = false }
        },
    )
}

@Composable
fun EpisodeList(
    vm: MainViewModel,
    items: List<EpisodeWithPodcast>,
    showPodcast: Boolean,
    currentId: Long?,
    header: (@Composable () -> Unit)? = null,
    empty: @Composable () -> Unit,
) {
    if (items.isEmpty() && header == null) { empty(); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
        if (header != null) item { header() }
        items(items, key = { it.episode.id }) { item ->
            EpisodeRow(
                item = item, showPodcast = showPodcast, isCurrent = item.episode.id == currentId,
                onPlay = { vm.play(item) },
                onDownload = { vm.download(item.episode) },
                onDeleteDownload = { vm.deleteDownload(item.episode) },
                onTogglePlayed = { vm.setPlayed(item.episode, !item.episode.played) },
            )
            HorizontalDivider(Modifier.padding(start = 84.dp))
        }
    }
}

@Composable
private fun Library(podcasts: List<Podcast>, onOpen: (Long) -> Unit) {
    if (podcasts.isEmpty()) {
        EmptyState(Icons.Filled.LibraryMusic, "Aucun abonnement", "Appuie sur + pour ajouter ta première source.")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
        items(podcasts, key = { it.id }) { p ->
            ListItem(
                modifier = Modifier.clickable { onOpen(p.id) },
                leadingContent = { Artwork(p.imageUrl, 56.dp, p.type) },
                headlineContent = { Text(p.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(listOfNotNull(p.type.label, p.author).joinToString(" · "), maxLines = 1) },
                trailingContent = { Icon(p.type.icon(), null, tint = MaterialTheme.colorScheme.primary) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddSourceDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onOpenOdeon: () -> Unit,
    onAdd: (SourceType, String, String?, String?) -> Unit,
) {
    var type by remember { mutableStateOf(SourceType.RSS) }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var showAuth by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajouter une source") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourceType.entries.forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t },
                            label = { Text(t.label) }, leadingIcon = { Icon(t.icon(), null, Modifier.size(18.dp)) })
                    }
                }
                Spacer(Modifier.height(12.dp))
                val help = when (type) {
                    SourceType.RSS -> "Colle l'adresse du flux RSS du podcast."
                    SourceType.PATREON -> "Sur patreon.com : page du créateur → onglet « Audio » ou menu ⋯ → « Obtenir le lien RSS ». Copie ce lien privé ici. Ne le partage pas : il te donne accès à ton abonnement."
                    SourceType.YOUTUBE -> "Colle le lien d'une chaîne (youtube.com/@nom), d'une playlist ou d'une vidéo. Les vidéos sont converties en MP3 sur le téléphone. Astuce : « Partager » depuis l'appli YouTube vers PodHub marche aussi."
                    SourceType.ODEON -> "Od3on n'a pas de flux RSS. Ouvre od3on dans PodHub, connecte-toi, puis ouvre un post : le bouton en bas récupère l'audio de la page."
                }
                Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                if (type == SourceType.ODEON) {
                    Button(onClick = onOpenOdeon, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Language, null); Spacer(Modifier.width(8.dp)); Text("Ouvrir od3on")
                    }
                } else {
                    OutlinedTextField(
                        value = url, onValueChange = { url = it }, singleLine = true,
                        label = { Text(if (type == SourceType.YOUTUBE) "Lien YouTube" else "URL du flux") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (type == SourceType.RSS) {
                        TextButton(onClick = { showAuth = !showAuth }) {
                            Text(if (showAuth) "Masquer les identifiants" else "Flux protégé par identifiant ?")
                        }
                        if (showAuth) {
                            OutlinedTextField(user, { user = it }, label = { Text("Utilisateur") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(pass, { pass = it }, label = { Text("Mot de passe") }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (type != SourceType.ODEON) TextButton(
                enabled = url.isNotBlank() && !busy,
                onClick = { onAdd(type, url, user, pass) },
            ) { Text(if (busy) "Ajout…" else "Ajouter") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastScreen(vm: MainViewModel, podcastId: Long, onBack: () -> Unit, onOpenPlayer: () -> Unit) {
    val podcast by vm.podcast(podcastId).collectAsStateWithLifecycle(null)
    val episodes by remember(podcastId) { vm.episodesOf(podcastId) }.collectAsStateWithLifecycle(emptyList())
    val player by vm.player.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val p = podcast ?: return

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
                actions = {
                    if (!app.podhub.data.Repository.isVirtual(p)) IconButton(onClick = { vm.refresh(p) }) { Icon(Icons.Filled.Refresh, "Actualiser") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Se désabonner") }
                },
            )
        },
        bottomBar = { MiniPlayer(player, onOpenPlayer) { vm.player.togglePlay() } },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            EpisodeList(vm, episodes, false, player.episodeId, header = {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Artwork(p.imageUrl, 96.dp, p.type)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(p.type.label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            p.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                            Text("${episodes.size} épisodes", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    p.description?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it.replace(Regex("<[^>]+>"), ""), maxLines = 4, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (!app.podhub.data.Repository.isVirtual(p)) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (p.type == SourceType.YOUTUBE) "Convertir les nouvelles vidéos en MP3 automatiquement"
                            else "Télécharger les nouveaux épisodes automatiquement",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = p.autoDownload, onCheckedChange = { vm.setAutoDownload(p, it) })
                    }
                }
            }) {}
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Supprimer « ${p.title} » ?") },
        text = { Text("L'abonnement et tous ses fichiers téléchargés seront supprimés.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deletePodcast(p); onBack() }) { Text("Supprimer") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(vm: MainViewModel, onBack: () -> Unit) {
    val s by vm.player.state.collectAsStateWithLifecycle()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val speeds = listOf(0.8f, 1f, 1.2f, 1.5f, 1.75f, 2f)

    Scaffold(topBar = {
        TopAppBar(title = {}, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Filled.KeyboardArrowDown, "Fermer") }
        })
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Artwork(s.imageUrl, 280.dp)
            Spacer(Modifier.height(24.dp))
            Text(s.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(s.podcastTitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(24.dp))
            val dur = s.durationMs.coerceAtLeast(1)
            Slider(
                value = dragging ?: (s.positionMs.toFloat() / dur).coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { vm.player.seekTo((it * dur).toLong()) }; dragging = null },
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatDuration(dragging?.let { (it * dur).toLong() } ?: s.positionMs).ifEmpty { "0:00" }, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Text(formatDuration(s.durationMs), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                IconButton(onClick = { vm.player.seekBy(-15_000) }, Modifier.size(56.dp)) {
                    Icon(Icons.Filled.Replay, "Reculer de 15 s", Modifier.size(36.dp))
                }
                FilledIconButton(onClick = { vm.player.togglePlay() }, Modifier.size(80.dp)) {
                    if (s.buffering) CircularProgressIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.onPrimary)
                    else Icon(if (s.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Lecture/pause", Modifier.size(48.dp))
                }
                IconButton(onClick = { vm.player.seekBy(30_000) }, Modifier.size(56.dp)) {
                    Icon(Icons.Filled.Forward30, "Avancer de 30 s", Modifier.size(36.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = {
                val next = speeds[(speeds.indexOf(s.speed).takeIf { it >= 0 }?.plus(1) ?: 1) % speeds.size]
                vm.player.setSpeed(next)
            }) { Text("Vitesse ×${"%.2f".format(s.speed).trimEnd('0').trimEnd('.', ',')}") }
        }
    }
}
