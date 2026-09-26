package com.saeidkazemi.trader

import com.saeidkazemi.trader.data.model.AppSettings
import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.data.remote.InsightSource
import com.saeidkazemi.trader.trading.Fees
import com.saeidkazemi.trader.util.Format
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeesTests {

    private val s = AppSettings()

    @Test
    fun officialIranRates() {
        assertEquals(0.003712, Fees.commission(MarketKind.IR_STOCK, true, s), 1e-12)
        assertEquals(0.003632, Fees.commission(MarketKind.IR_STOCK, true, s, farabourse = true), 1e-12)
        assertEquals(0.0088, Fees.commission(MarketKind.IR_STOCK, false, s), 1e-12)
        val fara = Asset("ir:1", "X", "X", MarketKind.IR_STOCK, "IRR", 1.0, 0.0, 0L, farabourse = true)
        assertEquals(0.003632, Fees.commission(fara, null, true, s), 1e-12)
        // رفت‌وبرگشت کامل سهام ≈ ۱٫۲۵٪
        assertEquals(1.2512, (Fees.IR_BOURSE_BUY + Fees.IR_SELL) * 100, 1e-9)
    }

    @Test
    fun nobitexTakerTiers() {
        assertEquals(0.0025, Fees.commission(MarketKind.CRYPTO, true, s), 1e-12)
        assertEquals(0.0025, Fees.commission(MarketKind.CRYPTO, false, s), 1e-12)
        assertEquals(0.00135, Fees.commission(MarketKind.CRYPTO, true, s.copy(nobitexFeeTier = 6)), 1e-12)
        assertEquals(0.00135, Fees.commission(MarketKind.CRYPTO, true, s.copy(nobitexFeeTier = 99)), 1e-12)
        assertEquals(7, Fees.NOBITEX_TIERS.size)
        // فارکس فرضی از تنظیمات
        assertEquals(0.002, Fees.commission(MarketKind.FX, true, s), 1e-12)
        val table = Fees.table(s)
        assertTrue(table.first { it.market == MarketKind.CRYPTO }.real)
        assertFalse(table.first { it.market == MarketKind.FX }.real)
    }

    @Test
    fun spreadFromBook() {
        val json = """{"status":"ok","lastUpdate":1,"lastTradePrice":"100","bids":[["99.9","1"],["99.5","2"]],"asks":[["100.1","1"],["100.6","3"]]}"""
        assertEquals(0.001, InsightSource.parseHalfSpread(json)!!, 1e-9)
        assertNull(InsightSource.parseHalfSpread("""{"status":"ok","bids":[],"asks":[]}"""))
        assertNull(Fees.halfSpreadOf(101.0, 100.0))
        assertNull(Fees.halfSpreadOf(null, 100.0))
        assertEquals(Fees.MAX_HALF_SPREAD, Fees.halfSpreadOf(50.0, 150.0)!!, 1e-12)
    }

    @Test
    fun trimFormat() {
        assertEquals("0.25", Format.trim(0.25, 4))
        assertEquals("1", Format.trim(1.0, 3))
        assertEquals("0.3712", Format.trim(0.3712, 4))
    }
}
