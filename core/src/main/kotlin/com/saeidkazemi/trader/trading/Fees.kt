package com.saeidkazemi.trader.trading

import com.saeidkazemi.trader.data.model.AppSettings
import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.MarketKind

/**
 * هزینه واقعی معاملات هر بازار.
 *
 * دو جزء جدا حساب می‌شود:
 * 1. **کارمزد** (درصد از ارزش معامله) طبق جدول رسمی همان بازار؛
 * 2. **اسپرد** (فاصله بهترین قیمت خرید و فروش): سفارش «بازار» به بهترین قیمت فروشنده خریده و به بهترین
 *    قیمت خریدار فروخته می‌شود، پس نصف اسپرد در هر طرف معامله هزینه واقعی است. اسپرد از دفتر سفارش
 *    زنده (نوبیتکس / TSETMC) خوانده می‌شود.
 */
object Fees {

    // ---- بورس تهران و فرابورس (مصوبه سازمان بورس، پس از کاهش ۲۰٪ مرداد ۱۳۹۹) ----
    /** خرید سهام بورس تهران (کارگزار + بورس + حق نظارت سازمان + سپرده‌گذاری + فناوری). */
    const val IR_BOURSE_BUY = 0.003712

    /** خرید سهام فرابورس (حق نظارت سازمان کمتر). */
    const val IR_FARA_BUY = 0.003632

    /** فروش سهام: حدود ۰٫۳۸٪ کارمزد ارکان + ۰٫۵٪ مالیات مقطوع نقل‌وانتقال (ماده ۱۴۳ ق.م.م). */
    const val IR_SELL = 0.0088

    /** پله‌های کارمزد نوبیتکس؛ ربات سفارش «بازار» در بازار تومانی ثبت می‌کند، پس کارمزد «تیکر تومانی» اعمال می‌شود. */
    data class Tier(val name: String, val volume: String, val takerIrt: Double, val takerUsdt: Double)

    val NOBITEX_TIERS = listOf(
        Tier("پایه", "کمتر از ۱۰۰ میلیون تومان", 0.0025, 0.0013),
        Tier("VIP1", "۱۰۰ تا ۳۰۰ میلیون تومان", 0.0020, 0.0012),
        Tier("VIP2", "۳۰۰ میلیون تا ۱ میلیارد تومان", 0.0019, 0.0011),
        Tier("VIP3", "۱ تا ۵ میلیارد تومان", 0.00175, 0.0010),
        Tier("VIP4", "۵ تا ۲۰ میلیارد تومان", 0.00155, 0.0010),
        Tier("VIP5", "۲۰ تا ۸۰ میلیارد تومان", 0.00145, 0.00095),
        Tier("VIP6", "بیش از ۸۰ میلیارد تومان", 0.00135, 0.0009)
    )

    /** اسپرد پیش‌فرض (کل، نه نصف) وقتی دفتر سفارش در دسترس نیست. محافظه‌کارانه. */
    const val DEFAULT_SPREAD_CRYPTO = 0.002
    const val DEFAULT_SPREAD_IR = 0.005

    /** سقف نصف اسپرد برای جلوگیری از داده خراب (۲٪). */
    const val MAX_HALF_SPREAD = 0.02

    fun tier(settings: AppSettings): Tier = NOBITEX_TIERS[settings.nobitexFeeTier.coerceIn(0, NOBITEX_TIERS.size - 1)]

    /** کارمزد یک طرف معامله (کسر از ارزش معامله، مثلاً ۰٫۰۰۲۵ = ۰٫۲۵٪). */
    fun commission(market: MarketKind, buy: Boolean, settings: AppSettings, farabourse: Boolean = false): Double =
        when (market) {
            MarketKind.IR_STOCK -> if (!buy) IR_SELL else if (farabourse) IR_FARA_BUY else IR_BOURSE_BUY
            MarketKind.CRYPTO -> tier(settings).takerIrt
            MarketKind.FX, MarketKind.METAL -> settings.feePct
        }

    fun commission(asset: Asset?, kind: MarketKind?, buy: Boolean, settings: AppSettings): Double {
        val m = asset?.market ?: kind ?: MarketKind.CRYPTO
        return commission(m, buy, settings, asset?.farabourse == true)
    }

    /**
     * نصف اسپرد (کسر) از روی بهترین قیمت خرید/فروش.
     * @return null اگر داده معتبر نباشد.
     */
    fun halfSpreadOf(bid: Double?, ask: Double?): Double? {
        if (bid == null || ask == null || bid <= 0 || ask <= 0 || ask < bid) return null
        return ((ask - bid) / (ask + bid)).coerceIn(0.0, MAX_HALF_SPREAD)
    }

    /** یک ردیف جدول کارمزد برای نمایش. */
    data class Line(val market: MarketKind, val buyPct: Double, val sellPct: Double, val detail: String, val real: Boolean)

    fun table(settings: AppSettings): List<Line> {
        val t = tier(settings)
        return listOf(
            Line(
                MarketKind.CRYPTO, t.takerIrt * 100, t.takerIrt * 100,
                "نوبیتکس، بازار تومانی، سفارش بازار (تیکر) — پله «" + t.name + "» (" + t.volume + " معامله در ۳۰ روز) + اسپرد زنده دفتر سفارش",
                true
            ),
            Line(
                MarketKind.IR_STOCK, IR_BOURSE_BUY * 100, IR_SELL * 100,
                "مصوبه سازمان بورس: خرید ۰٫۳۷۱۲٪ (فرابورس ۰٫۳۶۳۲٪)، فروش ۰٫۸۸٪ شامل ۰٫۵٪ مالیات + اسپرد زنده سرخط سفارش‌ها",
                true
            ),
            Line(
                MarketKind.FX, settings.feePct * 100, settings.feePct * 100,
                "فرضی — فارکس از داخل برنامه به هیچ کارگزاری وصل نیست و نرخ‌ها نرخ مرجع روزانه بانک مرکزی اروپا هستند",
                false
            )
        )
    }
}
