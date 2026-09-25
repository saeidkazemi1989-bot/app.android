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

    private class CachedHistory(val points: List<PricePoint>, val ts: Long)

    private val historyCache = ConcurrentHashMap<String, CachedHistory>()

    /** آخرین خطای دریافت تاریخچه هر دارایی (برای عیب‌یابی). */
    val historyErrors = ConcurrentHashMap<String, String>()

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

    /** آمار آخرین پویش کل بازار بورس (برای نمایش). */
    @Volatile
    var lastIranScan: IranStockSource.ScanResult? = null
        private set

    val iranError: String? get() = iranSource.lastError

    fun iranQuote(assetId: String): IranStockSource.Quote? = iranSource.quote(assetId)

    fun iranAdjustment(assetId: String): Pair<Int, Double>? = iranSource.adjustmentFor(assetId)

    /** آیا تاریخچه تازه این دارایی در کش هست (بدون درخواست شبکه)؟ */
    fun hasFreshHistory(asset: Asset): Boolean {
        val c = historyCache[asset.id] ?: return false
        val ttl = if (c.points.isEmpty()) 10 * 60_000L else 3 * 3_600_000L
        return System.currentTimeMillis() - c.ts < ttl
    }

    suspend fun refresh(settings: AppSettings, mustInclude: Set<String> = emptySet()): RefreshResult {
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
            val scan = iranSource.scan(mustInclude)
            lastIranScan = scan
            if (scan.live) {
                notes.add(
                    "بورس و فرابورس: " + scan.stocks + " سهم پویش شد؛ " + scan.liquid +
                        " سهم با ارزش معاملات بالای ۱ میلیارد تومان وارد تحلیل شد" +
                        (if (scan.buyQueues + scan.sellQueues > 0) " (صف خرید: " + scan.buyQueues + "، صف فروش: " + scan.sellQueues + ")" else "") + "."
                )
                if (iranSource.lastError != null) notes.add("دیده‌بان بازار به‌روز نشد؛ آخرین داده دریافتی استفاده می‌شود.")
            } else {
                notes.add("داده بورس تهران (TSETMC) در دسترس نبود؛ ۱۰ نماد شاخص با قیمت شبیه‌سازی‌شده (با برچسب) نمایش داده می‌شود و روی آن‌ها معامله خودکار انجام نمی‌شود.")
            }
            scan.assets
        } catch (e: Exception) {
            notes.add("داده بورس تهران در دسترس نیست.")
            emptyList()
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

    /**
     * تاریخچه قیمت برای تحلیل. نتیجه موفق ۳ ساعت و شکست ۱۰ دقیقه کش می‌شود
     * (تا برنامه‌ای که روزها روشن است داده کهنه نداشته باشد و منبع خراب هر دقیقه دوباره صدا زده نشود).
     */
    suspend fun historyFor(asset: Asset): List<PricePoint> {
        val now = System.currentTimeMillis()
        historyCache[asset.id]?.let { c ->
            val ttl = if (c.points.isEmpty()) 10 * 60_000L else 3 * 3_600_000L
            if (now - c.ts < ttl) return c.points
        }
        val h: List<PricePoint> = try {
            when (asset.market) {
                MarketKind.CRYPTO -> if (asset.isDisplayOnly) emptyList() else cryptoSource.history(asset.id, asset.symbol)
                MarketKind.FX -> fxSource.history(asset.id.removePrefix("fx:"), 90)
                MarketKind.IR_STOCK -> iranSource.history(asset)
                MarketKind.METAL -> emptyList()
            }
        } catch (e: Exception) {
            historyErrors[asset.id] = e.message ?: e.toString()
            emptyList()
        }
        if (h.isNotEmpty()) historyErrors.remove(asset.id)
        historyCache[asset.id] = CachedHistory(h, now)
        return h
    }
}
