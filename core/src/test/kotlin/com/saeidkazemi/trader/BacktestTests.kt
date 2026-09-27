package com.saeidkazemi.trader

import com.saeidkazemi.trader.analysis.Backtest
import com.saeidkazemi.trader.analysis.RiskManager
import com.saeidkazemi.trader.data.model.AppSettings
import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.data.model.PricePoint
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BacktestTests {

    private val day = 86_400_000L
    private val costs = Backtest.Costs(0.0025, 0.0025, 0.001)

    private fun series(seed: Int, drift: Double, n: Int = 400): List<PricePoint> {
        val r = Random(seed)
        var p = 100.0
        return (0 until n).map { i ->
            p *= 1 + drift + 0.02 * sin(i / 9.0) + (r.nextDouble() - 0.5) * 0.03
            PricePoint(i * day, p)
        }
    }

    private fun asset(i: Int) = Asset("c$i", "C$i", "C$i", MarketKind.CRYPTO, "USD", 100.0, 0.0, 0L)

    @Test
    fun simulateAppliesCostsAndExits() {
        val s = AppSettings()
        val ser = Backtest.prepare(asset(1), series(1, 0.002), s, costs)
        assertNotNull(ser)
        val p = Backtest.Params(60, 0.05, 2.0, 0.03, 0.10, 0.0, 15)
        val trades = Backtest.simulate(listOf(ser), p, s)
        assertTrue(trades.isNotEmpty())
        // هیچ معامله‌ای بدون کارمزد سود کامل حد سود را نمی‌گیرد (هزینه رفت و برگشت ≈ ۰٫۷٪)
        assertTrue(trades.all { it.netPct < 5.0 * 1.5 + 50 })
        assertTrue(trades.all { it.reason.isNotEmpty() })
        val st = Backtest.stats(trades)
        assertEquals(trades.size, st.trades)
        assertTrue(st.winRate in 0.0..100.0)
        // تعریف ریسک به ریوارد و نرخ برد سربه‌سر
        if (st.rr > 0) assertEquals(100.0 / (1 + st.rr), st.breakEvenWinRate, 1e-9)
    }

    @Test
    fun statsMath() {
        val t = listOf(10.0, 10.0, 10.0, -5.0, -5.0).mapIndexed { i, v ->
            Backtest.Trade("a", "A", i * day, (i + 1) * day, v, "x", 1, 2.0)
        }
        val st = Backtest.stats(t)
        assertEquals(60.0, st.winRate, 1e-9)
        assertEquals(2.0, st.rr, 1e-9)
        assertEquals(4.0, st.expectancyPct, 1e-9)
        assertEquals(3.0, st.profitFactor, 1e-9)
        assertEquals(10.0, st.maxDrawdownPct, 1e-9)
    }

    @Test
    fun optimizeSplitsInAndOutOfSample() {
        val s = AppSettings()
        val list = (1..6).mapNotNull { Backtest.prepare(asset(it), series(it, 0.0015), s, costs) }
        val base = RiskManager().plan("MED", MarketKind.CRYPTO)
        val res = Backtest.optimize(MarketKind.CRYPTO, list, Backtest.paramsOf(base, 70), s)
        assertNotNull(res)
        assertEquals(Backtest.grid(MarketKind.CRYPTO).size, res.tested)
        if (res.applied) {
            val out = res.bestOut!!
            assertTrue(out.expectancyPct > 0)
            val tuned = Backtest.applyTo(base, res.best!!, 70)
            assertTrue(tuned.tuned)
            assertEquals(res.best!!.tpPct, tuned.tpPct)
            assertEquals(base.maxPositions, tuned.maxPositions)
        }
    }
}
