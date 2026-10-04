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

    /** شروع جلسه معاملاتی بعدی (اگر الان باز است، همین الان). */
    fun nextOpenMs(nowMs: Long = System.currentTimeMillis()): Long {
        if (isOpen(nowMs)) return nowMs
        var z = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), ZONE)
        var d = z.toLocalDate()
        if (!z.toLocalTime().isBefore(OPEN)) d = d.plusDays(1)
        while (!isTradingDay(d)) d = d.plusDays(1)
        return d.atTime(OPEN).atZone(ZONE).toInstant().toEpochMilli()
    }

    private val FA_DAYS = mapOf(
        DayOfWeek.SATURDAY to "شنبه", DayOfWeek.SUNDAY to "یکشنبه", DayOfWeek.MONDAY to "دوشنبه",
        DayOfWeek.TUESDAY to "سه‌شنبه", DayOfWeek.WEDNESDAY to "چهارشنبه", DayOfWeek.THURSDAY to "پنجشنبه",
        DayOfWeek.FRIDAY to "جمعه"
    )

    /** مثل «دوشنبه ساعت ۹:۰۰» برای جلسه بعدی. */
    fun nextOpenText(nowMs: Long = System.currentTimeMillis()): String {
        val z = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nextOpenMs(nowMs)), ZONE)
        return (FA_DAYS[z.dayOfWeek] ?: "") + " ساعت ۹:۰۰"
    }

    /** ساعت به وقت تهران مثل 12:27 */
    fun clock(ts: Long): String {
        val z = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ts), ZONE)
        return String.format("%02d:%02d", z.hour, z.minute)
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
