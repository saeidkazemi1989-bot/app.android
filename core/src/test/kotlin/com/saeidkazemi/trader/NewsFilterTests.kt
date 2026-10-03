package com.saeidkazemi.trader

import com.saeidkazemi.trader.news.NewsService
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** تیترهای «گزارش حرکت قیمت» از ژورنال واقعی کاربر نباید خبر مهم حساب شوند. */
class NewsFilterTests {
    @Test
    fun priceReports() {
        assertTrue(NewsService.isPriceReport("BTC, ETH, DOGE price news: Doge slides 8%, Bitcoin under \$84,000 in crypto sell-off"))
        assertTrue(NewsService.isPriceReport("تقویم اقتصادی: کاهش قیمت طلا در میان صحبت های بانک مرکزی"))
        assertTrue(NewsService.isPriceReport("Chainlink (LINK) jumps 7.47% to \$13.42 in four hours amid crypto market surge"))
        assertFalse(NewsService.isPriceReport("Cardano: ADA joins Mastercard's crypto partner program"))
        assertFalse(NewsService.isPriceReport("SEC sues exchange over unregistered securities"))
    }
}
