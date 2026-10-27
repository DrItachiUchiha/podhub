package app.podhub.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Message
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject

private const val ODEON_HOME = "https://www.od3on.com/"

private val MEDIA_EXT = Regex("""\.(mp3|m4a|aac|ogg|oga|opus|wav|flac|mp4|m4v|webm|mov|m3u8)(\?|$)""", RegexOption.IGNORE_CASE)
private val SEGMENT = Regex("""\.(ts|m4s)(\?|$)|/seg-|segment|chunk|init\.mp4""", RegexOption.IGNORE_CASE)

data class Captured(val url: String, val kind: String)

internal const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"

/**
 * Réglages du navigateur. On retire les marqueurs « WebView » de l'user-agent :
 * certains sites (et Google) refusent la connexion depuis un navigateur intégré.
 */
@SuppressLint("SetJavaScriptEnabled")
internal fun WebView.configure(desktop: Boolean) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.mediaPlaybackRequiresUserGesture = true
    settings.javaScriptCanOpenWindowsAutomatically = true
    settings.setSupportMultipleWindows(true)
    settings.setSupportZoom(true)
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    settings.useWideViewPort = desktop
    settings.loadWithOverviewMode = desktop
    settings.userAgentString = if (desktop) DESKTOP_UA else WebSettings.getDefaultUserAgent(context)
        .replace("; wv", "")
        .replace(Regex("""Version/\d+\.\d+ """), "")
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
}

/** Ignore les liens vers d'autres applis (intent://, market://…) qui font planter la page. */
internal open class ExternalSchemeBlocker : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        request.url.scheme !in setOf("http", "https")
}

/** Lit dans la page les sources des lecteurs audio/vidéo, le titre et l'image. */
private const val SCAN_JS = """
(function(){
  var urls = [];
  document.querySelectorAll('audio,video,source').forEach(function(e){
    var u = e.currentSrc || e.src; if (u && u.indexOf('blob:') !== 0) urls.push(u);
  });
  document.querySelectorAll('a[href]').forEach(function(a){
    if (/\.(mp3|m4a|aac|ogg|opus|wav|flac)(\?|$)/i.test(a.href)) urls.push(a.href);
  });
  var og = document.querySelector('meta[property="og:image"]');
  var ogt = document.querySelector('meta[property="og:title"]');
  return JSON.stringify({urls: urls, title: (ogt && ogt.content) || document.title, image: og ? og.content : null});
})()
"""

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OdeonScreen(vm: MainViewModel, startUrl: String?, onBack: () -> Unit) {
    val found = remember { mutableStateListOf<Captured>() }
    var pageTitle by remember { mutableStateOf("") }
    var pageImage by remember { mutableStateOf<String?>(null) }
    var pageUrl by remember { mutableStateOf(startUrl ?: ODEON_HOME) }
    var loading by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var popup by remember { mutableStateOf<WebView?>(null) }
    var menu by remember { mutableStateOf(false) }
    val prefs = LocalContext.current.getSharedPreferences("odeon", Context.MODE_PRIVATE)
    // Mode « ordinateur » activé par défaut : le site mobile d'od3on pose problème.
    var desktop by remember { mutableStateOf(prefs.getBoolean("desktop", true)) }

    fun addFound(url: String) {
        if (url.startsWith("blob:") || SEGMENT.containsMatchIn(url)) return
        val kind = when {
            ".m3u8" in url -> "Flux HLS"
            Regex("""\.(mp4|m4v|webm|mov)""", RegexOption.IGNORE_CASE).containsMatchIn(url) -> "Vidéo (piste audio extraite)"
            else -> "Audio"
        }
        if (found.none { it.url.substringBefore('?') == url.substringBefore('?') }) found.add(Captured(url, kind))
    }

    fun scan(w: WebView) = w.evaluateJavascript(SCAN_JS) { raw ->
        runCatching {
            val json = JSONObject(JSONArray("[$raw]").getString(0))
            pageTitle = json.optString("title")
            pageImage = json.optString("image").takeIf { it.isNotBlank() && it != "null" }
            val arr = json.getJSONArray("urls")
            for (i in 0 until arr.length()) addFound(arr.getString(i))
        }
    }

    BackHandler {
        val p = popup
        when {
            p != null && p.canGoBack() -> p.goBack()
            p != null -> { popup = null; p.destroy() }
            webView?.canGoBack() == true -> webView?.goBack()
            else -> onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pageTitle.ifBlank { "Od3on" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.Close, "Fermer") } },
                actions = {
                    IconButton(onClick = {
                        desktop = !desktop
                        prefs.edit().putBoolean("desktop", desktop).apply()
                        webView?.let { it.configure(desktop); it.reload() }
                    }) {
                        Icon(if (desktop) Icons.Filled.Computer else Icons.Filled.PhoneAndroid,
                            if (desktop) "Version ordinateur (appuie pour la version mobile)" else "Version mobile (appuie pour la version ordinateur)")
                    }
                    IconButton(onClick = { webView?.reload() }) { Icon(Icons.Filled.Refresh, "Recharger") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Se déconnecter (vider les cookies)") },
                                onClick = {
                                    menu = false
                                    CookieManager.getInstance().removeAllCookies { webView?.loadUrl(ODEON_HOME) }
                                    WebStorage.getInstance().deleteAllData()
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            // Masqué pendant la saisie : il recouvrait le bas du formulaire de connexion.
            if (!WindowInsets.isImeVisible) ExtendedFloatingActionButton(
                onClick = { webView?.let(::scan); sheet = true },
                icon = {
                    BadgedBox(badge = { if (found.isNotEmpty()) Badge { Text("${found.size}") } }) {
                        Icon(Icons.Filled.LibraryAdd, null)
                    }
                },
                text = { Text("Récupérer l'audio") },
            )
        },
    ) { pad ->
        // imePadding : l'appli est en plein écran (edge-to-edge), donc Android ne réduit plus la page
        // quand le clavier s'ouvre ; sans ça le clavier cache les champs de connexion d'od3on.
        Box(Modifier.padding(pad).consumeWindowInsets(pad).imePadding().fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        configure(desktop)
                        webChromeClient = object : WebChromeClient() {
                            // Connexion ouverte dans une fenêtre pop-up (souvent le cas des boutons Google/Apple/X).
                            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                                val child = WebView(view.context).apply {
                                    configure(desktop)
                                    webViewClient = ExternalSchemeBlocker()
                                    webChromeClient = object : WebChromeClient() {
                                        override fun onCloseWindow(window: WebView) { popup = null; window.destroy() }
                                    }
                                }
                                (resultMsg.obj as WebView.WebViewTransport).webView = child
                                resultMsg.sendToTarget()
                                popup = child
                                return true
                            }
                        }
                        webViewClient = object : ExternalSchemeBlocker() {
                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                loading = true
                                if (url.substringBefore('#') != pageUrl.substringBefore('#')) found.clear()
                                pageUrl = url
                            }
                            override fun onPageFinished(view: WebView, url: String) {
                                loading = false
                                CookieManager.getInstance().flush()
                                scan(view)
                            }
                            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                // Sites « single page » : l'URL change sans recharger la page.
                                if (url != pageUrl) { pageUrl = url; found.clear(); view.postDelayed({ scan(view) }, 1500) }
                            }
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                val u = request.url.toString()
                                if (MEDIA_EXT.containsMatchIn(u)) view.post { addFound(u) }
                                return null
                            }
                        }
                        loadUrl(startUrl ?: ODEON_HOME)
                        webView = this
                    }
                },
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            popup?.let { child ->
                Surface(Modifier.fillMaxSize()) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { popup = null; child.destroy() }) { Icon(Icons.Filled.Close, "Fermer la fenêtre") }
                            Text("Connexion", style = MaterialTheme.typography.titleMedium)
                        }
                        key(child) { AndroidView(factory = { child }, modifier = Modifier.fillMaxSize()) }
                    }
                }
            }
        }
    }

    if (sheet) ModalBottomSheet(onDismissRequest = { sheet = false }) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Médias trouvés sur cette page", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            if (found.isEmpty()) {
                Text(
                    "Rien pour l'instant. Connecte-toi, ouvre un post audio et lance la lecture quelques secondes " +
                        "dans la page, puis réessaie : l'appli capte le fichier au moment où le lecteur le charge.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else LazyColumn {
                items(found, key = { it.url }) { c ->
                    ListItem(
                        headlineContent = { Text(c.kind) },
                        supportingContent = { Text(c.url.substringBefore('?'), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            FilledTonalButton(onClick = {
                                vm.captureMedia(c.url, pageTitle, pageUrl, pageImage)
                                sheet = false
                            }) { Text("Ajouter") }
                        },
                    )
                }
            }
        }
    }

    DisposableEffect(Unit) { onDispose { webView?.destroy() } }
}
