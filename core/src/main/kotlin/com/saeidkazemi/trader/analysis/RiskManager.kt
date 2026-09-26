package com.saeidkazemi.trader.analysis

import com.saeidkazemi.trader.data.model.MarketKind

/**
 * مدیریت ریسک جداگانه برای هر بازار. هر بازار با سرمایه اختصاصی خودش و «راه خودش» معامله می‌کند:
 *
 * - **ارز دیجیتال**: بازار ۲۴ ساعته و پرنوسان → حد ضرر بر اساس نوسان (نه درصد ثابت)، حد سود باز و
 *   حد ضرر متحرک که سود را قفل می‌کند؛ خرید آلت‌کوین فقط وقتی بیت‌کوین در روند نزولی شدید نیست.
 * - **بورس تهران**: دامنه نوسان روزانه و صف دارد → موقعیت‌های بیشتر و کوچک‌تر (پخش ریسک بین نمادها)،
 *   حد ضرر عریض‌تر (چون یک روز صف فروش می‌تواند از حد ضرر عبور کند)، حد ضرر متحرک و اتکای بیشتر به
 *   جریان پول حقیقی.
 * - **ارز خارجی**: کم‌نوسان → حد ضرر و سود نزدیک، موقعیت‌های کمتر.
 */
class RiskManager {

    data class Plan(
        val title: String,
        /** حداکثر موقعیت هم‌زمان در این بازار. */
        val maxPositions: Int,
        /** سهم هر خرید از ارزش همان بازار. */
        val positionPct: Double,
        /** حد ضرر پایه (اگر نوسان در دسترس نباشد). */
        val stopPct: Double,
        /** حد سود ثابت؛ برای بازارهایی که حد ضرر متحرک دارند بزرگ است تا سود بدود. */
        val tpPct: Double,
        /** ذخیره نقد اجباری این بازار. */
        val cashReservePct: Double,
        val minTradeUsd: Double,
        /** ضریب نوسان روزانه برای حد ضرر (مثلاً ۲٫۵ × نوسان روزانه). */
        val volStopMult: Double = 0.0,
        val minStopPct: Double = stopPct,
        val maxStopPct: Double = stopPct,
        /** فاصله حد ضرر متحرک از قله؛ ۰ یعنی غیرفعال. */
        val trailPct: Double = 0.0,
        /** آستانه خرید اختصاصی بازار (جمع با آستانه تنظیمات). */
        val buyThresholdDelta: Int = 0
    ) {
        /** حد ضرر نهایی برای یک دارایی با نوسان روزانه مشخص (درصد). */
        fun stopFor(volatilityPct: Double?): Double {
            if (volStopMult <= 0 || volatilityPct == null || !volatilityPct.isFinite() || volatilityPct <= 0) return stopPct
            return (volatilityPct / 100.0 * volStopMult).coerceIn(minStopPct, maxStopPct)
        }
    }

    /** برنامه قدیمی (یک سطح برای همه) — برای سازگاری. */
    fun plan(level: String): Plan = plan(level, MarketKind.CRYPTO)

    fun plan(level: String, market: MarketKind): Plan = when (market) {
        MarketKind.CRYPTO -> when (level) {
            "LOW" -> Plan("کم‌ریسک", 5, 0.15, 0.06, 0.40, 0.15, 10.0, 2.0, 0.05, 0.10, 0.08, 4)
            "HIGH" -> Plan("پرریسک", 3, 0.30, 0.10, 0.60, 0.05, 10.0, 3.0, 0.07, 0.18, 0.14, -3)
            else -> Plan("متوسط", 4, 0.22, 0.08, 0.50, 0.10, 10.0, 2.5, 0.06, 0.14, 0.11, 0)
        }

        MarketKind.IR_STOCK -> when (level) {
            "LOW" -> Plan("کم‌ریسک", 8, 0.11, 0.08, 0.35, 0.12, 10.0, 2.0, 0.07, 0.12, 0.10, 4)
            "HIGH" -> Plan("پرریسک", 4, 0.24, 0.12, 0.60, 0.05, 10.0, 3.0, 0.09, 0.18, 0.15, -3)
            else -> Plan("متوسط", 6, 0.15, 0.10, 0.45, 0.08, 10.0, 2.5, 0.08, 0.15, 0.12, 0)
        }

        MarketKind.FX -> when (level) {
            "LOW" -> Plan("کم‌ریسک", 3, 0.30, 0.015, 0.03, 0.10, 10.0, 2.0, 0.01, 0.025, 0.015, 2)
            "HIGH" -> Plan("پرریسک", 2, 0.50, 0.03, 0.06, 0.05, 10.0, 3.0, 0.02, 0.05, 0.03, -2)
            else -> Plan("متوسط", 3, 0.33, 0.02, 0.04, 0.08, 10.0, 2.5, 0.015, 0.035, 0.02, 0)
        }

        MarketKind.METAL -> Plan("نمایشی", 0, 0.0, 0.05, 0.1, 1.0, 10.0)
    }

    /**
     * قفل سود: محاسبات بر اساس سود **خالص** (پس از کارمزد خرید و فروش/مالیات) انجام می‌شود تا
     * «۱۰٪ سود» واقعاً ۱۰٪ اضافه روی پول واردشده باشد.
     *
     * در خرید، کارمزد از مبلغ کم می‌شود: تعداد = مبلغ × (۱ − کارمزد خرید) ÷ قیمت خرید.
     * در فروش: دریافتی = تعداد × قیمت × (۱ − کارمزد فروش).
     */
    object ProfitLock {
        /** سود خالص (کسری) اگر با قیمت [price] فروخته شود. */
        fun netGain(avgBuy: Double, price: Double, buyFee: Double, sellFee: Double): Double =
            if (avgBuy <= 0) 0.0 else price * (1 - buyFee) * (1 - sellFee) / avgBuy - 1

        /** قیمتی که فروش در آن دقیقاً [keepPct] درصد سود خالص می‌دهد. */
        fun lockPrice(avgBuy: Double, keepPct: Double, buyFee: Double, sellFee: Double): Double =
            avgBuy * (1 + keepPct / 100.0) / ((1 - buyFee) * (1 - sellFee))

        /**
         * اگر سود خالص در قله به [triggerPct] رسیده باشد، قیمت حد ضرر قفل سود را برمی‌گرداند (وگرنه null).
         * سود حفظ‌شده هیچ‌وقت بیشتر از آستانه نیست.
         */
        fun stopFor(
            avgBuy: Double, peak: Double, buyFee: Double, sellFee: Double,
            triggerPct: Double, keepPct: Double
        ): Double? {
            if (triggerPct <= 0 || keepPct <= 0 || peak <= 0) return null
            if (netGain(avgBuy, peak, buyFee, sellFee) * 100.0 + 1e-9 < triggerPct) return null
            return lockPrice(avgBuy, minOf(keepPct, triggerPct), buyFee, sellFee)
        }
    }

    /** بازارهایی که سرمایه جداگانه می‌گیرند (فلزات فعلاً فقط نمایشی‌اند). */
    val tradableMarkets = listOf(MarketKind.CRYPTO, MarketKind.IR_STOCK, MarketKind.FX)
}
