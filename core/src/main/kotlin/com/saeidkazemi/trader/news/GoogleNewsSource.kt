package com.saeidkazemi.trader.news

import com.saeidkazemi.trader.data.remote.Http
import okhttp3.Request
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** یک آیتم خام RSS. */
data class RssEntry(
    val title: String,
    val link: String,
    val pubDate: Long?,
    val source: String,
    val description: String
)

/** تجزیه‌گر سبک RSS (بدون وابستگی به کتابخانه XML پلتفرم؛ روی اندروید و ویندوز یکسان کار می‌کند). */
object RssParser {

    private val itemRx = Regex("<item\\b[^>]*>([\\s\\S]*?)</item>", RegexOption.IGNORE_CASE)

    fun parse(xml: String): List<RssEntry> {
        return itemRx.findAll(xml).mapNotNull { m ->
            val block = m.groupValues[1]
            val title = clean(tag(block, "title"))
            if (title.isBlank()) return@mapNotNull null
            val link = clean(tag(block, "link"))
            val source = clean(tag(block, "source"))
            val desc = clean(tag(block, "description"))
            val date = parseDate(clean(tag(block, "pubDate")))
            RssEntry(title, link, date, source, desc)
        }.toList()
    }

    private fun tag(block: String, name: String): String {
        val rx = Regex("<$name\\b[^>]*>([\\s\\S]*?)</$name>", RegexOption.IGNORE_CASE)
        return rx.find(block)?.groupValues?.get(1) ?: ""
    }

    /** حذف CDATA، تگ‌های HTML و تبدیل موجودیت‌ها. */
    fun clean(s: String): String {
        var t = s.trim()
        if (t.startsWith("<![CDATA[")) t = t.removePrefix("<![CDATA[").removeSuffix("]]>")
        t = decodeEntities(t)
        t = t.replace(Regex("<[^>]+>"), " ")
        t = decodeEntities(t)
        return t.replace(Regex("\\s+"), " ").trim()
    }

    fun decodeEntities(s: String): String {
        var t = s
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
        t = Regex("&#(\\d+);").replace(t) { m ->
            m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
        }
        t = Regex("&#x([0-9a-fA-F]+);").replace(t) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
        }
        return t.replace("&amp;", "&")
    }

    fun parseDate(s: String): Long? {
        if (s.isBlank()) return null
        return try {
            ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * جستجوی اخبار در Google News (RSS رایگان و بدون کلید) — برای ارز دیجیتال، ارز خارجی، طلا
 * و همچنین اخبار فارسی سهام بورس تهران.
 */
class GoogleNewsSource {

    private val client by lazy {
        Http.client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * @param query عبارت جستجو
     * @param persian اگر true باشد اخبار فارسی (ایران) جستجو می‌شود، وگرنه انگلیسی.
     */
    fun search(query: String, persian: Boolean, days: Int = 7): List<RssEntry> {
        val q = URLEncoder.encode("$query when:${days}d", "UTF-8")
        val locale = if (persian) "hl=fa&gl=IR&ceid=IR:fa" else "hl=en-US&gl=US&ceid=US:en"
        val req = Request.Builder()
            .url("https://news.google.com/rss/search?q=$q&$locale")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) MoameleYar/1.1")
            .header("Accept", "application/rss+xml, application/xml, text/xml")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP " + resp.code)
            val body = resp.body?.string() ?: return emptyList()
            return RssParser.parse(body).map { e ->
                // گوگل نام ناشر را به انتهای عنوان اضافه می‌کند: «عنوان - ناشر»
                val title = if (e.source.isNotBlank() && e.title.endsWith(" - " + e.source)) {
                    e.title.removeSuffix(" - " + e.source)
                } else e.title
                e.copy(title = title)
            }
        }
    }
}
