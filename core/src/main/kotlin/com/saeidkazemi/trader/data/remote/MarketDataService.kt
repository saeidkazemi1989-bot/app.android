package com.saeidkazemi.trader.data.remote

import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.AppSettings
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.data.model.PricePoint
import java.util.concurrent.ConcurrentHashMap

class RefreshResult(
    val assets: List<Asset>,
    val notes: List<String>,
    /** گوشی در این دور اصلاً به اینترنت دسترسی نداشت (هیچ سروری پیدا نشد). */
    val offline: Boolean = false
)

/**
 * سرویس تجمیعی داده بازار: ارز دیجیتال، ارز خارجی، طلا و بورس تهران را از منابع مختلف گرفته،
 * خطای هر منبع را جداگانه مدیریت می‌کند و کش تاریخچه قیمت را نگه می‌دارد.
 */
class MarketDataService {

    private val cryptoSource = CryptoSource()
    private val fxSource = FxSource()
    private val goldSource = GoldSource()
    @Volatile private var goldFailAt = 0L

    private suspend fun isOffline(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        OFFLINE_PROBE_HOSTS.none { h ->
            try {
                java.net.InetAddress.getByName(h)
                true
            } catch (_: java.net.UnknownHostException) {
                false
            } catch (_: Exception) {
                true // خطای دیگر = نامعلوم؛ قطع حساب نمی‌شود
            }
        }
    }

    companion object {
        val OFFLINE_PROBE_HOSTS = listOf("apiv2.nobitex.ir", "cdn.tsetmc.com", "api.coingecko.com")
        const val OFFLINE_NOTE = "اینترنت دستگاه در این دور قطع بود (هیچ سروری پیدا نشد)؛ ربات منتظر وصل شدن اینترنت می‌ماند و خرید و فروشی انجام نمی‌شود."
    }

    /** منبع فعلی فهرست ارزهای دیجیتال (CoinGecko یا نوبیتکس). */
    val cryptoListSource: String get() = cryptoSource.lastListSource
    private val erSource = ErSource()
    private val iranSource = IranStockSource()
    private val rialSource = NobitexRialSource()
    val insights = InsightSource()

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

    /** حقیقی/حقوقی امروز یک سهم. */
    fun iranFlow(assetId: String): IranStockSource.ClientFlow? = iranSource.flowOf(assetId)

    /** تاریخچه ورود/خروج پول حقیقی یک سهم (جدیدترین اول). */
    fun iranFlowHistory(assetId: String): List<IranStockSource.ClientFlow> = iranSource.flowHistoryOf(assetId)

    /** آمار کل بازار سهام (سهم نمادهای مثبت، ورود پول حقیقی کل، P/E گروه‌ها). */
    val iranStats: IranStockSource.MarketStats? get() = iranSource.stats

    val iranFlowError: String? get() = iranSource.flowError

    /** تاریخچه کش‌شده (بدون درخواست شبکه). */
    fun cachedHistory(assetId: String): List<PricePoint>? = historyCache[assetId]?.points

    /** آیا تاریخچه تازه این دارایی در کش هست (بدون درخواست شبکه)؟ */
    fun hasFreshHistory(asset: Asset): Boolean {
        val c = historyCache[asset.id] ?: return false
        val ttl = if (c.points.isEmpty()) 10 * 60_000L else 3 * 3_600_000L
        return System.currentTimeMillis() - c.ts < ttl
    }

    suspend fun refresh(
        settings: AppSettings,
        mustInclude: Set<String> = emptySet(),
        heldCrypto: List<Triple<String, String, String>> = emptyList()
    ): RefreshResult {
        // اگر هیچ سروری (نوبیتکس، بورس، CoinGecko) حتی نامش پیدا نشود، اینترنت دستگاه قطع است:
        // به‌جای ده خطای جداگانه یک یادداشت روشن، و این دور بدون درخواست شبکه رد می‌شود.
        if (isOffline()) {
            return RefreshResult(emptyList(), listOf(OFFLINE_NOTE), offline = true)
        }
        val notes = mutableListOf<String>()

        val cryptoAssets = (try {
            cryptoSource.topAssets(40)
        } catch (e: Exception) {
            notes.add("داده ارز دیجیتال در دسترس نیست (CoinGecko و نوبیتکس هر دو پاسخ ندادند: " + (e.message ?: e.javaClass.simpleName).take(80) + ").")
            emptyList<Asset>()
        }).toMutableList()
        // خریدهای باز ارز دیجیتال باید همیشه قیمت داشته باشند تا حد ضرر و حد سودشان مدیریت شود
        val missingHeld = heldCrypto.filter { h -> cryptoAssets.none { it.id == h.first } }
        if (missingHeld.isNotEmpty()) {
            var got = 0
            for ((id, sym, name) in missingHeld) {
                val a = try { cryptoSource.nobitexAssetFor(id, sym, name) } catch (_: Exception) { null }
                if (a != null) { cryptoAssets.add(a); got++ }
            }
            if (got < missingHeld.size) {
                notes.add("قیمت " + (missingHeld.size - got) + " ارزِ داخل پرتفوی دریافت نشد؛ حد ضرر آن‌ها در این دور بررسی نشد.")
            }
        }

        val fxAssets = try {
            fxSource.assets()
        } catch (e: Exception) {
            if (settings.allocationPct(MarketKind.FX) > 0) notes.add("داده ارز خارجی در دسترس نیست.")
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
            if (scan.goldFunds.isNotEmpty()) {
                notes.add("صندوق‌های طلای بورسی: " + scan.goldFunds.size + " صندوق پرمعامله وارد تحلیل شد.")
            }
            scan.assets + scan.goldFunds
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

        if (cryptoAssets.isNotEmpty() && cryptoSource.lastListSource == "نوبیتکس") {
            notes.add("✓ قیمت ارزهای دیجیتال از نوبیتکس (منبع جایگزین) دریافت شد؛ ارزها عادی تحلیل و مدیریت می‌شوند.")
        }

        try {
            // قیمت جهانی طلا: gold-api.com؛ اگر نشد، PAXG نوبیتکس (هر توکن = یک اونس طلا)
            val now0 = System.currentTimeMillis()
            var xau: Double? = if (now0 - goldFailAt > 20 * 60_000L) goldSource.xauUsd() else null
            if (xau == null) {
                if (now0 - goldFailAt > 20 * 60_000L) goldFailAt = now0
                xau = try { cryptoSource.nobitexUsdtPrice("paxg") } catch (_: Exception) { null }
            }
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

        try {
            list.addAll(rialSource.assets())
        } catch (e: Exception) {
            notes.add("قیمت دلار (تتر) و طلای PAXG از نوبیتکس در دسترس نیست.")
        }

        list.addAll(iranAssets)

        lastAssets = list
        lastRefreshTs = System.currentTimeMillis()
        return RefreshResult(list, notes)
    }

    /** تاریخچه طولانی (تا حدود ۲ سال) برای بک‌تست؛ بدون کش. */
    suspend fun longHistory(asset: Asset, days: Int = 730): List<PricePoint> {
        if (asset.isDisplayOnly || asset.isSimulated) return emptyList()
        return when (asset.market) {
            MarketKind.CRYPTO -> cryptoSource.longHistory(asset.symbol, days)
            MarketKind.IR_STOCK -> iranSource.history(asset, minOf(days, 600))
            MarketKind.METAL -> when {
                asset.id.startsWith("nbx:") -> rialSource.history(asset, days)
                asset.id.startsWith("ir:") -> iranSource.history(asset, minOf(days, 600))
                else -> emptyList()
            }
            MarketKind.FX -> emptyList()
        }
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
                MarketKind.METAL -> when {
                    asset.isDisplayOnly || asset.isSimulated -> emptyList()
                    asset.id.startsWith("nbx:") -> rialSource.history(asset)
                    asset.id.startsWith("ir:") -> iranSource.history(asset)
                    else -> emptyList()
                }
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
