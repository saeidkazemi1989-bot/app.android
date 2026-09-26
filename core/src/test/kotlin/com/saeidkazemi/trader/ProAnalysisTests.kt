package com.saeidkazemi.trader

import com.saeidkazemi.trader.analysis.ProAnalysis
import com.saeidkazemi.trader.analysis.RiskManager
import com.saeidkazemi.trader.analysis.StrategyEngine
import com.saeidkazemi.trader.data.local.JsonStore
import com.saeidkazemi.trader.data.model.Action
import com.saeidkazemi.trader.data.model.AppSettings
import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.data.model.PricePoint
import com.saeidkazemi.trader.data.remote.InsightSource
import com.saeidkazemi.trader.data.remote.IranStockSource
import com.saeidkazemi.trader.trading.PaperBroker
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProAnalysisTests {

    private fun res(name: String): String =
        javaClass.classLoader.getResource(name)!!.readText(Charsets.UTF_8)

    // ---------------------------------------------------------------- داده حقیقی/حقوقی TSETMC

    @Test
    fun parsesClientTypeAll() {
        val flows = IranStockSource.parseClientTypeAll(res("clienttype_all_sample.json"), 20260926)
        assertTrue(flows.size >= 5)
        val f = flows["40473515538481093"]!!
        assertEquals(274738.0, f.buyIVol)
        assertEquals(1197137.0, f.sellIVol)
        assertEquals(26.0, f.buyICount)
        // سرانه خرید 10567 ÷ سرانه فروش 41280 ≈ 0.256
        assertEquals(0.256, f.buyerPower!!, 0.01)
        assertTrue(f.realNetShare!! < 0)
    }

    @Test
    fun parsesClientTypeHistory() {
        val h = IranStockSource.parseClientTypeHistory(res("clienttype_history_sample.json"))
        assertEquals(10, h.size)
        assertEquals(20220223, h.first().date)
        assertTrue(h.zipWithNext().all { (a, b) -> a.date > b.date })
        val d = h.first()
        assertEquals(578998427750.0, d.buyIValue)
        assertEquals(697532900950.0, d.sellIValue)
        assertEquals(578998427750.0 - 697532900950.0, d.realNetValue(1.0))
        assertNotNull(d.buyerPower)
    }

    @Test
    fun marketStatsFromWatch() {
        val quotes = IranStockSource.parseMarketWatch(res("marketwatch_sample.json")).filter { it.isStock }
        assertTrue(quotes.all { it.sector.isNotEmpty() })
        val flows = IranStockSource.parseClientTypeAll(res("clienttype_all_sample.json"))
        val st = IranStockSource.marketStats(quotes, flows)
        assertNotNull(st.breadth)
        assertTrue(st.totalValueIrr > 0)
    }

    // ---------------------------------------------------------------- تحلیل تخصصی بورس

    private fun day(date: Int, buyVol: Double, sellVol: Double, buyCnt: Double, sellCnt: Double, price: Double = 1000.0) =
        ProAnalysis.FlowDay(date, buyVol, sellVol, buyNVol = 1_000_000.0, buyICount = buyCnt, sellICount = sellCnt,
            netRealValueIrr = (buyVol - sellVol) * price)

    private fun flatHistory(n: Int = 60, lastVolMult: Double = 1.0, lastChangePct: Double = 0.0): List<PricePoint> {
        val now = System.currentTimeMillis()
        val out = ArrayList<PricePoint>()
        for (i in 0 until n) {
            val p = 1000.0 * (1 + 0.002 * kotlin.math.sin(i / 2.0))
            out.add(PricePoint(now - (n - 1 - i) * 86_400_000L, p, 1_000_000.0 * (1 + 0.1 * kotlin.math.cos(i.toDouble()))))
        }
        val prev = out[n - 2].price
        out[n - 1] = PricePoint(out[n - 1].t, prev * (1 + lastChangePct / 100), 1_000_000.0 * lastVolMult)
        return out
    }

    @Test
    fun smartMoneyInflowIsPositive() {
        val today = day(20260926, buyVol = 5_000_000.0, sellVol = 2_000_000.0, buyCnt = 100.0, sellCnt = 400.0)
        val hist = (1..5).map { day(20260926 - it, 3_000_000.0, 2_000_000.0, 100.0, 200.0) }
        val r = ProAnalysis.iran(
            flatHistory(lastVolMult = 3.2, lastChangePct = 3.0),
            ProAnalysis.IranInputs(today = today, history = hist, pe = 4.0, eps = 250.0, sectorPe = 8.0, breadth = 0.7,
                marketRealNetIrr = 1e12, marketValueIrr = 5e13)
        )
        assertTrue(r.adj >= 15, "adj=${r.adj} ${r.reasons}")
        assertFalse(r.blockBuy)
        assertTrue(r.factors.any { it.title.contains("قدرت خریدار") && it.impact > 0 })
        assertTrue(r.factors.any { it.title.contains("حجم") && it.impact > 0 })
        assertTrue(r.adj <= ProAnalysis.MAX_ADJ)
    }

    @Test
    fun persistentOutflowBlocksBuy() {
        val today = day(20260926, buyVol = 1_000_000.0, sellVol = 3_000_000.0, buyCnt = 400.0, sellCnt = 100.0)
        val hist = (1..5).map { day(20260926 - it, 1_000_000.0, 2_500_000.0, 300.0, 100.0) }
        val r = ProAnalysis.iran(flatHistory(), ProAnalysis.IranInputs(today = today, history = hist, eps = -50.0))
        assertTrue(r.adj <= -12, "adj=${r.adj}")
        assertTrue(r.blockBuy)
        assertTrue(r.blockReason!!.contains("خروج"))
    }

    @Test
    fun marketCrashBlocksStocks() {
        val r = ProAnalysis.iran(flatHistory(), ProAnalysis.IranInputs(breadth = 0.1, marketRealNetIrr = -5e11, marketValueIrr = 4e13))
        assertTrue(r.blockBuy)
        assertTrue(r.adj < 0)
    }

    // ---------------------------------------------------------------- تحلیل تخصصی ارز دیجیتال

    private fun trend(n: Int, dailyPct: Double, start: Double = 100.0): List<PricePoint> {
        val now = System.currentTimeMillis()
        return (0 until n).map { i ->
            PricePoint(now - (n - 1 - i) * 86_400_000L, start * Math.pow(1 + dailyPct / 100, i.toDouble()), 1000.0)
        }
    }

    @Test
    fun cryptoFearAndStrongBtcArePositive() {
        val r = ProAnalysis.crypto(
            trend(120, 0.8),
            ProAnalysis.CryptoInputs(fearGreed = 15, fearGreedLabel = "ترس شدید", btcHistory = trend(120, 0.3), bidShare = 0.72)
        )
        assertTrue(r.adj >= 8, "adj=${r.adj} ${r.reasons}")
        assertFalse(r.blockBuy)
        assertTrue(r.factors.any { it.title.contains("قدرت نسبی") && it.impact > 0 })
    }

    @Test
    fun btcDowntrendBlocksAltcoins() {
        val r = ProAnalysis.crypto(
            trend(120, -0.2),
            ProAnalysis.CryptoInputs(fearGreed = 88, btcHistory = trend(120, -0.6))
        )
        assertTrue(r.blockBuy)
        assertTrue(r.adj < 0)
        // خود بیت‌کوین با فیلتر خودش مسدود نمی‌شود
        val btc = ProAnalysis.crypto(trend(120, -0.6), ProAnalysis.CryptoInputs(isBtc = true, btcHistory = trend(120, -0.6)))
        assertFalse(btc.blockBuy)
    }

    @Test
    fun volumeSpikeDirection() {
        val up = ProAnalysis.volumeFactor(flatHistory(lastVolMult = 3.5, lastChangePct = 4.0))
        assertNotNull(up)
        assertTrue(up.impact > 0)
        val down = ProAnalysis.volumeFactor(flatHistory(lastVolMult = 3.5, lastChangePct = -4.0))
        assertTrue(down!!.impact < 0)
        assertNull(ProAnalysis.volumeFactor(flatHistory(lastVolMult = 1.1, lastChangePct = 1.0)))
    }

    @Test
    fun parsesFearGreedAndOrderBook() {
        val fg = InsightSource.parseFearGreed(
            """{"name":"Fear and Greed Index","data":[{"value":"74","value_classification":"Greed","timestamp":"1790380800"}],"metadata":{"error":null}}"""
        )!!
        assertEquals(74, fg.value)
        assertEquals("Greed", fg.label)
        val share = InsightSource.parseBidShare(
            """{"status":"ok","lastUpdate":1,"lastTradePrice":"100","bids":[["99.9","5"],["99","5"],["50","999"]],"asks":[["100.1","1"],["101","1"],["200","999"]]}"""
        )!!
        // سفارش‌های دور از قیمت (۵۰ و ۲۰۰) حساب نمی‌شوند
        assertTrue(share > 0.8 && share < 0.9, "share=$share")
    }

    @Test
    fun proBlockTurnsBuyIntoHold() {
        val a = Asset(
            id = "solana", symbol = "SOL", name = "Solana", market = MarketKind.CRYPTO,
            baseCurrency = "USD", price = 200.0, changePct24h = 1.0, updatedAt = System.currentTimeMillis()
        )
        val hist = trend(90, 0.9)
        val settings = AppSettings(buyThreshold = 40)
        val engine = StrategyEngine()
        val base = engine.analyze(a, hist, settings)!!
        assertEquals(Action.BUY, base.action)
        val blocked = ProAnalysis.Result(-5, listOf(com.saeidkazemi.trader.data.model.ProFactor("روند بیت‌کوین", "زیر", -5)), true, "بیت‌کوین نزولی")
        val sig = engine.analyze(a, hist, settings, null, blocked)!!
        assertEquals(Action.HOLD, sig.action)
        assertTrue(sig.proBlocked)
        assertEquals(base.score - 5, sig.score)
        // خاموش کردن تحلیل تخصصی
        val off = engine.analyze(a, hist, settings.copy(proAnalysis = false), null, blocked)!!
        assertEquals(Action.BUY, off.action)
    }

    // ---------------------------------------------------------------- سرمایه جداگانه هر بازار

    private fun asset(id: String, m: MarketKind, price: Double) = Asset(
        id = id, symbol = id.uppercase(), name = id, market = m, baseCurrency = "USD",
        price = price, changePct24h = 0.0, updatedAt = System.currentTimeMillis()
    )

    @Test
    fun marketSleevesAreIndependent() {
        val store = JsonStore(Files.createTempDirectory("sleeves").toFile())
        val broker = PaperBroker(store)
        broker.reset(1000.0, mapOf("CRYPTO" to 50.0, "IR_STOCK" to 40.0, "FX" to 10.0))
        assertEquals(500.0, broker.cashOf(MarketKind.CRYPTO), 1e-9)
        assertEquals(400.0, broker.cashOf(MarketKind.IR_STOCK), 1e-9)
        assertEquals(100.0, broker.cashOf(MarketKind.FX), 1e-9)

        val btc = asset("btc", MarketKind.CRYPTO, 100.0)
        // بیشتر از سهم ارز دیجیتال → رد می‌شود حتی اگر کل نقد کافی باشد
        assertNull(broker.buy(btc, 100.0, 600.0, 0.0, 90.0, 150.0, "t"))
        assertNotNull(broker.buy(btc, 100.0, 400.0, 0.0, 90.0, 150.0, "t", trailPct = 0.1))
        assertEquals(100.0, broker.cashOf(MarketKind.CRYPTO), 1e-9)
        assertEquals(400.0, broker.cashOf(MarketKind.IR_STOCK), 1e-9)
        assertEquals(600.0, broker.account().cashUsd, 1e-9)

        // حد ضرر متحرک: قیمت به ۱۲۰ می‌رسد → حد ضرر ۱۰۸
        val moved = broker.trail(mapOf("btc" to 120.0))
        assertEquals(1, moved.size)
        assertEquals(108.0, broker.account().positions.first().stopLossUsd, 1e-9)
        // برگشت قیمت، حد ضرر را پایین نمی‌آورد
        broker.trail(mapOf("btc" to 110.0))
        assertEquals(108.0, broker.account().positions.first().stopLossUsd, 1e-9)

        // فروش با سود → پول فقط به صندوق ارز دیجیتال برمی‌گردد
        assertNotNull(broker.sell("btc", 110.0, 0.0, "t"))
        assertEquals(540.0, broker.cashOf(MarketKind.CRYPTO), 1e-9)
        assertEquals(400.0, broker.cashOf(MarketKind.IR_STOCK), 1e-9)
        assertEquals(40.0, broker.account().realizedByMarket["CRYPTO"]!!, 1e-9)
        assertEquals(1040.0, broker.account().cashUsd, 1e-9)
    }

    @Test
    fun legacyAccountIsSplitOnLoad() {
        val dir = Files.createTempDirectory("legacy").toFile()
        val store = JsonStore(dir)
        store.saveSettings(AppSettings(allocations = mapOf("CRYPTO" to 60.0, "IR_STOCK" to 40.0, "FX" to 0.0)))
        store.saveAccount(com.saeidkazemi.trader.data.model.AccountState(cashUsd = 800.0, initialCapitalUsd = 1000.0))
        val broker = PaperBroker(store)
        assertEquals(480.0, broker.cashOf(MarketKind.CRYPTO), 1e-9)
        assertEquals(320.0, broker.cashOf(MarketKind.IR_STOCK), 1e-9)
        assertEquals(0.0, broker.cashOf(MarketKind.FX), 1e-9)
    }

    @Test
    fun volatilityStopsPerMarket() {
        val rm = RiskManager()
        val crypto = rm.plan("MED", MarketKind.CRYPTO)
        assertEquals(0.06, crypto.stopFor(1.0), 1e-9)      // حداقل
        assertEquals(0.10, crypto.stopFor(4.0), 1e-9)      // ۲٫۵ × ۴٪
        assertEquals(0.14, crypto.stopFor(12.0), 1e-9)     // حداکثر
        assertEquals(crypto.stopPct, crypto.stopFor(null), 1e-9)
        val fx = rm.plan("MED", MarketKind.FX)
        assertTrue(fx.maxStopPct < crypto.minStopPct)
        assertTrue(rm.plan("MED", MarketKind.IR_STOCK).maxPositions > crypto.maxPositions)
    }
}
