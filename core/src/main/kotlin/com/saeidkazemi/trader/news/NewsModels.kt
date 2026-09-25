package com.saeidkazemi.trader.news

/** منبع خبر. */
enum class NewsSourceKind(val faTitle: String) {
    CODAL("کدال"),
    CODAL_TELEGRAM("کدال (کانال)"),
    GOOGLE_NEWS("اخبار"),
}

/** یک خبر/اطلاعیه مرتبط با یک دارایی، همراه با تحلیل احساس (مثبت/منفی). */
data class NewsItem(
    val title: String,
    val summary: String,
    val url: String,
    val publisher: String,
    val publishedAt: Long?,
    val kind: NewsSourceKind,
    /** احساس خبر بین ۱- (خیلی منفی) تا ۱+ (خیلی مثبت). */
    val sentiment: Double,
    /** کلیدواژه‌هایی که باعث امتیاز شدند (برای شفافیت). */
    val matched: List<String>,
    val lang: String
) {
    val isOfficial: Boolean get() = kind == NewsSourceKind.CODAL || kind == NewsSourceKind.CODAL_TELEGRAM

    val sentimentLabel: String
        get() = when {
            sentiment >= 0.35 -> "مثبت"
            sentiment >= 0.1 -> "کمی مثبت"
            sentiment <= -0.35 -> "منفی"
            sentiment <= -0.1 -> "کمی منفی"
            else -> "خنثی"
        }
}

/** جمع‌بندی اخبار یک دارایی که در امتیاز سیگنال اثر داده می‌شود. */
data class NewsDigest(
    val assetId: String,
    val items: List<NewsItem>,
    /** برآیند وزن‌دار احساس اخبار (۱- تا ۱+). */
    val score: Double,
    /** میزان اطمینان (۰ تا ۱) بر اساس تعداد و تازگی اخبار. */
    val confidence: Double,
    /** اثر نهایی روی امتیاز سیگنال (مثلاً ۸+ یا ۱۰-). */
    val adjustment: Int,
    /** اگر خبر منفی مهم و تازه‌ای باشد، خرید جدید متوقف می‌شود. */
    val blockBuy: Boolean,
    val blockReason: String?,
    val fetchedAt: Long,
    val sourcesOk: List<String>,
    val sourcesFailed: List<String>
) {
    val available: Boolean get() = items.isNotEmpty()

    val label: String
        get() = when {
            items.isEmpty() -> "خبری یافت نشد"
            score >= 0.25 -> "برآیند اخبار مثبت"
            score <= -0.25 -> "برآیند اخبار منفی"
            else -> "برآیند اخبار خنثی"
        }

    companion object {
        fun empty(assetId: String, failed: List<String> = emptyList()) = NewsDigest(
            assetId = assetId,
            items = emptyList(),
            score = 0.0,
            confidence = 0.0,
            adjustment = 0,
            blockBuy = false,
            blockReason = null,
            fetchedAt = System.currentTimeMillis(),
            sourcesOk = emptyList(),
            sourcesFailed = failed
        )
    }
}
