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
        assertTrue(melat.buyQueue, "تقاضا روی سقف ۲٬۳۴۸؛ عرضه ۳٬۳۹۰ بالاتر از سقف مجاز است و امروز اجرا نمی‌شود → صف خرید")
        assertEquals(null, melat.validAsk)
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
    fun intradayAdjustmentIgnoresStaleHistory() {
        val z = com.saeidkazemi.trader.util.IranMarket.ZONE
        fun ms(y: Int, mo: Int, d: Int, h: Int) = java.time.LocalDateTime.of(y, mo, d, h, 0).atZone(z).toInstant().toEpochMilli()
        val now = ms(2026, 10, 14, 10) // چهارشنبه ۱۰ صبح
        val rows = listOf(DailyRow(20261012, 950.0, 940.0), DailyRow(20261013, 1000.0, 950.0))
        val fresh = ms(2026, 10, 14, 8)
        val stale = ms(2026, 10, 12, 20)
        // افزایش سرمایه ۱۰۰٪ با تاریخچه تازه
        assertEquals(0.5, IranStockSource.intradayAdjustment(rows, fresh, 500.0, 10.0, 0.0, 0L, now)!!.second, 1e-9)
        // روز عادی
        assertEquals(null, IranStockSource.intradayAdjustment(rows, fresh, 1000.0, 10.0, 0.0, 0L, now))
        // «ضریب» بزرگ‌تر از ۱ هرگز تعدیل نیست
        assertEquals(null, IranStockSource.intradayAdjustment(rows, fresh, 1050.0, 10.0, 0.0, 0L, now))
        // تاریخچه کهنه: تغییر قیمت چند روز نباید تعدیل حساب شود
        assertEquals(null, IranStockSource.intradayAdjustment(rows, stale, 960.0, 10.0, 0.0, 0L, now))
        assertEquals(null, IranStockSource.intradayAdjustment(rows, stale, 1040.0, 10.0, 0.0, 0L, now))
        // بدون تاریخچه تازه: فقط افت خیلی بزرگ نسبت به آخرین قیمت دیده‌شده
        val seenAt = ms(2026, 10, 13, 12)
        assertEquals(0.5, IranStockSource.intradayAdjustment(null, 0L, 500.0, 10.0, 1000.0, seenAt, now)!!.second, 1e-9)
        assertEquals(null, IranStockSource.intradayAdjustment(null, 0L, 950.0, 10.0, 1000.0, seenAt, now))
        // هنوز معامله‌ای نشده
        assertEquals(null, IranStockSource.intradayAdjustment(rows, fresh, 500.0, 0.0, 0.0, 0L, now))
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
