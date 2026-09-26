package com.saeidkazemi.trader.data.model

/**
 * یک ردیف ژورنال معاملاتی: «چرا خریدم، با چه اطلاعاتی، چرا فروختم و نتیجه چه شد».
 * همه قیمت‌ها دلاری‌اند؛ قیمت به ارز خود دارایی (مثلاً ریال) هم جداگانه ثبت می‌شود.
 * فیلدهای لیستی ممکن است در داده‌های قدیمی null باشند.
 */
data class JournalEntry(
    val id: String,
    val assetId: String,
    val symbol: String,
    val name: String,
    val market: MarketKind,
    /** خرید خودکار (true) یا دستی. */
    val auto: Boolean,
    /** PAPER (دمو) یا REAL. */
    val mode: String,

    // ---- ورود ----
    val openedAt: Long,
    val entryUsd: Double,
    val entryNative: Double,
    val nativeCurrency: String,
    val usdIrr: Double,
    /** مبلغ پرداختی (شامل کارمزد خرید). */
    val amountUsd: Double,
    val buyFeeUsd: Double,
    /** هزینه اسپرد خرید (درصد): فاصله قیمت خرید واقعی از قیمت میانی. */
    val buySpreadPct: Double? = null,
    val qty: Double,
    val entryReason: String,
    val score: Int? = null,
    val technicalScore: Int? = null,
    val newsAdj: Int = 0,
    val proAdj: Int = 0,
    /** آستانه خرید همان بازار در لحظه خرید. */
    val threshold: Int? = null,
    val reasons: List<String>? = null,
    val proFactors: List<ProFactor>? = null,
    val metrics: Metrics? = null,
    val newsLabel: String? = null,
    val newsHeadlines: List<String>? = null,
    val newsSourcesOk: List<String>? = null,
    val newsSourcesFailed: List<String>? = null,
    val dataSources: List<String>? = null,
    val simulated: Boolean = false,
    val stopUsd: Double = 0.0,
    val takeProfitUsd: Double = 0.0,
    val trailPct: Double = 0.0,
    val riskLevel: String? = null,
    val forecastExpPct: Double? = null,
    val forecastProbUp: Double? = null,
    /** اگر محافظ نرخ برد فعال بود، توضیحش. */
    val guardNote: String? = null,

    // ---- خروج ----
    val closedAt: Long? = null,
    val exitUsd: Double? = null,
    val exitNative: Double? = null,
    val exitReason: String? = null,
    val exitScore: Int? = null,
    val exitReasons: List<String>? = null,
    /** مبلغ دریافتی پس از کارمزد فروش. */
    val proceedsUsd: Double? = null,
    val sellFeeUsd: Double? = null,
    /** هزینه اسپرد فروش (درصد). */
    val sellSpreadPct: Double? = null,
    /** سود/زیان واقعی = دریافتی − پرداختی (هر دو کارمزد لحاظ شده). */
    val pnlUsd: Double? = null,
    val pnlPct: Double? = null,
    /** بالاترین و پایین‌ترین قیمت دیده‌شده در طول نگهداری. */
    val peakUsd: Double? = null,
    val troughUsd: Double? = null,
    val profitLockedPct: Double = 0.0,
    /** از روی تاریخچه معاملات قبل از ژورنال ساخته شده (دلایل ورود در دسترس نیست). */
    val backfilled: Boolean = false
) {
    val isOpen: Boolean get() = closedAt == null
    val isWin: Boolean get() = (pnlUsd ?: 0.0) > 0
    val holdMs: Long get() = (closedAt ?: System.currentTimeMillis()) - openedAt

    /** بیشترین سود شناور در طول معامله (MFE)، درصد. */
    val maxGainPct: Double? get() = peakUsd?.let { if (entryUsd > 0) (it / entryUsd - 1) * 100 else null }

    /** بیشترین زیان شناور در طول معامله (MAE)، درصد. */
    val maxDrawPct: Double? get() = troughUsd?.let { if (entryUsd > 0) (it / entryUsd - 1) * 100 else null }
}
