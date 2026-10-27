package app.podhub.download

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import java.io.File
import java.io.IOException

/**
 * Connexion au compte YouTube, nécessaire pour les vidéos réservées aux membres.
 * Deux sources de cookies, passées à yt-dlp au format Netscape (--cookies) :
 *  1. un cookies.txt importé (exporté depuis le navigateur du PC) ;
 *  2. sinon, les cookies de la connexion faite dans le navigateur intégré de l'appli.
 */
object YoutubeAuth {
    private const val YT = "https://www.youtube.com"
    private fun importedFile(ctx: Context) = File(ctx.filesDir, "yt_cookies_imported.txt")

    fun isLoggedIn(ctx: Context): Boolean =
        importedFile(ctx).exists() || webCookies().any { it.first == "SAPISID" || it.first == "__Secure-3PAPISID" }

    /** Fichier cookies à passer à yt-dlp, ou null si pas connecté. */
    fun cookieFile(ctx: Context): File? {
        val imported = importedFile(ctx)
        if (imported.exists()) return imported
        val cookies = webCookies()
        if (cookies.none { it.first == "SAPISID" || it.first == "__Secure-3PAPISID" }) return null
        val expiry = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        val file = File(ctx.filesDir, "yt_cookies_web.txt")
        file.writeText(buildString {
            appendLine("# Netscape HTTP Cookie File")
            cookies.forEach { (k, v) -> appendLine(".youtube.com\tTRUE\t/\tTRUE\t$expiry\t$k\t$v") }
        })
        return file
    }

    fun import(ctx: Context, uri: Uri) {
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?: throw IOException("Fichier illisible")
        if ("youtube.com" !in text) throw IOException("Ce fichier ne contient pas de cookies YouTube")
        importedFile(ctx).writeText(text)
    }

    fun logout(ctx: Context) {
        importedFile(ctx).delete()
        File(ctx.filesDir, "yt_cookies_web.txt").delete()
        val cm = CookieManager.getInstance()
        for (domain in listOf(YT, "https://accounts.google.com", "https://www.google.com")) {
            cm.getCookie(domain)?.split(";")?.forEach { c ->
                val name = c.substringBefore('=').trim()
                val host = Uri.parse(domain).host!!.removePrefix("www.")
                cm.setCookie(domain, "$name=; Max-Age=0; Path=/; Domain=.$host")
                cm.setCookie(domain, "$name=; Max-Age=0; Path=/")
            }
        }
        cm.flush()
    }

    private fun webCookies(): List<Pair<String, String>> =
        CookieManager.getInstance().getCookie(YT).orEmpty().split(";").mapNotNull { c ->
            val i = c.indexOf('=')
            if (i <= 0) null else c.substring(0, i).trim() to c.substring(i + 1).trim()
        }
}
