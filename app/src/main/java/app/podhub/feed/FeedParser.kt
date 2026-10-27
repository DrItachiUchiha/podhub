package app.podhub.feed

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Locale

data class ParsedEpisode(
    val guid: String,
    val title: String,
    val description: String?,
    val pubDate: Long,
    val mediaUrl: String,
    val durationMs: Long,
    val imageUrl: String?,
)

data class ParsedFeed(
    val title: String,
    val description: String?,
    val author: String?,
    val imageUrl: String?,
    val episodes: List<ParsedEpisode>,
)

/** Analyse les flux RSS 2.0 (podcasts, Patreon) et Atom (chaînes YouTube). */
object FeedParser {

    fun parse(input: InputStream): ParsedFeed {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)

        var feedTitle = ""
        var feedDesc: String? = null
        var feedAuthor: String? = null
        var feedImage: String? = null
        val episodes = mutableListOf<ParsedEpisode>()

        var inItem = false
        var inImage = false
        var cur = ItemBuilder()

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = p.name
                    when {
                        name == "item" || name == "entry" -> { inItem = true; cur = ItemBuilder() }
                        name == "image" && !inItem -> inImage = true
                        inItem -> when (name) {
                            "title" -> cur.title = p.text()
                            "guid", "id" -> cur.guid = p.text()
                            "yt:videoId" -> cur.videoId = p.text()
                            "description", "content:encoded", "media:description", "summary", "itunes:summary" ->
                                p.text().takeIf { it.isNotBlank() }?.let { if (cur.desc == null || name == "content:encoded") cur.desc = it }
                            "pubDate", "published" -> cur.date = parseDate(p.text())
                            "enclosure" -> {
                                val type = p.getAttributeValue(null, "type") ?: ""
                                val url = p.getAttributeValue(null, "url")
                                if (url != null && (cur.media == null || type.startsWith("audio"))) cur.media = url
                            }
                            "link" -> {
                                val href = p.getAttributeValue(null, "href")
                                if (href != null) {
                                    if (cur.link == null) cur.link = href
                                } else cur.link = cur.link ?: p.text()
                            }
                            "itunes:duration" -> cur.duration = parseDuration(p.text())
                            "itunes:image" -> cur.image = p.getAttributeValue(null, "href")
                            "media:thumbnail" -> cur.image = cur.image ?: p.getAttributeValue(null, "url")
                        }
                        else -> when (name) {
                            "title" -> if (feedTitle.isEmpty()) feedTitle = p.text()
                            "description", "subtitle" -> if (feedDesc == null) feedDesc = p.text()
                            "itunes:author" -> feedAuthor = p.text()
                            "name" -> if (feedAuthor == null) feedAuthor = p.text()
                            "itunes:image" -> feedImage = p.getAttributeValue(null, "href") ?: feedImage
                            "url" -> if (inImage && feedImage == null) feedImage = p.text()
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "item", "entry" -> {
                        inItem = false
                        cur.build()?.let(episodes::add)
                    }
                    "image" -> inImage = false
                }
            }
            event = p.next()
        }
        return ParsedFeed(feedTitle.ifBlank { "Sans titre" }, feedDesc, feedAuthor, feedImage, episodes)
    }

    private class ItemBuilder {
        var title: String? = null
        var guid: String? = null
        var videoId: String? = null
        var desc: String? = null
        var date: Long = 0
        var media: String? = null
        var link: String? = null
        var duration: Long = 0
        var image: String? = null

        fun build(): ParsedEpisode? {
            val url = when {
                videoId != null -> "https://www.youtube.com/watch?v=$videoId"
                media != null -> media
                else -> null
            } ?: return null
            return ParsedEpisode(
                guid = videoId ?: guid ?: url,
                title = title ?: "Épisode",
                description = desc,
                pubDate = date,
                mediaUrl = url,
                durationMs = duration,
                imageUrl = image ?: videoId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" },
            )
        }
    }

    /** Lit le texte d'un élément (y compris CDATA) et avance jusqu'à sa fermeture. */
    private fun XmlPullParser.text(): String {
        val sb = StringBuilder()
        val depth = depth
        while (true) {
            val e = next()
            if (e == XmlPullParser.TEXT || e == XmlPullParser.CDSECT) sb.append(text)
            if (e == XmlPullParser.END_TAG && this.depth == depth) break
            if (e == XmlPullParser.END_DOCUMENT) break
        }
        return sb.toString().trim()
    }

    private val rfc822 = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, d MMM yyyy HH:mm:ss Z",
        "dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm Z",
    )

    fun parseDate(s: String): Long {
        val t = s.trim()
        runCatching { return OffsetDateTime.parse(t).toInstant().toEpochMilli() }
        for (f in rfc822) {
            runCatching { return SimpleDateFormat(f, Locale.ENGLISH).parse(t)!!.time }
        }
        return 0
    }

    fun parseDuration(s: String): Long {
        val parts = s.trim().split(":").mapNotNull { it.toDoubleOrNull() }
        if (parts.isEmpty()) return 0
        val seconds = parts.fold(0.0) { acc, v -> acc * 60 + v }
        return (seconds * 1000).toLong()
    }
}
