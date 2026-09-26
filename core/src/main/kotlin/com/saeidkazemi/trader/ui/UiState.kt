package com.saeidkazemi.trader.ui

import com.saeidkazemi.trader.data.model.AccountState
import com.saeidkazemi.trader.data.model.AppSettings
import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.CycleReport
import com.saeidkazemi.trader.data.model.PricePoint
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.data.model.Signal
import com.saeidkazemi.trader.news.NewsDigest
import com.saeidkazemi.trader.news.NewsItem

/** جزئیات یک دارایی برای صفحه جزئیات. */
data class AssetDetail(
    val asset: Asset,
    val history: List<PricePoint>,
    val signal: Signal?,
    val usdPrice: Double,
    val held: Boolean,
    val news: NewsDigest? = null,
    val newsLoading: Boolean = false,
    /** پیش‌بینی ۷ روز آینده (به دلار). */
    val forecast: com.saeidkazemi.trader.analysis.Forecast.Result? = null
)

/** یک ردیف در صفحه اخبار. */
data class NewsFeedEntry(
    val assetId: String,
    val symbol: String,
    val assetName: String,
    val market: MarketKind,
    val item: NewsItem
)

/** اطلاعات پلتفرم (اندروید یا ویندوز) برای نمایش گزینه‌های مخصوص هر نسخه. */
data class PlatformInfo(
    val isDesktop: Boolean = false,
    val name: String = "اندروید",
    val autostartSupported: Boolean = false,
    val autostartEnabled: Boolean = false,
    val dataLocation: String = "حافظه داخلی گوشی",
    /** معافیت از بهینه‌سازی باتری (فقط اندروید؛ null یعنی موضوعیت ندارد). */
    val batteryOptimizationIgnored: Boolean? = null,
    /** لرزش پشتیبانی می‌شود (گوشی). */
    val vibrationSupported: Boolean = false,
    /** نام سازنده گوشی (برای راهنمای اجرای خودکار شیائومی/سامسونگ/هواوی…). */
    val manufacturer: String = ""
)

/** وضعیت سرمایه اختصاصی یک بازار. */
data class SleeveStat(
    val market: MarketKind,
    val allocationPct: Double,
    val capitalUsd: Double,
    val cashUsd: Double,
    val positionsUsd: Double,
    val positions: Int,
    val realizedUsd: Double,
    val riskLevel: String
) {
    val equityUsd: Double get() = cashUsd + positionsUsd
    val returnUsd: Double get() = equityUsd - capitalUsd
    val returnPct: Double get() = if (capitalUsd > 0) returnUsd / capitalUsd * 100 else 0.0
}

data class UiState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val notes: List<String> = emptyList(),
    val assets: List<Asset> = emptyList(),
    val signals: List<Signal> = emptyList(),
    val account: AccountState = AccountState(),
    val settings: AppSettings = AppSettings(),
    val priceMap: Map<String, Double> = emptyMap(),
    val usdIrr: Double = 900000.0,
    val rateIsFallback: Boolean = true,
    val lastCycle: CycleReport? = null,
    val detail: AssetDetail? = null,
    val toastMessage: String? = null,
    val newsFeed: List<NewsFeedEntry> = emptyList(),
    val newsDigests: Map<String, NewsDigest> = emptyMap(),
    val newsRefreshing: Boolean = false,
    val platform: PlatformInfo = PlatformInfo(),
    /** روند و پیش‌بینی هر موقعیت باز (کلید: شناسه دارایی). */
    val outlooks: Map<String, com.saeidkazemi.trader.analysis.PositionOutlook> = emptyMap(),
    /** ژورنال معاملات (جدیدترین اول). */
    val journal: List<com.saeidkazemi.trader.data.model.JournalEntry> = emptyList(),
    /** پنل سودآوری: نرخ برد، ضریب سود، امید ریاضی، … */
    val perf: com.saeidkazemi.trader.analysis.PerfReport = com.saeidkazemi.trader.analysis.PerfReport(),
    /** روند کلی هر بازار. */
    val marketTrends: List<com.saeidkazemi.trader.analysis.MarketTrendReport> = emptyList()
) {
    val equityUsd: Double
        get() = account.cashUsd + account.positions.sumOf { p ->
            (priceMap[p.assetId] ?: p.avgBuyUsd) * p.qty
        }

    val unrealizedUsd: Double
        get() = account.positions.sumOf { p ->
            val cur = priceMap[p.assetId] ?: p.avgBuyUsd
            (cur - p.avgBuyUsd) * p.qty
        }

    val totalReturnUsd: Double
        get() = equityUsd - account.initialCapitalUsd

    val totalReturnPct: Double
        get() = if (account.initialCapitalUsd > 0) totalReturnUsd / account.initialCapitalUsd * 100.0 else 0.0

    fun heldAssetIds(): Set<String> = account.positions.map { it.assetId }.toSet()

    /** سرمایه، نقد، موقعیت‌ها و بازده هر بازار به‌صورت جداگانه. */
    fun sleeves(): List<SleeveStat> = listOf(MarketKind.CRYPTO, MarketKind.IR_STOCK, MarketKind.FX).map { m ->
        val pos = account.positions.filter { it.market == m }
        SleeveStat(
            market = m,
            allocationPct = settings.allocationPct(m),
            capitalUsd = account.capitalByMarket[m.name] ?: 0.0,
            cashUsd = account.cashByMarket[m.name] ?: 0.0,
            positionsUsd = pos.sumOf { p -> (priceMap[p.assetId] ?: p.avgBuyUsd) * p.qty },
            positions = pos.size,
            realizedUsd = account.realizedByMarket[m.name] ?: 0.0,
            riskLevel = settings.riskFor(m)
        )
    }
}

/** مبلغ پیشنهادی هر خرید در یک بازار (هم‌راستا با RiskManager و سرمایه اختصاصی همان بازار). */
fun suggestedBuyUsd(state: UiState, market: MarketKind): Double {
    val sleeve = state.sleeves().firstOrNull { it.market == market }
    val plan = com.saeidkazemi.trader.analysis.RiskManager().plan(state.settings.riskFor(market), market)
    if (sleeve == null) return state.equityUsd * plan.positionPct
    return minOf(sleeve.equityUsd * plan.positionPct, sleeve.cashUsd).coerceAtLeast(0.0)
}
