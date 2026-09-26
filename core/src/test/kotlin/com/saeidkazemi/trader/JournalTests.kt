package com.saeidkazemi.trader

import com.saeidkazemi.trader.analysis.Performance
import com.saeidkazemi.trader.data.local.JsonStore
import com.saeidkazemi.trader.data.model.AppSettings
import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.JournalEntry
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.data.model.Metrics
import com.saeidkazemi.trader.data.model.ProFactor
import com.saeidkazemi.trader.data.model.Trade
import com.saeidkazemi.trader.journal.JournalExport
import com.saeidkazemi.trader.trading.PaperBroker
import com.saeidkazemi.trader.trading.TradeJournal
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalTests {

    private val t0 = 1_780_000_000_000L
    private val hour = 3_600_000L

    private fun closed(i: Int, pnl: Double, market: MarketKind = MarketKind.CRYPTO, reason: String = "فعال شدن حد ضرر") = JournalEntry(
        id = "e$i", assetId = "a$i", symbol = "S$i", name = "S$i", market = market, auto = true, mode = "PAPER",
        openedAt = t0 + i * hour, entryUsd = 100.0, entryNative = 100.0, nativeCurrency = "USD", usdIrr = 0.0,
        amountUsd = 1000.0, buyFeeUsd = 2.0, qty = 9.98, entryReason = "خرید خودکار",
        closedAt = t0 + i * hour + hour / 2, exitUsd = 100.0 + pnl / 10, exitReason = reason,
        pnlUsd = pnl, pnlPct = pnl / 10
    )

    @Test
    fun statsWinRateProfitFactorExpectancy() {
        // ۴ برد ۱۰۰ دلاری، ۶ باخت ۵۰ دلاری
        val list = (1..4).map { closed(it, 100.0) } + (5..10).map { closed(it, -50.0) }
        val s = Performance.stats(list)
        assertEquals(10, s.closed)
        assertEquals(0.4, s.winRate!!, 1e-9)
        assertEquals(100.0, s.totalPnlUsd, 1e-9)
        assertEquals(400.0 / 300.0, s.profitFactor!!, 1e-9)
        assertEquals(10.0, s.expectancyUsd!!, 1e-9)
        assertEquals(2.0, s.payoff!!, 1e-9)
        assertEquals(1.0 / 3.0, s.breakEvenWinRate!!, 1e-9)
        assertEquals(6, s.maxConsecLosses)
        assertEquals(300.0, s.maxDrawdownUsd, 1e-9)
        // ردیف باز در آمار حساب نمی‌شود
        val open = closed(99, 0.0).copy(closedAt = null, pnlUsd = null, exitReason = null)
        assertEquals(10, Performance.stats(list + open).closed)
        assertNull(Performance.stats(emptyList()).winRate)
    }

    @Test
    fun guardNeedsLowWinRateAndNegativeTotal() {
        val settings = AppSettings(minWinRatePct = 40.0, guardWindow = 10)
        // نرخ برد ۳۰٪ ولی جمع مثبت (سودهای بزرگ) → محافظ فعال نمی‌شود
        val bigWins = (1..3).map { closed(it, 300.0) } + (4..10).map { closed(it, -50.0) }
        assertFalse(Performance.guard(bigWins, MarketKind.CRYPTO, settings).active)
        // نرخ برد ۳۰٪ و جمع منفی → محافظ فعال
        val bad = (1..3).map { closed(it, 50.0) } + (4..10).map { closed(it, -50.0) }
        val g = Performance.guard(bad, MarketKind.CRYPTO, settings)
        assertTrue(g.active)
        assertEquals(0.3, g.winRate!!, 1e-9)
        // بازار دیگر تحت تأثیر نیست
        assertFalse(Performance.guard(bad, MarketKind.IR_STOCK, settings).active)
        // نمونه کمتر از پنجره → فعال نمی‌شود
        assertFalse(Performance.guard(bad.take(5), MarketKind.CRYPTO, settings).active)
        // خاموش
        assertFalse(Performance.guard(bad, MarketKind.CRYPTO, settings.copy(winRateGuard = false)).active)
        // فقط آخرین ۱۰ معامله مهم است: ۱۰ برد جدیدتر، باخت‌های قدیمی را بی‌اثر می‌کند
        val recovered = bad + (11..20).map { closed(it, 20.0) }
        assertFalse(Performance.guard(recovered, MarketKind.CRYPTO, settings).active)
    }

    @Test
    fun exitCategories() {
        assertEquals("قفل سود", Performance.exitCategory("حفظ سود (قفل سود حداقل 10٪)"))
        assertEquals("حد ضرر متحرک", Performance.exitCategory("حد ضرر متحرک (قفل سود)"))
        assertEquals("حد ضرر", Performance.exitCategory("فعال شدن حد ضرر"))
        assertEquals("حد سود", Performance.exitCategory("فعال شدن حد سود"))
        assertEquals("ضعیف شدن سیگنال", Performance.exitCategory("ضعیف شدن سیگنال (امتیاز 40)"))
        assertEquals("فروش دستی", Performance.exitCategory("فروش دستی"))
        assertEquals("خبر منفی", Performance.exitCategory("خروج به‌خاطر خبر منفی مهم"))
    }

    @Test
    fun journalPersistsAndClosesAndExports() {
        val store = JsonStore(Files.createTempDirectory("jr").toFile())
        val j = TradeJournal(store)
        val e = closed(1, 0.0).copy(
            closedAt = null, pnlUsd = null, pnlPct = null, exitReason = null, exitUsd = null,
            score = 78, technicalScore = 70, newsAdj = 3, proAdj = 5, threshold = 70,
            reasons = listOf("روند صعودی", "RSI متعادل"),
            proFactors = listOf(ProFactor("ترس و طمع", "25 (ترس)", 3, "فرصت خرید")),
            metrics = Metrics(55.0, 0.1, 2.0, 5.0, 12.0, 3.2),
            newsLabel = "برآیند اخبار مثبت", newsHeadlines = listOf("[اخبار • مثبت] ETF approved"),
            dataSources = listOf("قیمت: CoinGecko"), stopUsd = 92.0, takeProfitUsd = 150.0, trailPct = 0.11,
            forecastExpPct = 2.5, forecastProbUp = 0.6
        )
        j.open(e)
        assertTrue(j.all().single().isOpen)
        assertTrue(j.close("a1") { it.copy(closedAt = t0 + 5 * hour, exitUsd = 110.0, exitReason = "فعال شدن حد سود", pnlUsd = 90.0, pnlPct = 9.0, peakUsd = 112.0, troughUsd = 97.0) })
        assertFalse(j.close("a1") { it })

        val j2 = TradeJournal(store)
        val r = j2.all().single()
        assertEquals(90.0, r.pnlUsd!!, 1e-9)
        assertEquals(12.0, r.maxGainPct!!, 1e-9)
        assertEquals(-3.0, r.maxDrawPct!!, 1e-9)
        assertEquals("ترس و طمع", r.proFactors!!.single().title)
        assertEquals(55.0, r.metrics!!.rsi!!, 1e-9)

        val titles = JournalExport.sections(r).map { it.title }
        for (t in listOf("ورود", "امتیاز و دلایل موتور", "تحلیل تخصصی", "اندیکاتورها", "اخبار", "پیش‌بینی لحظه خرید", "مدیریت ریسک", "منابع اطلاعات", "خروج", "نتیجه")) {
            assertTrue(t in titles, "missing section $t in $titles")
        }
        val report = Performance.report(j2.all(), AppSettings())
        val text = JournalExport.text(j2.all(), report)
        assertTrue(text.contains("نرخ برد") && text.contains("ETF approved") && text.contains("فعال شدن حد سود"))
        val csv = JournalExport.csv(j2.all()).trim().lines()
        assertEquals(2, csv.size)
        assertTrue(csv[1].contains("S1") && csv[1].contains("90.00"))

        j2.clear()
        assertTrue(TradeJournal(store).all().isEmpty())
    }

    @Test
    fun backfillPairsOldTrades() {
        val j = TradeJournal(null)
        fun tr(id: String, ts: Long, asset: String, side: String, value: Double, fee: Double, reason: String) =
            Trade(id, ts, asset, asset.uppercase(), side, 1.0, value, value, fee, reason, "PAPER")
        val trades = listOf(
            tr("s1", t0 + 3, "btc", "SELL", 1100.0, 2.2, "فعال شدن حد سود"),
            tr("b2", t0 + 2, "fx:EUR", "BUY", 500.0, 1.0, "خرید دستی"),
            tr("b1", t0 + 1, "btc", "BUY", 1000.0, 2.0, "خرید خودکار (امتیاز 80)")
        )
        j.backfill(trades) { if (it.startsWith("fx:")) MarketKind.FX else MarketKind.CRYPTO }
        val all = j.all()
        assertEquals(2, all.size)
        val btc = all.first { it.assetId == "btc" }
        assertEquals(1100.0 - 2.2 - 1000.0, btc.pnlUsd!!, 1e-9)
        assertTrue(btc.auto && btc.backfilled)
        val eur = all.first { it.assetId == "fx:EUR" }
        assertTrue(eur.isOpen)
        assertEquals(MarketKind.FX, eur.market)
        // بار دوم کاری نمی‌کند
        j.backfill(trades) { MarketKind.CRYPTO }
        assertEquals(2, j.all().size)
    }

    @Test
    fun realizedPnlIncludesBuyFee() {
        val store = JsonStore(Files.createTempDirectory("fee").toFile())
        val broker = PaperBroker(store)
        broker.reset(1000.0, mapOf("CRYPTO" to 100.0))
        val a = Asset("btc", "BTC", "Bitcoin", MarketKind.CRYPTO, "USD", 100.0, 0.0, t0)
        assertNotNull(broker.buy(a, 100.0, 1000.0, 0.01, 90.0, 150.0, "t"))
        assertEquals(1000.0, broker.account().positions.single().cost(), 1e-9)
        assertNotNull(broker.sell("btc", 100.0, 0.01, "t"))
        // قیمت ثابت ماند؛ زیان واقعی = هر دو کارمزد = ۱۰ + ۹٫۹
        assertEquals(-19.9, broker.account().realizedPnlUsd, 1e-9)
        assertEquals(1000.0 - 19.9, broker.account().cashUsd, 1e-9)
    }
}
