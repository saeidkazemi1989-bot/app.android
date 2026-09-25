package com.saeidkazemi.trader.data.remote

import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.AppSettings
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.data.model.PricePoint
import java.util.concurrent.ConcurrentHashMap

class RefreshResult(val assets: List<Asset>, val notes: List<String>)

/**
 * سرویس تجمیعی داده بازار: ارز دیجیتال، ارز خارجی، طلا و بورس تهران را از منابع مختلف گرفته،
 * خطای هر منبع را جداگانه مدیریت می‌کند و کش تاریخچه قیمت را نگه می‌دارد.
 */
class MarketDataService {

    private val cryptoSource = CryptoSource()
    private val fxSource = FxSource()
    private val goldSource = GoldSource()
    private val erSource = ErSource()
    private val iranSource = IranStockSource()

    @Volatile
    private var lastAssets: List<Asset> = emptyList()

    @Volatile
    private var lastUsdIrr: Double? = null

    @Volatile
    private var lastRefreshTs: Long = 0L

    private val historyCache = ConcurrentHashMap<String, List<PricePoint>>()

    val lastRefresh: Long get() = lastRefreshTs

    fun cachedAssets(): List<Asset> = lastAssets

    /** نرخ دلار به ریال؛ اگر منبع زنده در دسترس نباشد از نرخ پشتیبان تنظیمات استفاده می‌شود. */
    fun usdIrr(settings: AppSettings): Double = lastUsdIrr ?: settings.usdIrrFallback

    fun rateIsFallback(): Boolean = lastUsdIrr == null

    /** قیمت دلاری هر دارایی (دارایی‌های ریالی با نرخ روز تبدیل می‌شوند). */
    fun usdPriceOf(asset: Asset, settings: AppSettings): Double {
        return when (asset.baseCurrency) {
            "IRR" -> asset.price / usdIrr(settings)
            else -> asset.price
        }
    }

    fun tomanPerUsd(settings: AppSettings): Double = usdIrr(settings) / 10.0

    suspend fun refresh(settings: AppSettings): RefreshResult {
        val notes = mutableListOf<String>()

        val cryptoAssets = try {
            cryptoSource.topAssets(40)
        } catch (e: Exception) {
            notes.add("داده ارز دیجیتال در دسترس نیست (اتصال اینترنت یا محدودیت سرویس).")
            emptyList()
        }

        val fxAssets = try {
            fxSource.assets()
        } catch (e: Exception) {
            notes.add("داده ارز خارجی در دسترس نیست.")
            emptyList()
        }

        val iranAssets = try {
            iranSource.assets()
        } catch (e: Exception) {
            notes.add("داده بورس تهران در دسترس نیست.")
            emptyList()
        }
        if (iranAssets.all { it.isSimulated } && iranAssets.isNotEmpty()) {
            notes.add("داده بورس تهران در دسترس نبود؛ قیمت‌های شبیه‌سازی‌شده (با برچسب) نمایش داده می‌شود.")
        }

        try {
            val irr = erSource.usdIrr()
            if (irr != null) lastUsdIrr = irr else if (lastUsdIrr == null) notes.add("نرخ دلار/ریال زنده در دسترس نیست؛ نرخ پشتیبان استفاده می‌شود.")
        } catch (e: Exception) {
            if (lastUsdIrr == null) notes.add("نرخ دلار/ریال زنده در دسترس نیست؛ نرخ پشتیبان استفاده می‌شود.")
        }

        val list = mutableListOf<Asset>()
        list.addAll(cryptoAssets)
        list.addAll(fxAssets)

        try {
            val xau = goldSource.xauUsd()
            val now = System.currentTimeMillis()
            if (xau != null) {
                list.add(
                    Asset(
                        id = "metal:XAU",
                        symbol = "XAU",
                        name = "طلای جهانی (هر اونس)",
                        market = MarketKind.METAL,
                        baseCurrency = "USD",
                        price = xau,
                        changePct24h = null,
                        updatedAt = now,
                        isDisplayOnly = true
                    )
                )
                val irrRate = lastUsdIrr ?: settings.usdIrrFallback
                val gold18GramIrr = xau * 0.75 / 31.1034768 * irrRate
                list.add(
                    Asset(
                        id = "metal:GOLD18",
                        symbol = "طلای ۱۸عیار",
                        name = "طلای ۱۸عیار (هر گرم - محاسباتی)",
                        market = MarketKind.METAL,
                        baseCurrency = "IRR",
                        price = gold18GramIrr,
                        changePct24h = null,
                        updatedAt = now,
                        isDisplayOnly = true,
                        isSimulated = lastUsdIrr == null
                    )
                )
            } else {
                notes.add("قیمت جهانی طلا در دسترس نیست.")
            }
        } catch (e: Exception) {
            notes.add("قیمت جهانی طلا در دسترس نیست.")
        }

        list.addAll(iranAssets)

        lastAssets = list
        lastRefreshTs = System.currentTimeMillis()
        return RefreshResult(list, notes)
    }

    /** تاریخچه قیمت برای تحلیل؛ نتیجه در طول عمر فرایند کش می‌شود. */
    suspend fun historyFor(asset: Asset): List<PricePoint> {
        historyCache[asset.id]?.let { return it }
        val h = when (asset.market) {
            MarketKind.CRYPTO -> if (asset.isDisplayOnly) emptyList() else try {
                cryptoSource.history(asset.id, 90)
            } catch (e: Exception) {
                emptyList()
            }

            MarketKind.FX -> try {
                fxSource.history(asset.id.removePrefix("fx:"), 90)
            } catch (e: Exception) {
                emptyList()
            }

            MarketKind.IR_STOCK -> iranSource.history(asset)

            MarketKind.METAL -> emptyList()
        }
        if (h.isNotEmpty()) historyCache[asset.id] = h
        return h
    }
}
