package com.saeidkazemi.trader

import com.saeidkazemi.trader.data.local.JsonStore
import com.saeidkazemi.trader.data.model.AppSettings
import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.trading.PaperBroker
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** خرید پله‌ای با تأیید: پله اول، پله دوم در انتظار، اضافه شدن به موقعیت و لغو. */
class ScaledEntryTests {

    private val ada = Asset(
        id = "cardano", symbol = "ADA", name = "Cardano", market = MarketKind.CRYPTO,
        baseCurrency = "USD", price = 10.0, changePct24h = 0.0, updatedAt = 0L
    )

    @Test
    fun defaultOnlyCrypto() {
        val s = AppSettings()
        assertTrue(s.scaledEntry(MarketKind.CRYPTO))
        assertTrue(!s.scaledEntry(MarketKind.IR_STOCK))
        // تنظیمات قدیمی که این فیلد را ندارند (Gson null می‌گذارد) نباید خطا بدهند
        assertTrue(!s.copy(scaledEntryMarkets = null).scaledEntry(MarketKind.CRYPTO))
    }

    @Test
    fun addAndCancel() {
        val broker = PaperBroker(JsonStore(Files.createTempDirectory("scale").toFile()))
        broker.reset(100.0, mapOf("CRYPTO" to 100.0, "IR_STOCK" to 0.0, "METAL" to 0.0, "FX" to 0.0))
        val t1 = broker.buy(ada, 10.0, 20.0, 0.0, 9.0, 15.0, "x", addPendingUsd = 20.0, addTriggerUsd = 10.5, addDeadline = Long.MAX_VALUE)
        assertNotNull(t1)
        var pos = broker.account().positions.single()
        assertEquals(20.0, pos.addPendingUsd, 1e-9)
        assertEquals(80.0, broker.cashOf(MarketKind.CRYPTO), 1e-9)

        val t2 = broker.addToPosition("cardano", 11.0, 20.0, 0.0, "پله دوم")
        assertNotNull(t2)
        pos = broker.account().positions.single()
        assertEquals(2.0 + 20.0 / 11.0, pos.qty, 1e-9)
        assertEquals(40.0, pos.cost(), 1e-9)
        assertEquals(40.0 / pos.qty, pos.avgBuyUsd, 1e-9)
        assertEquals(9.0, pos.stopLossUsd, 1e-9)          // حد ضرر همان قبلی
        assertEquals(0.0, pos.addPendingUsd, 1e-9)
        assertTrue(pos.addedAt > 0)
        assertEquals(60.0, broker.cashOf(MarketKind.CRYPTO), 1e-9)

        // لغو پله دوم موقعیت دیگر
        val btc = ada.copy(id = "bitcoin", symbol = "BTC")
        broker.buy(btc, 100.0, 10.0, 0.0, 90.0, 150.0, "y", addPendingUsd = 10.0, addTriggerUsd = 105.0, addDeadline = 1L)
        assertTrue(broker.clearPendingAdd("bitcoin"))
        assertEquals(0.0, broker.account().positions.first { it.assetId == "bitcoin" }.addPendingUsd, 1e-9)
    }
}

class ScaleChoiceTests {
    private fun row(id: String, n: Int, exp: Double, oosN: Int, oosExp: Double, dd: Double) =
        com.saeidkazemi.trader.analysis.ScaleStudy.Row(
            id = id,
            all = com.saeidkazemi.trader.analysis.Backtest.Stats(trades = n, expectancyPct = exp, maxDrawdownPct = dd),
            oos = com.saeidkazemi.trader.analysis.Backtest.Stats(trades = oosN, expectancyPct = oosExp)
        )

    @Test
    fun picksClearlyBetter() {
        // داده گزارش HY9V-EK4K (ارز دیجیتال): D جمع ۴۴۷ در برابر ۴۰۳ و اخیر ۳۹۰ در برابر ۳۴۶ ⇐ D؛ I (۲۳۹) رد می‌شود
        val rows = listOf(
            row("A", 168, 2.40, 87, 3.98, 136.8), row("B", 187, 1.98, 98, 3.46, 164.4),
            row("D", 193, 2.32, 102, 3.82, 107.6), row("I", 168, 1.42, 87, 2.80, 158.3)
        )
        assertEquals("D", com.saeidkazemi.trader.analysis.ScaleStudy.choose(rows).first)
    }

    @Test
    fun keepsPlainWhenMarginal() {
        // طلا: D ۵۵۴ در برابر ۵۴۳ (کمتر از ۱۰٪ بهتر) ⇐ یک‌جا؛ B قابل اجرای زنده نیست
        val rows = listOf(row("A", 95, 5.71, 34, 0.96, 98.6), row("B", 109, 5.29, 39, 1.38, 98.6), row("D", 117, 4.73, 44, 2.49, 98.6))
        assertEquals("A", com.saeidkazemi.trader.analysis.ScaleStudy.choose(rows).first)
        // داده کم ⇐ یک‌جا
        assertEquals("A", com.saeidkazemi.trader.analysis.ScaleStudy.choose(listOf(row("A", 17, 6.0, 3, 8.0, 15.0), row("K", 12, 9.8, 2, 13.4, 5.0))).first)
    }

    @Test
    fun brokerRaiseAndRoll() {
        val broker = PaperBroker(JsonStore(Files.createTempDirectory("roll").toFile()))
        broker.reset(100.0, mapOf("CRYPTO" to 100.0, "IR_STOCK" to 0.0, "METAL" to 0.0, "FX" to 0.0))
        val a = Asset("x", "X", "X", MarketKind.CRYPTO, "USD", 10.0, 0.0, 0L)
        broker.buy(a, 10.0, 20.0, 0.0, 9.0, 15.0, "x", riskPct = 0.1)
        assertEquals(0.1, broker.account().positions.single().riskPct, 1e-9)
        assertTrue(broker.raiseStop("x", 10.05))
        assertTrue(!broker.raiseStop("x", 9.5))       // پایین آوردن ممنوع
        assertTrue(broker.rollTarget("x", 13.5, 22.5))
        val p = broker.account().positions.single()
        assertEquals(13.5, p.stopLossUsd, 1e-9)
        assertEquals(22.5, p.takeProfitUsd, 1e-9)
        assertEquals(1, p.rolls)
    }
}
