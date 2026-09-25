package com.saeidkazemi.trader.news

import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.MarketKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * سرویس اخبار: برای هر دارایی اخبار و اطلاعیه‌های مرتبط را جمع می‌کند، احساس هر خبر را می‌سنجد
 * و یک «اثر خبری» روی امتیاز سیگنال می‌سازد.
 *
 * - سهام بورس تهران: اطلاعیه‌های رسمی کدال (وزن بیشتر) + اخبار فارسی
 * - ارز دیجیتال / ارز خارجی / طلا: اخبار انگلیسی جهانی
 *
 * نتایج کش می‌شوند (پیش‌فرض ۲۰ دقیقه) تا به سرویس‌ها فشار نیاید.
 */
class NewsService(
    private val google: GoogleNewsSource = GoogleNewsSource(),
    private val codal: CodalSource = CodalSource()
) {

    companion object {
        const val CACHE_MS = 20 * 60 * 1000L
        const val FAIL_CACHE_MS = 5 * 60 * 1000L
        const val MAX_ADJ = 12.0
        private const val HALF_LIFE_HOURS = 36.0
    }

    private val cache = ConcurrentHashMap<String, NewsDigest>()
    private val gate = Semaphore(3)

    private val fxNames = mapOf(
        "EUR" to "euro", "GBP" to "British pound", "CHF" to "Swiss franc", "JPY" to "Japanese yen",
        "AED" to "UAE dirham", "TRY" to "Turkish lira", "CNY" to "Chinese yuan"
    )

    fun cached(assetId: String): NewsDigest? = cache[assetId]

    fun allCached(): List<NewsDigest> = cache.values.toList()

    /** اخبار چند دارایی به‌صورت موازی (با محدودیت هم‌زمانی). */
    suspend fun digests(assets: List<Asset>, force: Boolean = false): Map<String, NewsDigest> = coroutineScope {
        assets.map { a -> async { a.id to digest(a, force) } }.awaitAll().toMap()
    }

    suspend fun digest(asset: Asset, force: Boolean = false): NewsDigest {
        val now = System.currentTimeMillis()
        val old = cache[asset.id]
        if (!force && old != null) {
            val ttl = if (old.items.isEmpty()) FAIL_CACHE_MS else CACHE_MS
            if (now - old.fetchedAt < ttl) return old
        }
        val fresh = gate.withPermit { withContext(Dispatchers.IO) { fetch(asset) } }
        // اگر دریافت جدید شکست خورد ولی قبلاً خبر داشتیم، همان را نگه می‌داریم.
        val result = if (fresh.items.isEmpty() && old != null && old.items.isNotEmpty()) {
            old.copy(fetchedAt = now - CACHE_MS + FAIL_CACHE_MS)
        } else fresh
        cache[asset.id] = result
        return result
    }

    private fun fetch(asset: Asset): NewsDigest {
        val items = mutableListOf<NewsItem>()
        val ok = mutableListOf<String>()
        val failed = mutableListOf<String>()

        when (asset.market) {
            MarketKind.IR_STOCK -> {
                val r = codal.letters(asset.symbol)
                failed.addAll(r.failed)
                if (r.usedSource != null) ok.add(r.usedSource)
                for (l in r.letters) {
                    val s = Sentiment.analyze(l.title + " " + l.body)
                    items.add(
                        NewsItem(
                            title = l.title,
                            summary = l.body.take(280),
                            url = l.url,
                            publisher = if (l.viaTelegram) "کدال (از کانال کدال۳۶۰)" else "کدال",
                            publishedAt = l.publishedAt,
                            kind = if (l.viaTelegram) NewsSourceKind.CODAL_TELEGRAM else NewsSourceKind.CODAL,
                            sentiment = s.score,
                            matched = s.matched,
                            lang = "fa"
                        )
                    )
                }
                collectGoogle("\"${asset.name}\" OR \"نماد ${asset.symbol}\" when:14d", true, items, ok, failed)
            }

            MarketKind.CRYPTO -> collectGoogle(
                "\"${asset.name}\" ${asset.symbol} crypto when:7d", false, items, ok, failed
            )

            MarketKind.FX -> {
                val code = asset.id.removePrefix("fx:")
                val name = fxNames[code] ?: code
                collectGoogle(
                    "(\"$code/USD\" OR \"USD/$code\" OR \"$name\") (forex OR \"central bank\" OR inflation OR currency) when:7d",
                    false, items, ok, failed
                )
            }

            MarketKind.METAL -> collectGoogle("\"gold price\" OR \"gold prices\" when:7d", false, items, ok, failed)
        }

        val dedup = items
            .distinctBy { Sentiment.normalizeFa(it.title).lowercase().take(80) }
            .sortedByDescending { it.publishedAt ?: 0L }
            .take(20)
        return summarize(asset, dedup, ok.distinct(), failed.distinct())
    }

    /** صفحه‌های تبدیل قیمت و «پیش‌بینی قیمت» خبر نیستند و فقط نویز اضافه می‌کنند. */
    private fun isJunk(title: String): Boolean {
        val t = title.lowercase()
        return JUNK.any { it in t }
    }

    private val JUNK = listOf(
        "price today", "converter", "price prediction", "live price", "to usd price", "price chart",
        "exchange rate today", "how to buy", "قیمت لحظه‌ای", "قیمت امروز"
    )

    private fun collectGoogle(
        query: String,
        persian: Boolean,
        into: MutableList<NewsItem>,
        ok: MutableList<String>,
        failed: MutableList<String>
    ) {
        try {
            val list = google.search(query, persian)
            ok.add(if (persian) "اخبار فارسی" else "اخبار جهانی")
            for (e in list.filterNot { isJunk(it.title) }.take(15)) {
                val s = Sentiment.analyze(e.title + " " + e.description.take(200))
                into.add(
                    NewsItem(
                        title = e.title,
                        summary = "",
                        url = e.link,
                        publisher = e.source.ifBlank { "Google News" },
                        publishedAt = e.pubDate,
                        kind = NewsSourceKind.GOOGLE_NEWS,
                        sentiment = s.score,
                        matched = s.matched,
                        lang = if (persian) "fa" else "en"
                    )
                )
            }
        } catch (e: Exception) {
            failed.add(if (persian) "اخبار فارسی" else "اخبار جهانی")
        }
    }

    /** وزن‌دهی بر اساس تازگی و رسمی بودن منبع، و تبدیل به اثر روی امتیاز. */
    fun summarize(asset: Asset, items: List<NewsItem>, ok: List<String>, failed: List<String>): NewsDigest {
        val now = System.currentTimeMillis()
        if (items.isEmpty()) return NewsDigest.empty(asset.id, failed).copy(sourcesOk = ok)
        var wSum = 0.0
        var sSum = 0.0
        var blockReason: String? = null
        for (n in items) {
            val ageH = n.publishedAt?.let { t -> maxOf(0.0, (now - t) / 3_600_000.0) }
            val recency = if (ageH == null) 0.3 else 0.5.pow(ageH / HALF_LIFE_HOURS)
            val official = if (n.isOfficial) 1.6 else 1.0
            val w = recency * official
            wSum += w
            sSum += w * n.sentiment
            if (blockReason == null && ageH != null && ageH <= 24.0 && n.sentiment <= -0.6 &&
                (n.isOfficial || n.sentiment <= -0.75)
            ) {
                blockReason = "خبر منفی مهم در ۲۴ ساعت اخیر: «" + n.title.take(70) + "»"
            }
        }
        val score = if (wSum > 0) sSum / wSum else 0.0
        val confidence = min(1.0, wSum / 3.0)
        val marketFactor = when (asset.market) {
            MarketKind.FX -> 0.5 // جهت خبر برای جفت‌ارزها مبهم‌تر است
            else -> 1.0
        }
        var adj = (score * confidence * MAX_ADJ * marketFactor).roundToInt()
        if (abs(adj) < 1) adj = 0
        return NewsDigest(
            assetId = asset.id,
            items = items,
            score = score,
            confidence = confidence,
            adjustment = adj,
            blockBuy = blockReason != null,
            blockReason = blockReason,
            fetchedAt = now,
            sourcesOk = ok,
            sourcesFailed = failed
        )
    }
}
