package com.saeidkazemi.trader

import com.saeidkazemi.trader.analysis.Backtest
import com.saeidkazemi.trader.analysis.ScaleStudy
import com.saeidkazemi.trader.data.model.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/** ورود/خروج پله‌ای: مسیر «۱۲٪ سود، بعد برگشت زیر حد ضرر» باید با فروش پله‌ای سودده بماند. */
class ScaleStudyTests {

    private fun series(px: DoubleArray): Backtest.Series {
        val n = px.size
        return Backtest.Series(
            "x", "X", LongArray(n) { 1_700_000_000_000L + it * 86_400_000L }, px,
            IntArray(n) { if (it == 0) 100 else -1 }, DoubleArray(n) { Double.NaN }, Backtest.Costs(0.0, 0.0, 0.0)
        )
    }

    private val p = Backtest.Params(threshold = 60, tpPct = 0.5, stopMult = 2.0, minStopPct = 0.1, maxStopPct = 0.1, trailPct = 0.0, maxHoldDays = 0)
    private fun net(id: String, px: DoubleArray): Double =
        ScaleStudy.simulate(series(px), p, AppSettings(), ScaleStudy.VARIANTS.first { it.id == id }).single().trade.netPct

    @Test
    fun givebackPath() {
        val px = doubleArrayOf(100.0, 105.0, 112.0, 104.0, 95.0, 89.0)
        assertEquals(-11.0, net("A", px), 1e-6)            // یک‌جا: کل سود پس داده شد و با حد ضرر بسته شد
        assertEquals(-5.0, net("C", px), 1e-6)             // سربه‌سر بعد از 1R: خروج در ۹۵ (بسته شدن روزانه زیر سربه‌سر)
        assertEquals(3.5, net("E", px), 1e-6)              // نصف در ۱۱۲ (+۶٪) + نصف در ۹۵ (−۲٫۵٪)
    }

    @Test
    fun scaledEntryCountsIdleHalfAsZero() {
        // قیمت فقط بالا می‌رود ولی به 0.5R (۵٪) نمی‌رسد تا حد سود؛ ورود تأییدی نیمه دوم را هرگز نمی‌خرد
        val px = doubleArrayOf(100.0, 102.0, 103.0, 104.0, 104.5, 104.0, 104.8, 150.0)
        assertEquals(50.0, net("A", px), 1e-6)
        assertEquals(25.0, net("I", px), 1e-6)
    }

    @Test
    fun rollAtTakeProfit() {
        // حد سود ۵۰٪: K در ۱۵۰ نمی‌فروشد، حد ضرر ۱۳۵ و حد سود ۲۲۵ می‌شود
        assertEquals(50.0, net("A", doubleArrayOf(100.0, 150.0, 160.0, 130.0)), 1e-6)
        assertEquals(30.0, net("K", doubleArrayOf(100.0, 150.0, 160.0, 130.0)), 1e-6)
        // دوباره به حد سود جدید (۲۲۵) رسید: حد ضرر ۲۰۷؛ خروج در ۲۰۰
        assertEquals(100.0, net("K", doubleArrayOf(100.0, 150.0, 230.0, 200.0)), 1e-6)
    }

    @Test
    fun breakEvenAfter3Pct() {
        // مثل LINK: +۱۰٫۶٪ سود، بعد برگشت زیر حد ضرر. با بی‌ضرر کردن بعد از ۳٪، خروج در ۹۸ بسته روزانه زیر ۱۰۰٫۱
        val px = doubleArrayOf(100.0, 103.5, 110.6, 104.0, 98.0, 89.0)
        assertEquals(-11.0, net("A", px), 1e-6)
        val v = ScaleStudy.VARIANTS.first { it.id == "A" }.copy(bePct = 3.0)
        assertEquals(-2.0, ScaleStudy.simulate(series(px), p, AppSettings(), v).single().trade.netPct, 1e-6)
        // تصمیم: داده کم ⇒ اجرا
        assertEquals(true, ScaleStudy.guardDecision(emptyList(), "A", null).first)
    }
}
