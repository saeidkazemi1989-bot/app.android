package com.saeidkazemi.trader.analysis

import com.saeidkazemi.trader.data.model.Action
import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.Metrics
import com.saeidkazemi.trader.data.model.PricePoint
import com.saeidkazemi.trader.data.model.AppSettings
import com.saeidkazemi.trader.data.model.Signal
import com.saeidkazemi.trader.news.NewsDigest
import com.saeidkazemi.trader.util.Format
import kotlin.math.roundToInt

/**
 * موتور امتیازدهی ترکیبی (۰ تا ۱۰۰):
 * روند (۳۰) + موقعیت نسبت به میانگین ۵۰روزه (۱۰) + مومنتوم هفتگی (۲۵) + مکدی (۲۰) + آر‌اس‌آی (۱۵)
 * و جریمه نوسان زیاد (تا ۱۲-).
 *
 * سپس «اثر خبری» (از ۱۲- تا ۱۲+) بر اساس اخبار و اطلاعیه‌های کدال اضافه می‌شود و اگر خبر منفی
 * مهم و تازه‌ای وجود داشته باشد، خرید جدید آن دارایی متوقف می‌شود.
 */
class StrategyEngine {

    companion object {
        const val MIN_HISTORY = 40
    }

    fun analyze(
        asset: Asset,
        history: List<PricePoint>,
        settings: AppSettings,
        news: NewsDigest? = null
    ): Signal? {
        if (history.size < MIN_HISTORY) return null
        val v = history.map { it.price }.toDoubleArray()
        val price = v[v.size - 1]
        if (!price.isFinite() || price <= 0) return null

        val rsi = Indicators.rsi(v, 14)
        val macd = Indicators.macd(v)
        val trend = Indicators.trendPct(v)
        val mom7 = Indicators.momentumPct(v, 7)
        val mom30 = Indicators.momentumPct(v, 30)
        val vol = Indicators.dailyVolatilityPct(v, 30)
        val sma50 = Indicators.sma(v, 50)
        val vsSma50 = if (sma50 != null && sma50 > 0) (price / sma50 - 1.0) * 100.0 else null

        var score = 0.0
        val reasons = mutableListOf<String>()

        if (trend != null) {
            if (trend > 0) {
                score += minOf(30.0, 18.0 + trend * 4.0)
                reasons.add("روند صعودی: میانگین کوتاه‌مدت بالای بلندمدت")
            } else {
                score += maxOf(0.0, 8.0 + trend * 4.0)
                if (trend < -1.0) reasons.add("روند نزولی: میانگین کوتاه‌مدت زیر بلندمدت")
            }
        }

        if (vsSma50 != null) {
            if (vsSma50 > 0) score += minOf(10.0, vsSma50 * 1.2)
            else score += maxOf(0.0, 5.0 + vsSma50 * 0.8)
        }

        if (mom7 != null) {
            val clamped = maxOf(-12.0, minOf(12.0, mom7))
            score += 12.5 + clamped * (12.5 / 12.0)
            if (mom7 >= 3.0) reasons.add("مومنتوم مثبت هفتگی: " + Format.pct(mom7))
        }

        if (macd != null) {
            val rel = macd.hist / price * 100.0
            if (macd.hist > 0) {
                score += minOf(20.0, 12.0 + rel * 40.0)
                if (rel > 0.05) reasons.add("هیستوگرام مکدی مثبت و رو به بالا")
            } else {
                score += maxOf(0.0, 6.0 + rel * 40.0)
            }
        }

        if (rsi != null) {
            when {
                rsi in 45.0..65.0 -> {
                    score += 15.0
                    reasons.add("آر‌اس‌آی در محدوده سالم (${Format.num(rsi, 0)})")
                }

                rsi in 30.0..45.0 -> {
                    score += 11.0
                    reasons.add("آر‌اس‌آی نزدیک اشباع فروش؛ پتانسیل برگشت")
                }

                rsi < 30.0 -> {
                    score += 6.0
                    reasons.add("آر‌اس‌آی در اشباع فروش شدید")
                }

                rsi in 65.0..75.0 -> score += 8.0

                else -> {
                    score += 1.0
                    reasons.add("آر‌اس‌آی در اشباع خرید؛ ورود پرریسک")
                }
            }
        }

        if (vol != null && vol > 5.0) {
            score -= minOf(12.0, (vol - 5.0) * 1.2)
            if (vol > 8.0) reasons.add("نوسان روزانه بالا (حدود " + Format.num(vol, 1) + "٪)")
        }

        val technical = maxOf(0, minOf(100, score.roundToInt()))
        val useNews = settings.newsEnabled && news != null
        val newsAdj = if (useNews && news != null) news.adjustment else 0
        val blocked = useNews && news != null && news.blockBuy
        val total = maxOf(0, minOf(100, technical + newsAdj))
        if (useNews && news != null) {
            if (news.available) {
                val sign = if (newsAdj > 0) "+" else ""
                reasons.add(news.label + " (" + news.items.size + " خبر، اثر " + sign + newsAdj + ")")
                val top = news.items
                    .filter { kotlin.math.abs(it.sentiment) >= 0.3 }
                    .maxByOrNull { kotlin.math.abs(it.sentiment) }
                if (top != null) {
                    reasons.add((if (top.sentiment > 0) "خبر مثبت: «" else "خبر منفی: «") + top.title.take(80) + "»")
                }
            }
            if (blocked) reasons.add(news.blockReason ?: "خبر منفی مهم؛ خرید متوقف شد")
        }
        val action = when {
            blocked && total >= settings.buyThreshold -> Action.HOLD
            total >= settings.buyThreshold -> Action.BUY
            total <= 35 -> Action.SELL
            else -> Action.HOLD
        }
        if (asset.isSimulated) reasons.add("داده شبیه‌سازی‌شده")
        if (reasons.isEmpty()) reasons.add("امتیاز ترکیبی")

        return Signal(
            assetId = asset.id,
            symbol = asset.symbol,
            name = asset.name,
            market = asset.market,
            action = action,
            score = total,
            reasons = reasons,
            metrics = Metrics(
                rsi = rsi,
                macdHist = macd?.hist,
                trendPct = trend,
                momentum7 = mom7,
                momentum30 = mom30,
                volatility = vol
            ),
            isSimulated = asset.isSimulated,
            createdAt = System.currentTimeMillis(),
            technicalScore = technical,
            newsAdj = newsAdj,
            newsCount = news?.items?.size ?: 0,
            newsLabel = if (useNews && news != null) news.label else null,
            newsBlocked = blocked
        )
    }
}
