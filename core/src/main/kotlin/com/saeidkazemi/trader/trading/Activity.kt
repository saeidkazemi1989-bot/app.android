package com.saeidkazemi.trader.trading

import com.saeidkazemi.trader.data.model.MarketKind

/**
 * وضعیت ربات در هر بازار در آخرین دور بررسی: چرا خرید کرد / نکرد و هر موقعیت باز چقدر با فروش فاصله دارد.
 * برای اینکه کاربر ببیند ربات واقعاً کار می‌کند و دلیل «معامله نکردن» را بداند.
 */
data class MarketActivity(
    val market: MarketKind,
    val ts: Long,
    val positions: Int,
    val maxPositions: Int,
    val freeCashUsd: Double,
    val minTradeUsd: Double,
    val threshold: Int,
    /** تعداد دارایی‌هایی که در این دور امتیازشان به آستانه خرید رسید. */
    val buySignals: Int,
    val bestSymbol: String?,
    val bestScore: Int?,
    val boughtNow: Int,
    val soldNow: Int,
    /** خلاصه یک‌خطی: چرا خرید شد / نشد. */
    val status: String,
    /** جزئیات: دلایل رد کاندیدها و فاصله هر موقعیت باز تا فروش. */
    val details: List<String>,
    val lastTradeAt: Long?
)
