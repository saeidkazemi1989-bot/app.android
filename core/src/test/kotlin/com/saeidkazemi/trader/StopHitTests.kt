package com.saeidkazemi.trader

import com.saeidkazemi.trader.data.local.JsonStore
import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.trading.PaperBroker
import com.saeidkazemi.trader.util.IranMarket
import java.nio.file.Files
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** عیار بعد از بسته شدن بازار زیر حد ضرر رفت: ثبت زمان و توضیح جلسه بعدی. */
class StopHitTests {
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, IranMarket.ZONE).toInstant().toEpochMilli()

    @Test
    fun nextOpen() {
        // یکشنبه ۱۸:۴۹ → دوشنبه ۹:۰۰
        val sun = ms(2026, 10, 4, 18, 49)
        assertEquals(ms(2026, 10, 5, 9, 0), IranMarket.nextOpenMs(sun))
        assertTrue(IranMarket.nextOpenText(sun).startsWith("دوشنبه"))
        // چهارشنبه ۱۳:۰۰ → شنبه ۹:۰۰
        assertEquals(ms(2026, 10, 10, 9, 0), IranMarket.nextOpenMs(ms(2026, 10, 7, 13, 0)))
        // دوشنبه ۸:۰۰ → همان روز ۹:۰۰
        assertEquals(ms(2026, 10, 5, 9, 0), IranMarket.nextOpenMs(ms(2026, 10, 5, 8, 0)))
        assertEquals("12:27", IranMarket.clock(ms(2026, 10, 4, 12, 27)))
    }

    @Test
    fun markStopHitOnce() {
        val broker = PaperBroker(JsonStore(Files.createTempDirectory("hit").toFile()))
        broker.reset(1000.0, mapOf("METAL" to 100.0))
        val a = Asset("ir:9", "عیار", "عیار", MarketKind.METAL, "IRR", 700000.0, 0.0, 0L)
        broker.buy(a, 0.28, 20.0, 0.00125, 0.27, 0.32, "t", trailPct = 0.02, fxRate = 2_500_000.0)
        // قله تازه زمانش ثبت می‌شود
        broker.trail(mapOf("ir:9" to 0.30))
        assertTrue(broker.account().positions.single().peakAt > 0)
        assertTrue(broker.markStopHit("ir:9", 1000L, false))
        assertFalse(broker.markStopHit("ir:9", 2000L, false)) // فقط اولین بار
        assertEquals(1000L, broker.account().positions.single().stopHitAt)
        assertTrue(broker.markStopHit("ir:9", 0L, false)) // قیمت برگشت → پاک
        assertEquals(0L, broker.account().positions.single().stopHitAt)
    }
}
