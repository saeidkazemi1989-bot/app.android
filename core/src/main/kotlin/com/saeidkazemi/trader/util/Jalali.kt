package com.saeidkazemi.trader.util

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** تبدیل تاریخ شمسی (جلالی) به میلادی و برعکس. */
object Jalali {

    private val tehran: ZoneId = ZoneId.of("Asia/Tehran")

    /** الگوریتم استاندارد تبدیل جلالی به میلادی. */
    fun toGregorian(jy: Int, jm: Int, jd: Int): LocalDate {
        val jy2 = jy + 1595
        var days = -355668 + 365 * jy2 + (jy2 / 33) * 8 + ((jy2 % 33) + 3) / 4 + jd +
            if (jm < 7) (jm - 1) * 31 else (jm - 7) * 30 + 186
        var gy = 400 * (days / 146097)
        days %= 146097
        if (days > 36524) {
            days--
            gy += 100 * (days / 36524)
            days %= 36524
            if (days >= 365) days++
        }
        gy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            gy += (days - 1) / 365
            days = (days - 1) % 365
        }
        var gd = days + 1
        val leap = (gy % 4 == 0 && gy % 100 != 0) || (gy % 400 == 0)
        val monthDays = intArrayOf(0, 31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var gm = 1
        while (gm <= 12 && gd > monthDays[gm]) {
            gd -= monthDays[gm]
            gm++
        }
        return LocalDate.of(gy, gm, gd)
    }

    /** تبدیل میلادی به جلالی: [سال، ماه، روز]. */
    fun fromGregorian(date: LocalDate): IntArray {
        val gy = date.year
        val gm = date.monthValue
        val gd = date.dayOfMonth
        val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + 365 * gy + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400 + gd + gdm[gm - 1]
        var jy = -1595 + 33 * (days / 12053)
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + days / 31
            jd = 1 + days % 31
        } else {
            jm = 7 + (days - 186) / 30
            jd = 1 + (days - 186) % 30
        }
        return intArrayOf(jy, jm, jd)
    }

    /**
     * تجزیه رشته‌هایی مثل «۱۴۰۳/۰۷/۰۱ ۱۸:۲۳:۱۲» یا «1405-07-02 13:00:33» (به وقت تهران)
     * و برگرداندن زمان یونیکس به میلی‌ثانیه.
     */
    fun parseToEpochMillis(text: String): Long? {
        val s = latinDigits(text).trim()
        val m = Regex("(\\d{4})[/-](\\d{1,2})[/-](\\d{1,2})(?:\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?").find(s)
            ?: return null
        return try {
            val (y, mo, d) = Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            if (y !in 1300..1500 || mo !in 1..12 || d !in 1..31) return null
            val g = toGregorian(y, mo, d)
            val hh = m.groupValues[4].toIntOrNull() ?: 0
            val mm = m.groupValues[5].toIntOrNull() ?: 0
            val ss = m.groupValues[6].toIntOrNull() ?: 0
            LocalDateTime.of(g.year, g.monthValue, g.dayOfMonth, hh, mm, ss)
                .atZone(tehran).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    /** تاریخ شمسی کوتاه برای نمایش، مثل 1405/07/03. */
    fun format(ts: Long): String {
        val d = java.time.Instant.ofEpochMilli(ts).atZone(tehran).toLocalDate()
        val j = fromGregorian(d)
        return String.format(java.util.Locale.US, "%04d/%02d/%02d", j[0], j[1], j[2])
    }

    fun latinDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                in '۰'..'۹' -> sb.append('0' + (c - '۰'))
                in '٠'..'٩' -> sb.append('0' + (c - '٠'))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
