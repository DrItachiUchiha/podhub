package app.podhub

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.podhub.data.SourceType
import app.podhub.ui.*
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val sharedText = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        handleIntent(intent)

        setContent {
            val ctx = LocalContext.current
            val dark = isSystemInDarkTheme()
            val scheme = when {
                Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                val nav = rememberNavController()
                val snackbar = remember { SnackbarHostState() }
                LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

                // Lien reçu via « Partager » : YouTube => abonnement/MP3, od3on => navigateur, sinon RSS.
                val shared by sharedText.collectAsState()
                LaunchedEffect(shared) {
                    val text = shared ?: return@LaunchedEffect
                    sharedText.value = null
                    val url = Regex("""https?://\S+""").find(text)?.value ?: return@LaunchedEffect
                    when {
                        "od3on." in url -> nav.navigate("odeon?url=${Uri.encode(url)}")
                        "youtu" in url -> vm.addSource(SourceType.YOUTUBE, url, null, null) {}
                        else -> vm.addSource(SourceType.RSS, url, null, null) {}
                    }
                }

                NavHost(nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            vm, snackbar,
                            onOpenPodcast = { nav.navigate("podcast/$it") },
                            onOpenPlayer = { nav.navigate("player") },
                            onOpenOdeon = { url -> nav.navigate(if (url == null) "odeon" else "odeon?url=${Uri.encode(url)}") },
                            onOpenYoutubeAccount = { nav.navigate("youtube-account") },
                        )
                    }
                    composable("youtube-account") { YoutubeAccountScreen(vm, onBack = { nav.popBackStack() }) }
                    composable("podcast/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                        PodcastScreen(vm, it.arguments!!.getLong("id"), onBack = { nav.popBackStack() },
                            onOpenPlayer = { nav.navigate("player") })
                    }
                    composable("player") { PlayerScreen(vm, onBack = { nav.popBackStack() }) }
                    composable(
                        "odeon?url={url}",
                        arguments = listOf(navArgument("url") { type = NavType.StringType; nullable = true; defaultValue = null }),
                    ) {
                        OdeonScreen(vm, it.arguments?.getString("url"), onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            sharedText.value = intent.getStringExtra(Intent.EXTRA_TEXT)
        }
    }
}
