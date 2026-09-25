package com.saeidkazemi.trader.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {

    private val symbols = DecimalFormatSymbols(Locale.US)

    fun money(v: Double, digits: Int = 2): String {
        val pattern = if (digits > 0) "#,##0." + "0".repeat(digits) else "#,##0"
        return DecimalFormat(pattern, symbols).format(v)
    }

    /** عدد متعارف با جداکننده هزارگان. */
    fun num(v: Double, digits: Int = 2): String = money(v, digits)

    fun price(v: Double): String {
        return when {
            v >= 1000 -> money(v, 0)
            v >= 1 -> money(v, 2)
            else -> money(v, 6)
        }
    }

    fun compactUsd(v: Double): String {
        val a = kotlin.math.abs(v)
        return when {
            a >= 1_000_000_000 -> money(v / 1_000_000_000, 2) + "B$"
            a >= 1_000_000 -> money(v / 1_000_000, 2) + "M$"
            a >= 1_000 -> money(v / 1_000, 1) + "K$"
            else -> money(v, 2) + "$"
        }
    }

    fun compactIrr(v: Double): String {
        val a = kotlin.math.abs(v)
        return when {
            a >= 1_000_000_000_000 -> money(v / 1_000_000_000_000, 2) + " همت"
            a >= 1_000_000_000 -> money(v / 1_000_000_000, 2) + " میلیارد"
            a >= 1_000_000 -> money(v / 1_000_000, 1) + " میلیون"
            a >= 1_000 -> money(v / 1_000, 1) + " هزار"
            else -> money(v, 0)
        }
    }

    /** عدد بدون جداکننده هزارگان - مناسب فیلدهای ورودی و مقادیر قابل پارس. */
    fun raw(v: Double, digits: Int = 2): String {
        val pattern = if (digits > 0) "0." + "0".repeat(digits) else "0"
        return DecimalFormat(pattern, symbols).format(v)
    }

    fun pct(v: Double?, signed: Boolean = true): String {
        if (v == null) return "—"
        val prefix = if (signed && v > 0) "+" else ""
        return prefix + money(v, 2) + "٪"
    }

    fun dateTime(ts: Long): String {
        return SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(ts))
    }

    fun date(ts: Long): String {
        return SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date(ts))
    }

    private val faDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

    /** تبدیل ارقام لاتین به فارسی (برای متن‌های کوتاه مثل اعلان‌ها). */
    fun fa(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) {
            if (c in '0'..'9') sb.append(faDigits[c - '0']) else sb.append(c)
        }
        return sb.toString()
    }
}
