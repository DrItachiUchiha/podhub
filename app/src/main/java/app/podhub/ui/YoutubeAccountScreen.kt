package app.podhub.ui

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.podhub.download.YoutubeAuth

private const val YT_LOGIN =
    "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2F"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YoutubeAccountScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var loggedIn by remember { mutableStateOf(YoutubeAuth.isLoggedIn(ctx)) }
    var browsing by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { YoutubeAuth.import(ctx, uri) }
                .onSuccess { loggedIn = true; vm.toast("Cookies YouTube importés"); vm.refreshAll() }
                .onFailure { vm.toast(it.message ?: "Import impossible") }
        }
    }

    fun finishLogin() {
        CookieManager.getInstance().flush()
        browsing = false
        loggedIn = YoutubeAuth.isLoggedIn(ctx)
        if (loggedIn) { vm.toast("Compte YouTube connecté"); vm.refreshAll() }
        else vm.toast("Connexion non détectée")
    }

    BackHandler {
        when {
            browsing && webView?.canGoBack() == true -> webView?.goBack()
            browsing -> finishLogin()
            else -> onBack()
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Compte YouTube") },
            navigationIcon = {
                IconButton(onClick = { if (browsing) finishLogin() else onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                }
            },
            actions = { if (browsing) TextButton(onClick = ::finishLogin) { Text("Terminé") } },
        )
    }) { pad ->
        if (browsing) {
            AndroidView(
                modifier = Modifier.padding(pad).consumeWindowInsets(pad).imePadding().fillMaxSize(),
                factory = { c ->
                    WebView(c).apply {
                        // Google refuse souvent les navigateurs intégrés : on se présente comme Chrome sur PC.
                        configure(desktop = true)
                        webViewClient = object : ExternalSchemeBlocker() {
                            override fun onPageFinished(view: WebView, url: String) {
                                // Retour sur youtube.com après la connexion => terminé automatiquement.
                                if (url.startsWith("https://www.youtube.com") && YoutubeAuth.isLoggedIn(c)) finishLogin()
                            }
                        }
                        loadUrl(YT_LOGIN)
                        webView = this
                    }
                },
            )
            return@Scaffold
        }

        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (loggedIn) Icons.Filled.CheckCircle else Icons.Filled.AccountCircle, null,
                    tint = if (loggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(if (loggedIn) "Connecté" else "Non connecté", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                "La connexion sert aux vidéos réservées aux membres de chaînes où tu es abonné (payant). " +
                    "Elles apparaissent avec un 🔒 dans les abonnements YouTube et se téléchargent en MP3 avec ton compte.",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (!loggedIn) {
                Button(onClick = { browsing = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Login, null); Spacer(Modifier.width(8.dp)); Text("Se connecter à YouTube")
                }
                HorizontalDivider()
                Text("Si Google bloque la connexion (« navigateur non sécurisé ») :", style = MaterialTheme.typography.titleSmall)
                Text(
                    "1. Sur ton PC, dans Chrome ou Firefox, installe l'extension « Get cookies.txt LOCALLY ».\n" +
                        "2. Ouvre youtube.com connecté à ton compte, clique sur l'extension, puis « Export ».\n" +
                        "3. Envoie le fichier cookies.txt sur ton téléphone et importe-le ici.\n" +
                        "Ce fichier donne accès à ton compte : supprime-le du PC et du téléphone une fois importé.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { importer.launch(arrayOf("text/plain", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Importer cookies.txt")
                }
            } else {
                OutlinedButton(
                    onClick = { YoutubeAuth.logout(ctx); loggedIn = YoutubeAuth.isLoggedIn(ctx); vm.toast("Déconnecté") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Icon(Icons.Filled.Logout, null); Spacer(Modifier.width(8.dp)); Text("Se déconnecter") }
            }

            Text(
                "⚠ YouTube peut limiter temporairement un compte qui télécharge beaucoup via yt-dlp. " +
                    "Évite d'activer le téléchargement automatique sur des dizaines de chaînes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
