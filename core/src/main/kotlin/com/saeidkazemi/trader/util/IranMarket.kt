package com.saeidkazemi.trader.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * قواعد بازار سهام ایران: ساعت کار، کارمزدها و تاریخ‌ها.
 * (تعطیلات رسمی را نمی‌دانیم؛ در روز تعطیل، TSETMC معامله جدیدی نشان نمی‌دهد و قیمت‌ها ثابت می‌مانند.)
 */
object IranMarket {
    val ZONE: ZoneId = ZoneId.of("Asia/Tehran")

    /** جلسه معاملاتی: شنبه تا چهارشنبه، ۹:۰۰ تا ۱۲:۳۰ به وقت تهران. */
    val OPEN: LocalTime = LocalTime.of(9, 0)
    val CLOSE: LocalTime = LocalTime.of(12, 30)

    /** کارمزد رسمی خرید سهام بورس (جزئیات در Fees). */
    const val BUY_FEE = com.saeidkazemi.trader.trading.Fees.IR_BOURSE_BUY

    /** کارمزد رسمی فروش سهام (شامل ۰٫۵٪ مالیات نقل‌وانتقال). */
    const val SELL_FEE = com.saeidkazemi.trader.trading.Fees.IR_SELL

    fun isTradingDay(date: LocalDate): Boolean =
        date.dayOfWeek != DayOfWeek.THURSDAY && date.dayOfWeek != DayOfWeek.FRIDAY

    fun isOpen(nowMs: Long = System.currentTimeMillis()): Boolean {
        val z = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), ZONE)
        if (!isTradingDay(z.toLocalDate())) return false
        val t = z.toLocalTime()
        return !t.isBefore(OPEN) && t.isBefore(CLOSE)
    }

    /** امروز به وقت تهران به شکل عدد yyyymmdd (مثل ۲۰۲۶۰۹۲۵). */
    fun todayInt(nowMs: Long = System.currentTimeMillis()): Int {
        val d = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), ZONE).toLocalDate()
        return d.year * 10000 + d.monthValue * 100 + d.dayOfMonth
    }

    /** نیمه‌شب (وقت تهران) روز yyyymmdd به میلی‌ثانیه. */
    fun dayStartMs(dEven: Int): Long {
        val d = LocalDate.of(dEven / 10000, (dEven / 100) % 100, dEven % 100)
        return d.atStartOfDay(ZONE).toInstant().toEpochMilli()
    }

    /** پیام وضعیت بازار برای نمایش. */
    fun statusText(nowMs: Long = System.currentTimeMillis()): String =
        if (isOpen(nowMs)) "بازار بورس باز است" else "بازار بورس بسته است (شنبه تا چهارشنبه ۹:۰۰ تا ۱۲:۳۰)"
}
