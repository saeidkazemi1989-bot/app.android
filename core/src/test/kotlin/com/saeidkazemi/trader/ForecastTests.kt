package com.saeidkazemi.trader

import com.saeidkazemi.trader.analysis.Forecast
import com.saeidkazemi.trader.analysis.PositionOutlook
import com.saeidkazemi.trader.data.local.JsonStore
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.data.model.Position
import com.saeidkazemi.trader.data.model.PricePoint
import com.saeidkazemi.trader.trading.PositionTracker
import java.nio.file.Files
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForecastTests {

    private val day = 86_400_000L
    private val now = 1_780_000_000_000L

    private fun series(n: Int, dailyDrift: Double, noise: Double, seed: Long = 42): List<PricePoint> {
        val r = Random(seed)
        var p = 100.0
        return (0 until n).map { i ->
            p *= Math.exp(dailyDrift + noise * r.nextGaussian())
            PricePoint(now - (n - 1 - i) * day, p)
        }
    }

    @Test
    fun normalCdf() {
        assertEquals(0.5, Forecast.phi(0.0), 1e-7)
        assertEquals(0.975, Forecast.phi(1.96), 1e-3)
        assertEquals(0.025, Forecast.phi(-1.96), 1e-3)
    }

    @Test
    fun tooLittleHistoryGivesNoForecast() {
        assertNull(Forecast.build(series(10, 0.0, 0.02), 100.0, now))
        assertNull(Forecast.build(series(60, 0.0, 0.02), 0.0, now))
    }

    @Test
    fun uptrendForecastsUpAndIsCapped() {
        val h = series(90, 0.01, 0.01)
        val cur = h.last().price
        val f = assertNotNull(Forecast.build(h, cur, now))
        assertEquals(Forecast.HORIZON_DAYS + 1, f.points.size)
        assertTrue(f.expectedPct > 0, "expected up, got ${f.expectedPct}")
        assertTrue(f.probUp > 0.5)
        assertTrue(f.muDay <= 0.35 * f.sigmaDay + 1e-12, "drift must be capped")
        assertTrue(f.low < f.mid && f.mid < f.high)
        assertEquals(cur, f.points.first().mid, 1e-9)
        // مخروط با زمان بازتر می‌شود
        assertTrue(f.points[7].high - f.points[7].low > f.points[1].high - f.points[1].low)
    }

    @Test
    fun downtrendForecastsDown() {
        val h = series(90, -0.01, 0.01)
        val f = assertNotNull(Forecast.build(h, h.last().price, now))
        assertTrue(f.expectedPct < 0)
        assertTrue(f.probUp < 0.5)
    }

    @Test
    fun scoreTiltsDrift() {
        val h = series(90, 0.0, 0.02)
        val hi = assertNotNull(Forecast.build(h, h.last().price, now, score = 95))
        val lo = assertNotNull(Forecast.build(h, h.last().price, now, score = 5))
        assertTrue(hi.muDay > lo.muDay)
    }

    @Test
    fun touchProbabilities() {
        val h = series(90, 0.0, 0.02)
        val cur = h.last().price
        val f = assertNotNull(Forecast.build(h, cur, now))
        assertTrue(f.probTouch(cur * 1.0001) > 0.95)
        assertTrue(f.probTouch(cur * 0.9999) > 0.95)
        assertTrue(f.probTouch(cur * 3) < 0.01)
        assertTrue(f.probTouch(cur * 1.05) > f.probTouch(cur * 1.15))
        // رسیدن در طول مسیر همیشه محتمل‌تر از بالاتر بودن در پایان است
        assertTrue(f.probTouch(cur * 1.05) >= f.probAbove(cur * 1.05))
        val scaled = f.scaled(10.0)
        assertEquals(f.expectedPct, scaled.expectedPct, 1e-9)
        assertEquals(f.current * 10, scaled.current, 1e-9)
    }

    @Test
    fun outlookUsesFeesAndBuildsTrendFromBuy() {
        val h = series(90, 0.0, 0.015)
        val opened = now - 5 * day
        val track = listOf(PricePoint(now - 2 * day, 104.0), PricePoint(now - day, 106.0))
        val o = PositionOutlook.build(
            assetId = "x", avgBuyUsd = 100.0, openedAt = opened, currentUsd = 108.0,
            stopUsd = 92.0, takeProfitUsd = 150.0, buyFee = 0.003712, sellFee = 0.008812,
            historyUsd = h, track = track, score = 60, simulated = false, now = now
        )
        assertEquals(8.0, o.pnlPct, 1e-9)
        assertTrue(o.netPnlPct < 8.0 && o.netPnlPct > 6.5)
        assertEquals(100.0 / ((1 - 0.003712) * (1 - 0.008812)), o.breakEvenUsd, 1e-9)
        // اولین نقطه = خرید، آخرین = قیمت فعلی
        assertEquals(PricePoint(opened, 100.0), o.trend.first())
        assertEquals(108.0, o.trend.last().price, 1e-9)
        // نقاط روزانه بین خرید و شروع ثبت مسیر هم آمده‌اند
        assertTrue(o.trend.any { it.t > opened + day / 2 && it.t < now - 2 * day })
        assertTrue(o.trend.zipWithNext().all { (a, b) -> b.t > a.t }, "trend must be time-ordered")
        assertNotNull(o.forecast)
        assertNotNull(o.probProfit)
        assertNotNull(o.probStop)
        assertNotNull(o.probTakeProfit)
        assertTrue(o.lowPnlPct!! < o.expectedPnlPct!! && o.expectedPnlPct!! < o.highPnlPct!!)
    }

    private fun pos(id: String, openedAt: Long, avg: Double) = Position(
        assetId = id, symbol = id, name = id, market = MarketKind.CRYPTO, nativeCurrency = "USD",
        qty = 1.0, avgBuyUsd = avg, openedAt = openedAt, stopLossUsd = avg * 0.9, takeProfitUsd = avg * 1.5
    )

    @Test
    fun trackerRecordsThinsPrunesAndPersists() {
        val dir = Files.createTempDirectory("trk").toFile()
        val store = JsonStore(dir)
        val tr = PositionTracker(store)
        val p = pos("btc", now, 100.0)
        for (i in 1..1000) tr.record(listOf(p), mapOf("btc" to 100.0 + i * 0.01), now + i * 60_000L)
        val t = tr.track("btc")
        assertTrue(t.size <= PositionTracker.MAX_POINTS)
        assertEquals(PricePoint(now, 100.0), t.first())
        assertEquals(110.0, t.last().price, 1e-9)
        assertTrue(t.zipWithNext().all { (a, b) -> b.t > a.t })

        // بارگذاری دوباره از فایل
        val tr2 = PositionTracker(store)
        assertEquals(t.size, tr2.track("btc").size)

        // خرید دوباره همان دارایی: مسیر از نو
        val p2 = pos("btc", now + 2000 * 60_000L, 120.0)
        tr2.record(listOf(p2), mapOf("btc" to 121.0), now + 2001 * 60_000L)
        assertEquals(listOf(PricePoint(p2.openedAt, 120.0), PricePoint(now + 2001 * 60_000L, 121.0)), tr2.track("btc"))

        // موقعیت بسته شد: مسیر پاک می‌شود
        tr2.record(emptyList(), emptyMap(), now + 2002 * 60_000L)
        assertTrue(tr2.track("btc").isEmpty())
    }
}
