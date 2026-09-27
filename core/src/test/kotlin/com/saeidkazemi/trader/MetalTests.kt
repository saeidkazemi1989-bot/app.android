package com.saeidkazemi.trader

import com.saeidkazemi.trader.analysis.RiskManager
import com.saeidkazemi.trader.data.local.JsonStore
import com.saeidkazemi.trader.data.model.AppSettings
import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.data.model.PricePoint
import com.saeidkazemi.trader.data.remote.IranStockSource
import com.saeidkazemi.trader.data.remote.NobitexRialSource
import com.saeidkazemi.trader.trading.Fees
import com.saeidkazemi.trader.trading.PaperBroker
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetalTests {

    @Test
    fun parsesNobitexStats() {
        val body = """{"status":"ok","stats":{
            "paxg-rls":{"isClosed":false,"bestSell":"10050000000","bestBuy":"10030000000","volumeDst":"123456789000","latest":"10038989880","dayChange":"0.85"},
            "usdt-rls":{"isClosed":false,"bestSell":"2356000","bestBuy":"2355000","latest":"2355500","dayChange":"-0.12","volumeDst":"999"}}}"""
        val st = NobitexRialSource.parseStats(body)
        val p = st["paxg-rls"]!!
        assertEquals(10_038_989_880.0, p.latest, 1e-3)
        assertEquals(10_030_000_000.0, p.bestBuy!!, 1e-3)
        assertEquals(0.85, p.dayChange!!, 1e-9)
        assertFalse(p.closed)
        assertEquals(2_355_500.0, st["usdt-rls"]!!.latest, 1e-9)
        assertTrue(NobitexRialSource.parseStats("not json").isEmpty())
    }

    @Test
    fun udfTomanIsScaledToRial() {
        val body = """{"s":"ok","t":[1790300000,1790386400],"c":[1000000000,1003898988],"v":[1.5,2.0]}"""
        val raw = NobitexRialSource.parseUdf(body)
        assertEquals(2, raw.size)
        // تاریخچه UDF تومانی است؛ ×۱۰ → ریال و هم‌واحد با قیمت زنده
        val rial = NobitexRialSource.normalizeScale(raw.map { it.copy(price = it.price * 10) }, 10_038_989_880.0)
        assertEquals(10_038_989_880.0, rial.last().price, 1.0)
        // اگر واحد منبع عوض شود (مثلاً ریالی شود)، محافظ دوباره اصلاح می‌کند
        val double = NobitexRialSource.normalizeScale(raw.map { it.copy(price = it.price * 100) }, 10_038_989_880.0)
        assertEquals(10_038_989_880.0, double.last().price, 1.0)
        val same = listOf(PricePoint(1, 100.0), PricePoint(2, 102.0))
        assertEquals(same, NobitexRialSource.normalizeScale(same, 103.0))
        assertTrue(NobitexRialSource.parseUdf("""{"s":"no_data"}""").isEmpty())
    }

    private fun fund(isin: String, symbol: String, name: String) = IranStockSource.Quote(
        insCode = "9", isin = isin, symbol = symbol, name = name,
        last = 300_000.0, close = 300_000.0, yesterday = 298_000.0, maxAllowed = 0.0, minAllowed = 0.0,
        valueIrr = 5e12, volume = 1e7, trades = 5000.0, eps = null, pe = null,
        bidPrice = 299_900.0, bidQty = 1000.0, askPrice = 300_100.0, askQty = 1000.0
    )

    @Test
    fun detectsGoldFunds() {
        assertTrue(fund("IRT1AYAR0001", "عيار", "صندوق س.طلاي عيار مفيد").isGoldFund)
        assertTrue(fund("IRT3GOHR0001", "گوهر", "صندوق گوهر فام امید").isGoldFund)
        assertFalse(fund("IRO1FOLD0001", "فولاد", "فولاد مباركه اصفهان").isGoldFund)
        assertFalse(fund("IRT1KARA0001", "کارا", "صندوق اهرمی کاریزما").isGoldFund)
    }

    @Test
    fun metalFeesAreReal() {
        val s = AppSettings()
        val paxg = Asset("nbx:paxg", "طلا", "PAXG", MarketKind.METAL, "IRR", 1e10, 0.0, 0L)
        val ayar = Asset("ir:9", "عیار", "عیار", MarketKind.METAL, "IRR", 300_000.0, 0.0, 0L)
        assertEquals(0.0025, Fees.commission(paxg, null, true, s), 1e-12)
        assertEquals(0.0025, Fees.commission(paxg, null, false, s), 1e-12)
        assertEquals(0.00125, Fees.commission(ayar, null, true, s), 1e-12)
        assertEquals(0.00125, Fees.commission(ayar, null, false, s), 1e-12)
        assertTrue(Fees.table(s).first { it.market == MarketKind.METAL }.real)
    }

    @Test
    fun defaultsGiveMetalFxShare() {
        val s = AppSettings()
        assertEquals(10.0, s.allocationPct(MarketKind.METAL))
        assertEquals(0.0, s.allocationPct(MarketKind.FX))
        assertEquals(100.0, MarketKind.TRADED.sumOf { s.allocationPct(it) })
        val plan = RiskManager().plan(s.riskFor(MarketKind.METAL), MarketKind.METAL)
        assertTrue(plan.maxPositions > 0)
        assertTrue(MarketKind.METAL in RiskManager().tradableMarkets)
    }

    @Test
    fun moveSleeveKeepsEquity() {
        val broker = PaperBroker(JsonStore(Files.createTempDirectory("metal").toFile()))
        broker.reset(1000.0, mapOf("CRYPTO" to 50.0, "IR_STOCK" to 40.0, "FX" to 10.0))
        assertEquals(100.0, broker.cashOf(MarketKind.FX), 1e-9)
        assertEquals(0.0, broker.cashOf(MarketKind.METAL), 1e-9)
        val moved = broker.moveSleeve(MarketKind.FX, MarketKind.METAL)
        assertNotNull(moved)
        assertEquals(100.0, moved, 1e-9)
        val a = broker.account()
        assertEquals(100.0, a.cashByMarket["METAL"]!!, 1e-9)
        assertEquals(100.0, a.capitalByMarket["METAL"]!!, 1e-9)
        assertEquals(0.0, a.cashByMarket["FX"]!!, 1e-9)
        assertEquals(1000.0, a.cashUsd, 1e-9)

        // با موقعیت باز انتقال انجام نمی‌شود
        val eur = Asset("fx:EUR", "EUR", "یورو", MarketKind.FX, "USD", 1.1, 0.0, 0L)
        broker.moveSleeve(MarketKind.METAL, MarketKind.FX)
        broker.buy(eur, 1.1, 50.0, 0.002, 1.0, 1.2, "t")
        assertNull(broker.moveSleeve(MarketKind.FX, MarketKind.METAL))
    }
}
