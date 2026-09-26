package com.saeidkazemi.trader

import com.saeidkazemi.trader.analysis.MarketTrend
import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.data.model.PricePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarketTrendTests {

    private val day = 86_400_000L
    private val d0 = 20_000L * day

    private fun series(n: Int, start: Double, dailyMul: Double) =
        (0 until n).map { i -> PricePoint(d0 + i * day, start * Math.pow(dailyMul, i.toDouble())) }

    @Test
    fun equalWeightChainIndex() {
        // یکی روزی ۲٪ بالا، دیگری ثابت → شاخص روزی ۱٪ بالا (مستقل از قیمت اسمی)
        val idx = MarketTrend.indexOf(listOf(series(10, 50_000.0, 1.02), series(10, 0.5, 1.0)))
        assertEquals(10, idx.size)
        assertEquals(100.0, idx.first().price, 1e-9)
        assertEquals(100.0 * Math.pow(1.01, 9.0), idx.last().price, 1e-6)
    }

    @Test
    fun badTicksAreClippedAndThinDaysSkipped() {
        val a = series(5, 100.0, 1.0).toMutableList()
        a[3] = PricePoint(a[3].t, 1000.0) // جهش خراب ×۱۰
        val idx = MarketTrend.indexOf(listOf(a))
        // بازده به ۵۰٪ محدود شده
        assertEquals(150.0, idx[3].price, 1e-9)

        // ۲۰ دارایی؛ روز آخر فقط یکی قیمت دارد (کمتر از ۱۵٪) → آن روز رد می‌شود
        val many = (0 until 20).map { k ->
            val s = series(6, 10.0 + k, 1.01)
            if (k == 0) s + PricePoint(d0 + 6 * day, 20.0) else s
        }
        val idx2 = MarketTrend.indexOf(many)
        assertEquals(6, idx2.size)
    }

    @Test
    fun buildUptrendAndDowntrend() {
        fun asset(id: String) = Asset(id, id.uppercase(), id, MarketKind.CRYPTO, "USD", 1.0, 1.0, 0L)
        val up = (1..5).associate { k -> "u$k" to series(80, 10.0 * k, 1.006) }
        val r = assertNotNull(MarketTrend.build(MarketKind.CRYPTO, (1..5).map { asset("u$it") }, up, listOf("fact")))
        assertTrue(r.trendScore > 0, r.trendLabel)
        assertEquals(5, r.constituents)
        assertTrue(r.change7d!! > 0 && r.change30d!! > 0)
        assertEquals(1.0, r.breadthAboveSma20!!, 1e-9)
        assertNotNull(r.forecast)
        assertEquals(listOf("fact"), r.facts)
        assertEquals(5, r.advancers)

        val down = (1..5).associate { k -> "d$k" to series(80, 10.0 * k, 0.99) }
        val r2 = assertNotNull(MarketTrend.build(MarketKind.CRYPTO, (1..5).map { asset("d$it") }, down))
        assertEquals(-2, r2.trendScore)
        assertEquals("نزولی قوی", r2.trendLabel)

        assertNull(MarketTrend.build(MarketKind.CRYPTO, listOf(asset("x")), mapOf("x" to series(5, 1.0, 1.0))))
    }

    @Test
    fun changeOverDays() {
        val s = series(40, 100.0, 1.01)
        assertEquals((Math.pow(1.01, 7.0) - 1) * 100, MarketTrend.changeOver(s, 7)!!, 1e-9)
        assertNull(MarketTrend.changeOver(s.take(1), 7))
    }
}
