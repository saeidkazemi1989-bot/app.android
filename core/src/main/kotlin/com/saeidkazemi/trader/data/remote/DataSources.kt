package com.saeidkazemi.trader.data.remote

import com.saeidkazemi.trader.data.model.MarketKind

/** فهرست منابع اطلاعاتی هر بازار (برای ژورنال و صفحه «منابع اطلاعات»). */
object DataSources {

    fun forMarket(m: MarketKind): List<String> = when (m) {
        MarketKind.CRYPTO -> listOf(
            "قیمت لحظه‌ای، تغییر ۲۴ساعته و رتبه: CoinGecko",
            "تاریخچه روزانه قیمت و حجم: نوبیتکس (پشتیبان: CryptoCompare، CoinGecko)",
            "شاخص ترس و طمع کل بازار: alternative.me",
            "دفتر سفارش (فشار خرید/فروش لحظه‌ای): نوبیتکس",
            "روند بیت‌کوین (فیلتر کل بازار): تاریخچه نوبیتکس",
            "اخبار: Google News (انگلیسی)"
        )
        MarketKind.IR_STOCK -> listOf(
            "دیده‌بان کل بازار (قیمت، ارزش معاملات، صف خرید/فروش، EPS و P/E): TSETMC",
            "تاریخچه روزانه تعدیل‌شده (افزایش سرمایه/سود نقدی): TSETMC",
            "حقیقی/حقوقی، قدرت خریدار و ورود پول ۵ روزه: TSETMC",
            "اطلاعیه‌های رسمی: کدال (پشتیبان: کانال تلگرامی کدال۳۶۰)",
            "اخبار فارسی: Google News",
            "نرخ دلار بازار آزاد: قیمت تتر در نوبیتکس (پشتیبان: open.er-api)"
        )
        MarketKind.FX -> listOf(
            "نرخ و تاریخچه روزانه: frankfurter.dev (نرخ مرجع بانک مرکزی اروپا)",
            "اخبار: Google News (انگلیسی)"
        )
        MarketKind.METAL -> listOf("قیمت طلا و نقره: gold-api.com (فقط نمایشی، بدون معامله)")
    }
}
