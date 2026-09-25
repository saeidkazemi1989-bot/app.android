package com.saeidkazemi.trader

import com.saeidkazemi.trader.analysis.StrategyEngine
import com.saeidkazemi.trader.data.model.Action
import com.saeidkazemi.trader.data.model.AppSettings
import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.data.model.PricePoint
import com.saeidkazemi.trader.news.NewsItem
import com.saeidkazemi.trader.news.NewsService
import com.saeidkazemi.trader.news.NewsSourceKind
import com.saeidkazemi.trader.news.RssParser
import com.saeidkazemi.trader.news.Sentiment
import com.saeidkazemi.trader.util.Jalali
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoreTests {

    @Test
    fun jalaliConversion() {
        assertEquals(LocalDate.of(2024, 9, 22), Jalali.toGregorian(1403, 7, 1))
        assertEquals(LocalDate.of(2026, 9, 24), Jalali.toGregorian(1405, 7, 2))
        assertEquals(listOf(1405, 7, 3), Jalali.fromGregorian(LocalDate.of(2026, 9, 25)).toList())
        val ts = Jalali.parseToEpochMillis("۱۴۰۳/۰۷/۰۱ ۱۸:۲۳:۱۲")
        assertTrue(ts != null && ts > 0)
    }

    @Test
    fun persianSentiment() {
        assertTrue(Sentiment.analyze("افزایش سرمایه از محل سود انباشته").score > 0.3)
        assertTrue(Sentiment.analyze("شناسایی زیان و کاهش فروش").score < -0.3)
        assertTrue(Sentiment.analyze("کاهش زیان شرکت").score > 0)
        assertTrue(Sentiment.analyze("فروش ۵۱ درصد رشد داشته است").score > 0)
        assertTrue(Sentiment.analyze("توقف نماد").score < 0)
    }

    @Test
    fun englishSentiment() {
        assertTrue(Sentiment.analyze("Bitcoin surges to record high as ETF inflows jump").score > 0.5)
        assertTrue(Sentiment.analyze("Exchange hacked, token plunges 30%").score < -0.5)
        assertTrue(Sentiment.analyze("SEC has not approved the fund").score < 0.3)
    }

    @Test
    fun rssParsing() {
        val xml = """
            <rss><channel>
            <item><title>Bitcoin jumps above ${'$'}70K - CoinDesk</title>
            <link>https://news.google.com/rss/articles/abc</link>
            <pubDate>Mon, 23 Sep 2024 10:00:00 GMT</pubDate>
            <description>&lt;a href="x"&gt;Bitcoin jumps&lt;/a&gt;</description>
            <source url="https://www.coindesk.com">CoinDesk</source></item>
            </channel></rss>
        """.trimIndent()
        val items = RssParser.parse(xml)
        assertEquals(1, items.size)
        assertEquals("CoinDesk", items[0].source)
        assertTrue(items[0].pubDate != null)
        assertEquals("Bitcoin jumps", items[0].description)
    }

    private fun asset() = Asset(
        id = "bitcoin", symbol = "BTC", name = "Bitcoin", market = MarketKind.CRYPTO,
        baseCurrency = "USD", price = 200.0, changePct24h = 1.0, updatedAt = System.currentTimeMillis()
    )

    private fun uptrend(): List<PricePoint> {
        val now = System.currentTimeMillis()
        return (0 until 90).map { i ->
            val p = 100.0 * Math.pow(1.008, i.toDouble()) * (1 + 0.01 * Math.sin(i / 3.0))
            PricePoint(now - (89 - i) * 86_400_000L, p)
        }
    }

    @Test
    fun newsAffectsScore() {
        val a = asset()
        val hist = uptrend()
        val settings = AppSettings()
        val engine = StrategyEngine()
        val base = engine.analyze(a, hist, settings, null)!!
        val now = System.currentTimeMillis()
        val bad = (1..5).map {
            NewsItem(
                title = "Exchange hacked, Bitcoin plunges", summary = "", url = "u$it", publisher = "x",
                publishedAt = now - it * 3_600_000L, kind = NewsSourceKind.GOOGLE_NEWS,
                sentiment = -0.9, matched = listOf("hacked"), lang = "en"
            )
        }
        val digest = NewsService().summarize(a, bad, listOf("اخبار جهانی"), emptyList())
        assertTrue(digest.adjustment < 0)
        assertTrue(digest.blockBuy)
        val withNews = engine.analyze(a, hist, settings, digest)!!
        assertTrue(withNews.score < base.score)
        assertTrue(withNews.action != Action.BUY)
        assertEquals(base.technicalScore, withNews.technicalScore)
    }
}
