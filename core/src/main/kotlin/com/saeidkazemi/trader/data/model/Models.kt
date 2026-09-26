package com.saeidkazemi.trader.data.model

/** انواع بازارها که اپ پوشش می‌دهد. */
enum class MarketKind(val faTitle: String) {
    CRYPTO("ارز دیجیتال"),
    IR_STOCK("بورس تهران"),
    FX("ارز خارجی"),
    METAL("فلزات گرانبها")
}

/** یک دارایی با آخرین قیمت. قیمت‌ها به ارز پایه خود دارایی هستند (معمولاً USD یا IRR). */
data class Asset(
    val id: String,
    val symbol: String,
    val name: String,
    val market: MarketKind,
    val baseCurrency: String,
    val price: Double,
    val changePct24h: Double?,
    val updatedAt: Long,
    val isSimulated: Boolean = false,
    val isDisplayOnly: Boolean = false,
    val rank: Int? = null,
    val nobitexSymbol: String? = null,
    /** بورس تهران: نماد در صف خرید است (خرید عملاً ممکن نیست). */
    val buyQueue: Boolean = false,
    /** بورس تهران: نماد در صف فروش است (فروش عملاً ممکن نیست). */
    val sellQueue: Boolean = false,
    /** ارزش معاملات امروز به ارز پایه (برای سهام: ریال). */
    val tradeValue: Double? = null
)

/** یک نقطه از تاریخچه قیمت (زمان به میلی‌ثانیه). */
data class PricePoint(val t: Long, val price: Double, val volume: Double = 0.0)

/** یک عامل تحلیل تخصصی (مثل «ورود پول حقیقی» یا «شاخص ترس و طمع») با اثرش روی امتیاز. */
data class ProFactor(
    val title: String,
    val value: String,
    val impact: Int,
    val note: String = ""
)

enum class Action(val faTitle: String) { BUY("خرید"), SELL("فروش"), HOLD("نگهداری") }

/** نتایج اندیکاتورهای یک دارایی. */
data class Metrics(
    val rsi: Double?,
    val macdHist: Double?,
    val trendPct: Double?,
    val momentum7: Double?,
    val momentum30: Double?,
    val volatility: Double?
)

/** سیگنال تولیدشده توسط موتور تحلیل. */
data class Signal(
    val assetId: String,
    val symbol: String,
    val name: String,
    val market: MarketKind,
    val action: Action,
    val score: Int,
    val reasons: List<String>,
    val metrics: Metrics,
    val isSimulated: Boolean,
    val createdAt: Long,
    /** امتیاز فقط تکنیکال (قبل از اثر اخبار). */
    val technicalScore: Int = score,
    /** اثر اخبار روی امتیاز (مثبت یا منفی). */
    val newsAdj: Int = 0,
    val newsCount: Int = 0,
    val newsLabel: String? = null,
    /** خرید به‌خاطر خبر منفی مهم متوقف شده است. */
    val newsBlocked: Boolean = false,
    /** اثر تحلیل تخصصی (جریان پول، حجم، ارزش‌گذاری، وضعیت کل بازار، …). */
    val proAdj: Int = 0,
    val proFactors: List<ProFactor> = emptyList(),
    /** خرید به‌خاطر شرایط تخصصی (مثل خروج سنگین پول حقیقی یا بازار نزولی) متوقف شده است. */
    val proBlocked: Boolean = false,
    val proBlockReason: String? = null
)

/** موقعیت باز در پرتفوی. حسابداری همه موقعیت‌ها بر مبنای دلار است. */
data class Position(
    val assetId: String,
    val symbol: String,
    val name: String,
    val market: MarketKind,
    val nativeCurrency: String,
    val qty: Double,
    val avgBuyUsd: Double,
    val openedAt: Long,
    val stopLossUsd: Double,
    val takeProfitUsd: Double,
    /** آخرین روز (yyyymmdd) که بابت افزایش سرمایه/سود نقدی تعدیل شد. */
    val adjDay: Int? = null,
    /** بالاترین قیمت از زمان خرید (برای حد ضرر متحرک)؛ ۰ یعنی هنوز ثبت نشده. */
    val peakUsd: Double = 0.0,
    /** فاصله حد ضرر متحرک از قله (کسری، مثل ۰٫۰۸)؛ ۰ یعنی غیرفعال. */
    val trailPct: Double = 0.0,
    /** درصد سود خالصی که قفل شده (حد ضرر حداقل روی آن است)؛ ۰ یعنی هنوز قفل نشده. */
    val profitLockedPct: Double = 0.0
)

/** یک معامله انجام‌شده (دمو یا واقعی). */
data class Trade(
    val id: String,
    val ts: Long,
    val assetId: String,
    val symbol: String,
    val side: String,
    val qty: Double,
    val priceUsd: Double,
    val usdValue: Double,
    val feeUsd: Double,
    val reason: String,
    val mode: String
)

/** وضعیت حساب (لجر داخلی اپ). */
data class AccountState(
    val cashUsd: Double = 0.0,
    val initialCapitalUsd: Double = 0.0,
    val realizedPnlUsd: Double = 0.0,
    val positions: List<Position> = emptyList(),
    val trades: List<Trade> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    /** نقد اختصاصی هر بازار (کلید: نام MarketKind). هر بازار فقط با سرمایه خودش معامله می‌کند. */
    val cashByMarket: Map<String, Double> = emptyMap(),
    /** سرمایه اولیه اختصاص‌یافته به هر بازار. */
    val capitalByMarket: Map<String, Double> = emptyMap(),
    /** سود/زیان تحقق‌یافته هر بازار. */
    val realizedByMarket: Map<String, Double> = emptyMap()
)

/** تنظیمات کلی اپ. معامله خودکار به‌صورت پیش‌فرض روشن است (در حالت دمو، بدون تأیید موردی). */
data class AppSettings(
    val autoTrade: Boolean = true,
    val realTrading: Boolean = false,
    val riskLevel: String = "MED",
    val capitalUsd: Double = 10000.0,
    val feePct: Double = 0.002,
    val buyThreshold: Int = 70,
    val sellThreshold: Int = 45,
    val nobitexToken: String = "",
    val usdIrrFallback: Double = 900000.0,
    /** بررسی اخبار (کدال، اخبار فارسی و جهانی) و اثر دادن آن در تصمیم خرید/فروش. */
    val newsEnabled: Boolean = true,
    /** تحلیل تخصصی (جریان پول حقیقی/حقوقی، حجم، P/E، ترس و طمع، دفتر سفارش، وضعیت کل بازار). */
    val proAnalysis: Boolean = true,
    /** درصد سرمایه هر بازار (کلید: نام MarketKind). جمع باید ۱۰۰ باشد. */
    val allocations: Map<String, Double> = mapOf("CRYPTO" to 50.0, "IR_STOCK" to 40.0, "FX" to 10.0),
    /** سطح ریسک جداگانه هر بازار (LOW / MED / HIGH). */
    val marketRisk: Map<String, String> = mapOf("CRYPTO" to "MED", "IR_STOCK" to "MED", "FX" to "LOW"),
    /** هشدار صوتی هنگام شروع و پایان هر معامله. */
    val soundAlerts: Boolean = true,
    /** لرزش گوشی پس از پایان معامله. */
    val vibrateAlerts: Boolean = true,
    /** پخش صدا حتی وقتی گوشی روی بی‌صدا است (از کانال زنگ هشدار). */
    val loudAlerts: Boolean = false,
    /** قفل سود: وقتی سود خالص به «آستانه» رسید، حد ضرر طوری بالا می‌رود که حداقل «سود حفظ‌شده» بماند. */
    val profitLock: Boolean = true,
    /** آستانه فعال شدن قفل سود (درصد سود خالص پس از کارمزد). */
    val profitLockTriggerPct: Double = 10.0,
    /** سودی که پس از فعال شدن قفل حفظ می‌شود (درصد خالص؛ حداکثر برابر آستانه). */
    val profitLockKeepPct: Double = 10.0
) {
    fun allocationPct(m: MarketKind): Double = allocations[m.name] ?: 0.0
    fun riskFor(m: MarketKind): String = marketRisk[m.name] ?: riskLevel
}

/** رویدادهای معامله برای هشدار صوتی/لرزشی و اعلان. */
sealed class TradeEvent {
    abstract val symbol: String
    abstract val market: MarketKind
    abstract val side: String

    /** موتور تصمیم به معامله گرفت و در حال اجراست (صدای اول). */
    data class Starting(
        override val symbol: String,
        override val market: MarketKind,
        override val side: String,
        val amountUsd: Double,
        val reason: String
    ) : TradeEvent()

    /** معامله تمام شد (صدای دوم + لرزش). */
    data class Completed(
        override val symbol: String,
        override val market: MarketKind,
        override val side: String,
        val success: Boolean,
        val message: String,
        val pnlUsd: Double? = null
    ) : TradeEvent()
}

/** گزارش یک دور بررسی بازار توسط موتور. */
data class CycleReport(
    val ts: Long,
    val trigger: String,
    val buys: List<String>,
    val sells: List<String>,
    val notes: List<String>,
    val auto: Boolean
) {
    val tradesCount: Int get() = buys.size + sells.size

    fun summary(): String {
        val parts = mutableListOf<String>()
        if (buys.isNotEmpty()) parts.add("${buys.size} خرید")
        if (sells.isNotEmpty()) parts.add("${sells.size} فروش")
        return if (parts.isEmpty()) "معامله‌ای انجام نشد" else parts.joinToString("، ")
    }
}

/** نتیجه سفارش‌گذاری در صرافی واقعی. */
sealed class OrderResult {
    data class Success(val orderId: String) : OrderResult()
    data class Failure(val message: String) : OrderResult()
    object NotConfigured : OrderResult()
}
