package com.saeidkazemi.trader.data.remote

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * داده‌های تخصصی بازار ارز دیجیتال که در تحلیل کندلی عادی نیست:
 *
 * - **شاخص ترس و طمع** (alternative.me): احساسات کل بازار؛ ترس شدید معمولاً فرصت خرید و طمع شدید
 *   معمولاً نزدیک سقف است (سیگنال خلاف جهت جمعیت).
 * - **عدم تعادل دفتر سفارش** نوبیتکس: نسبت حجم سفارش‌های خرید به فروش نزدیک قیمت فعلی
 *   (فشار واقعی خریداران و فروشندگان در همین لحظه).
 */
class InsightSource {

    data class FearGreed(val value: Int, val label: String, val ts: Long)

    @Volatile
    private var fng: FearGreed? = null

    @Volatile
    private var fngFetched = 0L

    private class Book(val bidShare: Double?, val halfSpread: Double?, val ts: Long)

    private val books = ConcurrentHashMap<String, Book>()

    private suspend fun getJson(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        Http.client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            body
        }
    }

    /** شاخص ترس و طمع (کش ۱ ساعته؛ شاخص روزی یک بار به‌روز می‌شود). */
    suspend fun fearGreed(): FearGreed? {
        val now = System.currentTimeMillis()
        if (now - fngFetched < 3_600_000L && fng != null) return fng
        if (now - fngFetched < 10 * 60_000L) return fng
        fngFetched = now
        return try {
            parseFearGreed(getJson("https://api.alternative.me/fng/?limit=1"))?.also { fng = it }
        } catch (_: Exception) {
            fng
        }
    }

    fun cachedFearGreed(): FearGreed? = fng

    /**
     * سهم خریداران از حجم سفارش‌های ۲٪ اطراف قیمت (۰ تا ۱؛ ۰٫۵ = متعادل). کش ۳ دقیقه.
     * اول بازار تتری و اگر نبود بازار تومانی نوبیتکس.
     */
    suspend fun bidShare(symbol: String): Double? {
        val sym = symbol.uppercase().filter { it.isLetterOrDigit() }
        if (sym.isEmpty() || sym == "USDT") return null
        val now = System.currentTimeMillis()
        books[sym]?.let { if (now - it.ts < 3 * 60_000L) return it.bidShare }
        var share: Double? = null
        var half: Double? = null
        // اول بازار تومانی (سفارش واقعی ربات همان‌جا ثبت می‌شود و اسپرد آن هزینه واقعی است)، بعد تتری
        for (quote in listOf("IRT", "USDT")) {
            try {
                val json = getJson("https://apiv2.nobitex.ir/v3/orderbook/$sym$quote")
                share = parseBidShare(json)
                half = parseHalfSpread(json)
            } catch (_: Exception) {
                share = null
                half = null
            }
            if (share != null) break
        }
        books[sym] = Book(share, half, now)
        return share
    }

    /** نصف اسپرد آخرین دفتر سفارش دریافت‌شده (کسر؛ مثلاً ۰٫۰۰۱ = ۰٫۱٪)، اگر تازه‌تر از ۳۰ دقیقه باشد. */
    fun cachedHalfSpread(symbol: String): Double? {
        val sym = symbol.uppercase().filter { it.isLetterOrDigit() }
        val b = books[sym] ?: return null
        return if (System.currentTimeMillis() - b.ts < 30 * 60_000L) b.halfSpread else null
    }

    companion object {
        fun parseFearGreed(json: String): FearGreed? {
            val root = JsonParser.parseString(json)
            if (!root.isJsonObject) return null
            val first = root.asJsonObject.getAsJsonArray("data")?.firstOrNull()?.asJsonObject ?: return null
            val v = first.get("value")?.asString?.toIntOrNull() ?: return null
            val label = first.get("value_classification")?.asString ?: ""
            val ts = (first.get("timestamp")?.asString?.toLongOrNull() ?: 0L) * 1000
            return FearGreed(v.coerceIn(0, 100), label, ts)
        }

        /** نصف فاصله بهترین قیمت فروش و خرید نسبت به قیمت میانی. */
        fun parseHalfSpread(json: String): Double? {
            val root = JsonParser.parseString(json)
            if (!root.isJsonObject) return null
            val o = root.asJsonObject
            if (o.get("status")?.asString != "ok") return null
            fun best(key: String, max: Boolean): Double? =
                o.getAsJsonArray(key)?.mapNotNull { el ->
                    if (!el.isJsonArray || el.asJsonArray.size() < 2) null
                    else el.asJsonArray[0].asString.toDoubleOrNull()?.takeIf { it > 0 }
                }?.let { if (max) it.maxOrNull() else it.minOrNull() }
            return com.saeidkazemi.trader.trading.Fees.halfSpreadOf(best("bids", true), best("asks", false))
        }

        /** سهم حجم خرید در محدوده ±band از قیمت میانی. */
        fun parseBidShare(json: String, band: Double = 0.02): Double? {
            val root = JsonParser.parseString(json)
            if (!root.isJsonObject) return null
            val o = root.asJsonObject
            if (o.get("status")?.asString != "ok") return null
            fun levels(key: String): List<Pair<Double, Double>> =
                o.getAsJsonArray(key)?.mapNotNull { el ->
                    val a = if (el.isJsonArray) el.asJsonArray else return@mapNotNull null
                    if (a.size() < 2) return@mapNotNull null
                    val p = a[0].asString.toDoubleOrNull() ?: return@mapNotNull null
                    val q = a[1].asString.toDoubleOrNull() ?: return@mapNotNull null
                    if (p > 0 && q > 0) p to q else null
                }.orEmpty()
            val bids = levels("bids")
            val asks = levels("asks")
            if (bids.isEmpty() || asks.isEmpty()) return null
            val mid = (bids.maxOf { it.first } + asks.minOf { it.first }) / 2
            val bidVal = bids.filter { it.first >= mid * (1 - band) }.sumOf { it.first * it.second }
            val askVal = asks.filter { it.first <= mid * (1 + band) }.sumOf { it.first * it.second }
            if (bidVal + askVal <= 0) return null
            return bidVal / (bidVal + askVal)
        }
    }
}
