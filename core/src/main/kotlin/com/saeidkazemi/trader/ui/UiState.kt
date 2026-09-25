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
    val newsLoading: Boolean = false
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
    val dataLocation: String = "حافظه داخلی گوشی"
)

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
    val platform: PlatformInfo = PlatformInfo()
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
}

/** درصد اختصاص هر موقعیت بر اساس سطح ریسک (هم‌راستا با RiskManager). */
fun planPositionPct(riskLevel: String): Double = when (riskLevel) {
    "LOW" -> 0.10
    "HIGH" -> 0.22
    else -> 0.15
}
