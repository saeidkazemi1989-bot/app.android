package com.saeidkazemi.trader

import com.saeidkazemi.trader.data.local.JsonStore
import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.data.remote.IranStockSource
import com.saeidkazemi.trader.trading.PaperBroker
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IranQuoteTests {

    /** نمونه واقعی «ذرت» ۵ مهر ۱۴۰۵: آخرین ۹٬۶۲۰ (کف مجاز)، پایانی ۹٬۹۱۰ = دیروز، عرضه ۹٬۶۲۰، تقاضا ۹٬۳۵۰ (زیر کف). */
    private fun zorat(bidPrice: Double = 9350.0, bidQty: Double = 21000.0) = IranStockSource.Quote(
        insCode = "1", isin = "IRO3ZRTZ0001", symbol = "ذرت", name = "زرین ذرت شاهرود",
        last = 9620.0, close = 9910.0, yesterday = 9910.0, maxAllowed = 10200.0, minAllowed = 9620.0,
        valueIrr = 2.9e9, volume = 295_300.0, trades = 100.0, eps = null, pe = null,
        bidPrice = bidPrice, bidQty = bidQty, askPrice = 9620.0, askQty = 3_730_000.0
    )

    @Test
    fun sellQueueIgnoresBidsBelowFloor() {
        val q = zorat()
        assertTrue(q.sellQueue, "خریدار زیر کف مجاز امروز اجرا نمی‌شود؛ نماد در صف فروش است")
        assertNull(q.validBid)
        assertEquals(9620.0, q.validAsk)
        assertFalse(q.buyQueue)
        // خریدار واقعی روی کف → صف فروش نیست
        assertFalse(zorat(bidPrice = 9620.0).sellQueue)
    }

    @Test
    fun changeUsesLastTrade() {
        val q = zorat()
        assertEquals(-2.926, q.changePct!!, 0.001)
        assertEquals(0.0, q.closeChangePct!!, 1e-9)
        assertEquals(9620.0, q.price)
    }

    @Test
    fun fxRateIsStoredOnPosition() {
        val broker = PaperBroker(JsonStore(Files.createTempDirectory("fx").toFile()))
        broker.reset(1000.0, mapOf("IR_STOCK" to 100.0))
        val a = Asset("ir:1", "ذرت", "ذرت", MarketKind.IR_STOCK, "IRR", 9620.0, 0.0, 0L)
        val rate = 1_000_000.0
        broker.buy(a, 9620.0 / rate, 100.0, 0.003712, 0.0, 1.0, "t", fxRate = rate)
        assertEquals(rate, broker.account().positions.single().fxRate)
        // موقعیت قدیمی بدون نرخ
        val b = a.copy(id = "ir:2")
        broker.buy(b, 9620.0 / rate, 100.0, 0.003712, 0.0, 1.0, "t")
        assertEquals(0.0, broker.account().positions.first { it.assetId == "ir:2" }.fxRate)
        assertTrue(broker.setFxRate("ir:2", 950_000.0))
        assertFalse(broker.setFxRate("ir:2", 1.0))
        assertEquals(950_000.0, broker.account().positions.first { it.assetId == "ir:2" }.fxRate)
    }
}
