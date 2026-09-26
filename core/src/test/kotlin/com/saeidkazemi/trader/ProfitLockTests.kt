package com.saeidkazemi.trader

import com.saeidkazemi.trader.analysis.RiskManager.ProfitLock
import com.saeidkazemi.trader.data.local.JsonStore
import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.trading.PaperBroker
import com.saeidkazemi.trader.util.IranMarket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfitLockTests {

    private val bf = IranMarket.BUY_FEE
    private val sf = IranMarket.SELL_FEE

    @Test
    fun lockPriceKeepsExactNetProfit() {
        val lp = ProfitLock.lockPrice(100.0, 10.0, bf, sf)
        // با کارمزد بورس، برای ۱۰٪ سود خالص قیمت باید حدود ۱۱۱٫۴ شود (نه ۱۱۰)
        assertEquals(111.39, lp, 0.02)
        assertEquals(0.10, ProfitLock.netGain(100.0, lp, bf, sf), 1e-9)
        // بدون کارمزد دقیقاً ۱۱۰
        assertEquals(110.0, ProfitLock.lockPrice(100.0, 10.0, 0.0, 0.0), 1e-9)
    }

    @Test
    fun triggersOnlyAfterNetTarget() {
        // قله ۱۱۱: سود خالص کمتر از ۱۰٪ → هنوز قفل نمی‌شود
        assertNull(ProfitLock.stopFor(100.0, 111.0, bf, sf, 10.0, 10.0))
        val s = ProfitLock.stopFor(100.0, 112.0, bf, sf, 10.0, 10.0)
        assertNotNull(s)
        assertEquals(ProfitLock.lockPrice(100.0, 10.0, bf, sf), s, 1e-9)
        // سود حفظ‌شده کمتر از آستانه (۱۰ → ۸)
        val s8 = ProfitLock.stopFor(100.0, 112.0, bf, sf, 10.0, 8.0)!!
        assertEquals(0.08, ProfitLock.netGain(100.0, s8, bf, sf), 1e-9)
        // سود حفظ‌شده هیچ‌وقت از آستانه بیشتر نمی‌شود
        assertEquals(s, ProfitLock.stopFor(100.0, 112.0, bf, sf, 10.0, 25.0)!!, 1e-9)
    }

    @Test
    fun brokerLockOnlyRaisesStop() {
        val store = JsonStore(Files.createTempDirectory("lock").toFile())
        val broker = PaperBroker(store)
        broker.reset(1000.0, mapOf("CRYPTO" to 100.0, "IR_STOCK" to 0.0, "FX" to 0.0))
        val a = Asset(
            id = "eth", symbol = "ETH", name = "eth", market = MarketKind.CRYPTO, baseCurrency = "USD",
            price = 100.0, changePct24h = 0.0, updatedAt = System.currentTimeMillis()
        )
        assertNotNull(broker.buy(a, 100.0, 500.0, 0.0, 92.0, 150.0, "t", trailPct = 0.11))
        // قیمت به ۱۱۰ رسید → حد ضرر متحرک ۹۷٫۹، ولی قفل سود آن را روی ۱۱۰ می‌برد
        broker.trail(mapOf("eth" to 110.0))
        assertEquals(97.9, broker.account().positions.first().stopLossUsd, 1e-9)
        val stop = ProfitLock.stopFor(100.0, 110.0, 0.0, 0.0, 10.0, 10.0)!!
        assertTrue(broker.lockProfit("eth", stop, 10.0))
        val p = broker.account().positions.first()
        assertEquals(110.0, p.stopLossUsd, 1e-9)
        assertEquals(10.0, p.profitLockedPct, 1e-9)
        // تکرار: قفل تازه‌ای نیست
        assertFalse(broker.lockProfit("eth", stop, 10.0))
        // قیمت تا ۱۴۰ بالا رفت → حد ضرر متحرک (۱۲۴٫۶) از قفل بالاتر می‌رود
        broker.trail(mapOf("eth" to 140.0))
        assertEquals(124.6, broker.account().positions.first().stopLossUsd, 1e-9)
        // قفل هیچ‌وقت حد ضرر را پایین نمی‌آورد
        broker.lockProfit("eth", stop, 10.0)
        assertEquals(124.6, broker.account().positions.first().stopLossUsd, 1e-9)
    }
}
