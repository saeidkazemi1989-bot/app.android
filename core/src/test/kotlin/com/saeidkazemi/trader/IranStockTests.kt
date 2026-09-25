package com.saeidkazemi.trader

import com.saeidkazemi.trader.data.remote.IranStockSource
import com.saeidkazemi.trader.data.remote.IranStockSource.DailyRow
import com.saeidkazemi.trader.util.IranMarket
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IranStockTests {

    private fun sample(): String =
        javaClass.classLoader.getResource("marketwatch_sample.json")!!.readText(Charsets.UTF_8)

    @Test
    fun parsesMarketWatchAndDetectsQueues() {
        val quotes = IranStockSource.parseMarketWatch(sample()).associateBy { it.symbol }
        assertEquals(5, quotes.size)

        val folad = quotes.getValue("فولاد")
        assertTrue(folad.isStock)
        assertEquals("فولاد مبارکه اصفهان", folad.name) // «ك» عربی به «ک» فارسی تبدیل شده
        assertEquals(4100.0, folad.last)
        assertTrue(folad.buyQueue, "فولاد روی سقف با عرضه صفر → صف خرید")
        assertFalse(folad.sellQueue)
        assertTrue(folad.valueIrr > IranStockSource.MIN_VALUE_IRR)

        val iran = quotes.getValue("وایران")
        assertTrue(iran.sellQueue, "عرضه روی کف و تقاضای صفر → صف فروش")
        assertFalse(iran.buyQueue)

        val melat = quotes.getValue("وبملت")
        assertFalse(melat.buyQueue, "روی سقف است ولی فروشنده دارد")
        assertEquals(2.1, melat.pe)

        val bond = quotes.values.first { it.isin.startsWith("IRB") }
        assertFalse(bond.isStock)
    }

    @Test
    fun adjustsForCapitalIncrease() {
        // روز ۴: افزایش سرمایه ۱۰۰٪ → قیمت دیروزِ اعلامی نصف پایانی روز قبل.
        val rows = listOf(
            DailyRow(20260901, 1000.0, 990.0),
            DailyRow(20260902, 1020.0, 1000.0),
            DailyRow(20260903, 1040.0, 1020.0),
            DailyRow(20260906, 530.0, 520.0),
            DailyRow(20260907, 540.0, 530.0)
        )
        val adj = IranStockSource.adjust(rows).map { it.second }
        assertEquals(500.0, adj[0], 1e-9)
        assertEquals(520.0, adj[2], 1e-9)
        assertEquals(530.0, adj[3], 1e-9)
        assertEquals(540.0, adj[4], 1e-9)
        val ev = IranStockSource.lastAdjustment(rows)
        assertNotNull(ev)
        assertEquals(20260906, ev.first)
        assertEquals(0.5, ev.second, 1e-9)
        assertEquals(null, IranStockSource.lastAdjustment(rows.take(3)))
    }

    @Test
    fun parsesDailyList() {
        val json = """{"closingPriceDaily":[
            {"dEven":20230524,"pClosing":24652.0,"priceYesterday":24417.0},
            {"dEven":20230523,"pClosing":24417.0,"priceYesterday":24300.0}]}"""
        val rows = IranStockSource.parseDaily(json)
        assertEquals(listOf(20230523, 20230524), rows.map { it.dEven })
    }

    @Test
    fun marketHours() {
        fun at(s: String) = ZonedDateTime.parse(s).toInstant().toEpochMilli()
        assertTrue(IranMarket.isOpen(at("2026-09-26T10:00:00+03:30[Asia/Tehran]")))   // شنبه
        assertFalse(IranMarket.isOpen(at("2026-09-26T13:00:00+03:30[Asia/Tehran]")))  // بعد از بسته شدن
        assertFalse(IranMarket.isOpen(at("2026-09-24T10:00:00+03:30[Asia/Tehran]")))  // پنج‌شنبه
        assertEquals(20260925, IranMarket.todayInt(at("2026-09-25T01:00:00+03:30[Asia/Tehran]")))
    }
}
