package com.saeidkazemi.trader.news

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.saeidkazemi.trader.data.remote.Http
import okhttp3.FormBody
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * ترجمه خودکار تیتر اخبار انگلیسی به فارسی (رایگان و بدون کلید).
 *
 * منبع اول: Google Translate (چند تیتر در یک درخواست). اگر در دسترس نبود: MyMemory (تیتر به تیتر).
 * ترجمه‌ها کش می‌شوند تا هر تیتر فقط یک‌بار ترجمه شود. اگر هیچ سرویسی در دسترس نبود، متن اصلی نمایش داده می‌شود.
 */
object Translator {

    private val cache = ConcurrentHashMap<String, String>()
    private const val MAX_CACHE = 3000

    @Volatile private var googleFailUntil = 0L
    @Volatile private var memoryFailUntil = 0L

    private val client by lazy {
        Http.client.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36"

    fun cached(text: String): String? = cache[text.trim()]

    /** ترجمه فهرستی از متن‌ها؛ خروجی: متن اصلی ← ترجمه (فقط موارد موفق). */
    fun toPersian(texts: List<String>): Map<String, String> {
        val wanted = texts.map { it.trim() }.filter { it.isNotEmpty() && !looksPersian(it) }.distinct()
        val out = HashMap<String, String>()
        val todo = ArrayList<String>()
        for (t in wanted) {
            val c = cache[t]
            if (c != null) out[t] = c else todo.add(t)
        }
        if (todo.isEmpty()) return out
        val now = System.currentTimeMillis()
        if (now >= googleFailUntil) {
            try {
                for (chunk in chunked(todo)) {
                    val r = google(chunk)
                    for ((k, v) in r) put(k, v, out)
                }
            } catch (e: Exception) {
                googleFailUntil = now + 10 * 60_000L
            }
        }
        val rest = todo.filter { it !in out }
        if (rest.isNotEmpty() && System.currentTimeMillis() >= memoryFailUntil) {
            for (t in rest.take(15)) {
                try {
                    memory(t)?.let { put(t, it, out) }
                } catch (e: Exception) {
                    memoryFailUntil = System.currentTimeMillis() + 10 * 60_000L
                    break
                }
            }
        }
        return out
    }

    private fun put(src: String, fa: String, out: MutableMap<String, String>) {
        val v = polish(fa)
        if (v.isBlank() || v == src) return
        if (cache.size > MAX_CACHE) cache.clear()
        cache[src] = v
        out[src] = v
    }

    /** دسته‌بندی تیترها تا طول هر درخواست محدود بماند. */
    private fun chunked(list: List<String>): List<List<String>> {
        val res = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        var len = 0
        for (t in list) {
            val s = t.replace('\n', ' ').take(400)
            if (cur.isNotEmpty() && (len + s.length > 3000 || cur.size >= 25)) {
                res.add(cur); cur = ArrayList(); len = 0
            }
            cur.add(s); len += s.length + 1
        }
        if (cur.isNotEmpty()) res.add(cur)
        return res
    }

    /** Google: تیترها با خط جدید جدا و در یک درخواست POST فرستاده می‌شوند. */
    private fun google(lines: List<String>): Map<String, String> {
        val joined = lines.joinToString("\n")
        var lastErr: Exception? = null
        for (host in listOf("translate.googleapis.com", "translate.google.com")) {
            try {
                val req = Request.Builder()
                    .url("https://$host/translate_a/single?client=gtx&sl=auto&tl=fa&dt=t")
                    .header("User-Agent", UA)
                    .post(FormBody.Builder().add("q", joined).build())
                    .build()
                val body = client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP " + resp.code)
                    resp.body?.string() ?: throw IllegalStateException("empty")
                }
                val root = JsonParser.parseString(body).asJsonArray
                val segs = root[0] as? JsonArray ?: throw IllegalStateException("format")
                val sb = StringBuilder()
                for (seg in segs) {
                    val a = seg as? JsonArray ?: continue
                    if (a.size() > 0 && !a[0].isJsonNull) sb.append(a[0].asString)
                }
                val tr = sb.toString().split('\n').map { it.trim() }
                if (tr.size < lines.size) {
                    // ترتیب خطوط به‌هم خورده؛ برای اطمینان تیتر به تیتر ترجمه نمی‌کنیم، فقط اگر یک خط بود می‌پذیریم.
                    if (lines.size == 1) return mapOf(lines[0] to tr.joinToString(" "))
                    throw IllegalStateException("line mismatch")
                }
                val m = HashMap<String, String>()
                for (i in lines.indices) m[lines[i]] = tr[i]
                return m
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: IllegalStateException("google failed")
    }

    /** MyMemory: یک تیتر در هر درخواست (سهمیه رایگان روزانه دارد). */
    private fun memory(text: String): String? {
        val q = URLEncoder.encode(text.take(480), "UTF-8")
        val req = Request.Builder()
            .url("https://api.mymemory.translated.net/get?q=$q&langpair=en%7Cfa")
            .header("User-Agent", UA)
            .build()
        val body = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP " + resp.code)
            resp.body?.string() ?: return null
        }
        val o = JsonParser.parseString(body).asJsonObject
        if (o.get("quotaFinished")?.takeIf { !it.isJsonNull }?.asBoolean == true) throw IllegalStateException("quota")
        val t = o.getAsJsonObject("responseData")?.get("translatedText")?.takeIf { !it.isJsonNull }?.asString ?: return null
        if (t.contains("MYMEMORY WARNING", ignoreCase = true) || !looksPersian(t)) return null
        return RssParser.decodeEntities(t)
    }

    fun looksPersian(s: String): Boolean {
        val letters = s.count { it.isLetter() }
        if (letters == 0) return false
        val fa = s.count { it in '\u0600'..'\u06FF' }
        return fa * 2 >= letters
    }

    /** اصلاح چند اصطلاح رایج که ترجمه ماشینی برای بازار مالی بد برمی‌گرداند. */
    private val GLOSSARY = listOf(
        "تاجران" to "معامله‌گران",
        "تاجر " to "معامله‌گر ",
        "بیت کوین" to "بیت‌کوین",
        "صرافی ها" to "صرافی‌ها",
        "توکن ها" to "توکن‌ها",
        "سکه ها" to "کوین‌ها",
        "بازار نزولی" to "بازار نزولی"
    )

    fun polish(s: String): String {
        var t = s.trim()
        for ((a, b) in GLOSSARY) t = t.replace(a, b)
        return t.replace(Regex("\\s+"), " ")
    }
}
