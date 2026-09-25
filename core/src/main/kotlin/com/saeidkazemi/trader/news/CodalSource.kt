package com.saeidkazemi.trader.news

import com.google.gson.JsonObject
import com.saeidkazemi.trader.data.remote.Http
import com.saeidkazemi.trader.util.Jalali
import okhttp3.Request
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/** یک اطلاعیه کدال. */
data class CodalLetter(
    val symbol: String,
    val company: String,
    val title: String,
    val body: String,
    val url: String,
    val publishedAt: Long?,
    val viaTelegram: Boolean
)

/**
 * اطلاعیه‌های رسمی شرکت‌های بورسی از «کدال» (سامانه جامع اطلاع‌رسانی ناشران).
 *
 * ۱) ابتدا از سرویس جستجوی search.codal.ir استفاده می‌شود.
 * ۲) چون این سرویس از بعضی شبکه‌ها (مثلاً خارج از ایران) در دسترس نیست، در صورت شکست،
 *    نسخه عمومی وب کانال «کدال۳۶۰» (t.me/s/Codal360_ir) که همان اطلاعیه‌ها را بازنشر می‌کند
 *    با هشتگ نماد جستجو می‌شود.
 */
class CodalSource {

    private val client by lazy {
        Http.client.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val browserUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    class Result(val letters: List<CodalLetter>, val usedSource: String?, val failed: List<String>)

    fun letters(symbol: String, limit: Int = 12): Result {
        val failed = mutableListOf<String>()
        try {
            val list = fromCodalApi(symbol, limit)
            if (list.isNotEmpty()) return Result(list, "کدال", failed)
        } catch (e: Exception) {
            failed.add("کدال")
        }
        try {
            val list = fromTelegram(symbol, limit)
            return Result(list, if (list.isNotEmpty()) "کانال کدال۳۶۰" else null, failed)
        } catch (e: Exception) {
            failed.add("کانال کدال۳۶۰")
        }
        return Result(emptyList(), null, failed)
    }

    private fun fromCodalApi(symbol: String, limit: Int): List<CodalLetter> {
        val sym = URLEncoder.encode(symbol, "UTF-8")
        val url = "https://search.codal.ir/api/search/v2/q?&Audited=true&AuditorRef=-1&Category=-1" +
            "&Childs=true&CompanyState=-1&CompanyType=-1&Consolidatable=true&IsNotAudited=false" +
            "&Length=-1&LetterType=-1&Mains=true&NotAudited=true&NotConsolidatable=true" +
            "&PageNumber=1&Publisher=false&Symbol=$sym&TracingNo=-1&search=true"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", browserUa)
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", "https://codal.ir")
            .header("Referer", "https://codal.ir/")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP " + resp.code)
            val body = resp.body?.string() ?: return emptyList()
            val root = Http.gson.fromJson(body, JsonObject::class.java) ?: return emptyList()
            val arr = root.getAsJsonArray("Letters") ?: return emptyList()
            val out = mutableListOf<CodalLetter>()
            for (el in arr) {
                if (!el.isJsonObject) continue
                val o = el.asJsonObject
                fun str(k: String): String = o.get(k)?.takeIf { !it.isJsonNull }?.asString ?: ""
                val title = str("Title")
                if (title.isBlank()) continue
                val rel = str("Url")
                val link = when {
                    rel.startsWith("http") -> rel
                    rel.isNotBlank() -> "https://codal.ir" + (if (rel.startsWith("/")) rel else "/$rel")
                    else -> "https://codal.ir"
                }
                out.add(
                    CodalLetter(
                        symbol = str("Symbol"),
                        company = str("CompanyName"),
                        title = title,
                        body = "",
                        url = link,
                        publishedAt = Jalali.parseToEpochMillis(str("PublishDateTime")),
                        viaTelegram = false
                    )
                )
                if (out.size >= limit) break
            }
            return out
        }
    }

    private val wrapSplit = Regex("class=\"tgme_widget_message_wrap")
    private val textRx = Regex(
        "<div class=\"tgme_widget_message_text[^\"]*\"[^>]*>([\\s\\S]*?)</div>",
        RegexOption.IGNORE_CASE
    )
    private val timeRx = Regex("<time[^>]*datetime=\"([^\"]+)\"")
    private val codalLinkRx = Regex("href=\"(https://codal\\.ir/Reports/Decision\\.aspx[^\"]+)\"")
    private val postRx = Regex("data-post=\"([^\"]+)\"")
    private val hashtagRx = Regex("#([\\p{L}\\p{M}\\d_\u200c]+)")

    private fun normLetters(s: String): String =
        s.replace('ي', 'ی').replace('ك', 'ک').replace("\u200c", "").trim()

    private fun fromTelegram(symbol: String, limit: Int): List<CodalLetter> {
        val q = URLEncoder.encode("#$symbol", "UTF-8")
        val req = Request.Builder()
            .url("https://t.me/s/Codal360_ir?q=$q")
            .header("User-Agent", browserUa)
            .header("Accept", "text/html")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP " + resp.code)
            val html = resp.body?.string() ?: return emptyList()
            val chunks = html.split(wrapSplit).drop(1)
            val wanted = normLetters(symbol)
            val out = mutableListOf<CodalLetter>()
            for (chunk in chunks.asReversed()) { // جدیدترین پیام‌ها در انتهای صفحه هستند
                val rawText = textRx.find(chunk)?.groupValues?.get(1) ?: continue
                val text = RssParser.clean(rawText.replace(Regex("<br\\s*/?>"), "\n"))
                // فقط پیام‌هایی که دقیقاً هشتگ همین نماد را دارند (نه نمادهای مشابه مثل #جم_پیلن برای #جم)
                val hit = hashtagRx.findAll(text).any { normLetters(it.groupValues[1]) == wanted }
                if (!hit) continue
                val ts = timeRx.find(chunk)?.groupValues?.get(1)?.let {
                    try {
                        OffsetDateTime.parse(it).toInstant().toEpochMilli()
                    } catch (e: Exception) {
                        null
                    }
                }
                val link = codalLinkRx.find(chunk)?.groupValues?.get(1)?.let { RssParser.decodeEntities(it) }
                    ?: postRx.find(chunk)?.groupValues?.get(1)?.let { "https://t.me/$it" }
                    ?: "https://t.me/s/Codal360_ir"
                out.add(
                    CodalLetter(
                        symbol = symbol,
                        company = "",
                        title = telegramTitle(text),
                        body = text.take(600),
                        url = link,
                        publishedAt = ts,
                        viaTelegram = true
                    )
                )
                if (out.size >= limit) break
            }
            return out
        }
    }

    /** عنوان خوانا از متن پیام: حذف هشتگ‌ها و علائم تزئینی. */
    private fun telegramTitle(text: String): String {
        val cleaned = text
            .replace(Regex("#\\S+"), " ")
            .replace(Regex("[▪️➖⬇️❤👏📥]"), " ")
            .replace(Regex("@\\S+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (cleaned.length > 140) cleaned.take(140) + "…" else cleaned
    }
}
