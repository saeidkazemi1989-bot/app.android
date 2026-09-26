package com.saeidkazemi.trader.trading

import com.saeidkazemi.trader.analysis.ProAnalysis
import com.saeidkazemi.trader.analysis.RiskManager
import com.saeidkazemi.trader.analysis.StrategyEngine
import com.saeidkazemi.trader.data.local.JsonStore
import com.saeidkazemi.trader.data.model.Action
import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.CycleReport
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.data.model.OrderResult
import com.saeidkazemi.trader.data.model.PricePoint
import com.saeidkazemi.trader.data.model.Signal
import com.saeidkazemi.trader.data.model.TradeEvent
import com.saeidkazemi.trader.data.model.AccountState
import com.saeidkazemi.trader.data.remote.IranStockSource
import com.saeidkazemi.trader.data.remote.MarketDataService
import com.saeidkazemi.trader.data.remote.RefreshResult
import com.saeidkazemi.trader.news.NewsDigest
import com.saeidkazemi.trader.news.NewsService
import com.saeidkazemi.trader.data.model.AppSettings
import com.saeidkazemi.trader.util.Format
import com.saeidkazemi.trader.util.IranMarket
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * موتور معاملات: هر دور (سیکل) بازار را به‌روز می‌کند، سیگنال تکنیکال می‌سازد، اخبار و اطلاعیه‌های
 * مرتبط (کدال، اخبار فارسی و جهانی) را برای بهترین فرصت‌ها و دارایی‌های پرتفوی بررسی می‌کند،
 * حد ضرر/حد سود/ضعف سیگنال/خبر منفی مهم را روی موقعیت‌های باز اعمال می‌کند و در حالت خودکار،
 * بهترین فرصت‌ها را طبق بودجه و ریسک تعریف‌شده — بدون نیاز به تأیید موردی — می‌خرد.
 *
 * سرمایه بین بازارها تقسیم شده است و هر بازار (ارز دیجیتال، بورس تهران، ارز خارجی) با سرمایه، سطح
 * ریسک، حد ضرر مبتنی بر نوسان و حد ضرر متحرک خودش مستقل معامله می‌کند.
 *
 * قبل از هر معامله رویداد [TradeEvent.Starting] (صدای هشدار) و پس از انجام آن [TradeEvent.Completed]
 * (صدای دوم و لرزش) منتشر می‌شود.
 */
class TradeEngine(
    private val market: MarketDataService,
    private val strategy: StrategyEngine,
    private val riskManager: RiskManager,
    private val broker: PaperBroker,
    private val nobitex: NobitexClient,
    private val store: JsonStore,
    private val news: NewsService
) {

    companion object {
        /** تعداد بهترین فرصت‌های تکنیکال که در هر دور اخبارشان بررسی می‌شود. */
        const val NEWS_CANDIDATES = 10

        /** حداکثر تاریخچه جدید سهام بورس در هر دور (بقیه در دورهای بعد؛ کل بازار ظرف چند دقیقه پوشش داده می‌شود). */
        const val IR_HISTORY_PER_CYCLE = 60

        /** تعداد درخواست هم‌زمان تاریخچه. */
        const val HISTORY_PARALLELISM = 6

        /** تعداد ارزهای دیجیتال برتر که دفتر سفارششان در هر دور بررسی می‌شود. */
        const val BOOK_CANDIDATES = 8

        /** فاصله هشدار صوتی «شروع معامله» تا اجرای معامله. */
        const val ALERT_LEAD_MS = 1_800L

        fun fearGreedFa(label: String?): String? = when (label?.lowercase()) {
            null, "" -> null
            "extreme fear" -> "ترس شدید"
            "fear" -> "ترس"
            "neutral" -> "خنثی"
            "greed" -> "طمع"
            "extreme greed" -> "طمع شدید"
            else -> label
        }
    }

    private val _events = MutableSharedFlow<TradeEvent>(extraBufferCapacity = 64)

    /** رویدادهای شروع/پایان معامله برای هشدار صوتی، لرزش و اعلان. */
    val events: SharedFlow<TradeEvent> = _events

    private fun planFor(settings: AppSettings, m: MarketKind): RiskManager.Plan =
        riskManager.plan(settings.riskFor(m), m)

    /** ارزش کل (نقد + موقعیت‌ها) بخش اختصاصی یک بازار. */
    private fun sleeveEquity(acc: AccountState, m: MarketKind, prices: Map<String, Double>): Double =
        (acc.cashByMarket[m.name] ?: 0.0) + acc.positions.filter { it.market == m }
            .sumOf { p -> (prices[p.assetId]?.takeIf { it.isFinite() && it > 0 } ?: p.avgBuyUsd) * p.qty }

    private fun flowDay(f: IranStockSource.ClientFlow, price: Double) = ProAnalysis.FlowDay(
        date = f.date,
        buyIVol = f.buyIVol,
        sellIVol = f.sellIVol,
        buyNVol = f.buyNVol,
        buyICount = f.buyICount,
        sellICount = f.sellICount,
        netRealValueIrr = f.realNetValue(price)
    )

    /** ورودی‌های تحلیل تخصصی یک دارایی را از داده‌های کش‌شده می‌سازد. */
    private fun proFor(
        asset: Asset,
        history: List<PricePoint>,
        settings: AppSettings,
        btcHistory: List<PricePoint>,
        bidShare: Double?
    ): ProAnalysis.Result? {
        if (!settings.proAnalysis || asset.isSimulated) return null
        return when (asset.market) {
            MarketKind.IR_STOCK -> {
                val q = market.iranQuote(asset.id)
                val price = q?.price ?: asset.price
                val stats = market.iranStats
                ProAnalysis.iran(
                    history,
                    ProAnalysis.IranInputs(
                        today = market.iranFlow(asset.id)?.let { flowDay(it, price) },
                        history = market.iranFlowHistory(asset.id).map { flowDay(it, price) },
                        pe = q?.pe,
                        eps = q?.eps,
                        sectorPe = q?.sector?.let { stats?.sectorPe?.get(it) },
                        breadth = stats?.breadth,
                        marketRealNetIrr = stats?.realNetIrr,
                        marketValueIrr = stats?.totalValueIrr,
                        usdIrr = market.usdIrr(settings)
                    )
                )
            }

            MarketKind.CRYPTO -> {
                val fg = market.insights.cachedFearGreed()
                ProAnalysis.crypto(
                    history,
                    ProAnalysis.CryptoInputs(
                        isBtc = asset.symbol.equals("BTC", ignoreCase = true),
                        fearGreed = fg?.value,
                        fearGreedLabel = fearGreedFa(fg?.label),
                        btcHistory = btcHistory,
                        bidShare = bidShare
                    )
                )
            }

            else -> null
        }
    }

    /** تحلیل کامل یک دارایی (تکنیکال + اخبار + تخصصی) برای صفحه جزئیات. */
    suspend fun analyzeFull(asset: Asset, history: List<PricePoint>, news: NewsDigest?): Signal? {
        val settings = store.loadSettings()
        val btc = if (asset.market == MarketKind.CRYPTO) {
            market.cachedAssets().firstOrNull { it.symbol == "BTC" && it.market == MarketKind.CRYPTO }
                ?.let { try { market.historyFor(it) } catch (_: Exception) { emptyList() } }.orEmpty()
        } else emptyList()
        if (asset.market == MarketKind.CRYPTO && settings.proAnalysis) {
            try { market.insights.fearGreed() } catch (_: Exception) { }
        }
        val book = if (asset.market == MarketKind.CRYPTO && settings.proAnalysis) {
            try { market.insights.bidShare(asset.symbol) } catch (_: Exception) { null }
        } else null
        val pro = proFor(asset, history, settings, btc, book)
        val th = settings.buyThreshold + planFor(settings, asset.market).buyThresholdDelta
        return strategy.analyze(asset, history, settings, news, pro, th)
    }

    private suspend fun announce(side: String, symbol: String, m: MarketKind, amountUsd: Double, reason: String) {
        _events.tryEmit(TradeEvent.Starting(symbol, m, side, amountUsd, reason))
        delay(ALERT_LEAD_MS)
    }

    private suspend fun announce(side: String, asset: Asset, amountUsd: Double, reason: String) =
        announce(side, asset.symbol, asset.market, amountUsd, reason)

    private fun completed(side: String, symbol: String, m: MarketKind, ok: Boolean, msg: String, pnl: Double? = null) {
        _events.tryEmit(TradeEvent.Completed(symbol, m, side, ok, msg, pnl))
    }

    private fun completed(side: String, asset: Asset, ok: Boolean, msg: String, pnl: Double? = null) =
        completed(side, asset.symbol, asset.market, ok, msg, pnl)

    /** تست هشدار صوتی/لرزشی از صفحه تنظیمات (بدون معامله). */
    suspend fun testAlert() {
        _events.tryEmit(TradeEvent.Starting("TEST", MarketKind.CRYPTO, "TEST", 0.0, "آزمایش هشدار"))
        delay(ALERT_LEAD_MS)
        _events.tryEmit(TradeEvent.Completed("TEST", MarketKind.CRYPTO, "TEST", true, "آزمایش هشدار انجام شد"))
    }

    /** کارمزد واقعی هر بازار: سهام ایران کارمزد و مالیات خودش را دارد؛ بقیه طبق تنظیمات. */
    private fun feeFor(asset: Asset?, kind: MarketKind?, buy: Boolean, settings: AppSettings): Double =
        if ((asset?.market ?: kind) == MarketKind.IR_STOCK) {
            if (buy) IranMarket.BUY_FEE else IranMarket.SELL_FEE
        } else settings.feePct

    /** دلیل ممنوعیت معامله سهام در این لحظه (بسته بودن بازار یا صف)، یا null اگر مجاز است. */
    private fun irBlock(asset: Asset, buy: Boolean): String? {
        if (asset.market != MarketKind.IR_STOCK) return null
        if (!IranMarket.isOpen()) return "بازار بورس بسته است (شنبه تا چهارشنبه ۹:۰۰ تا ۱۲:۳۰)"
        if (buy && asset.buyQueue) return "نماد " + asset.symbol + " در صف خرید است"
        if (!buy && asset.sellQueue) return "نماد " + asset.symbol + " در صف فروش است"
        return null
    }

    private val mutex = Mutex()

    private val _reports = MutableSharedFlow<CycleReport>(replay = 1, extraBufferCapacity = 8)

    /** گزارش هر دور (هم از سرویس پس‌زمینه و هم از رابط کاربری) برای به‌روزرسانی صفحه‌ها. */
    val reports: SharedFlow<CycleReport> = _reports

    @Volatile
    var lastSignals: List<Signal> = emptyList()
        private set

    /** مسیر قیمت موقعیت‌های باز از لحظه خرید. */
    val tracker = PositionTracker(store)

    /** ژورنال معاملات: دلایل و داده‌های هر خرید/فروش و نتیجه آن. */
    val journal = TradeJournal(store)

    @Volatile
    private var journalBackfilled = false

    private fun marketGuess(assetId: String): MarketKind =
        market.cachedAssets().firstOrNull { it.id == assetId }?.market
            ?: broker.account().positions.firstOrNull { it.assetId == assetId }?.market
            ?: when {
                assetId.startsWith("fx:") -> MarketKind.FX
                assetId.startsWith("ir:") -> MarketKind.IR_STOCK
                else -> MarketKind.CRYPTO
            }

    private fun ensureJournal() {
        if (journalBackfilled) return
        journalBackfilled = true
        try {
            journal.backfill(broker.account().trades) { marketGuess(it) }
        } catch (_: Exception) {
        }
    }

    /** منابع اطلاعاتی که تصمیم هر بازار بر اساس آن‌ها گرفته می‌شود (برای ژورنال و نمایش). */
    fun sourcesFor(asset: Asset): List<String> {
        val sim = if (asset.isSimulated) listOf("⚠ داده شبیه‌سازی‌شده (منبع اصلی در دسترس نبود)") else emptyList()
        return sim + com.saeidkazemi.trader.data.remote.DataSources.forMarket(asset.market)
    }

    private fun journalOpen(
        asset: Asset,
        trade: com.saeidkazemi.trader.data.model.Trade,
        sig: Signal?,
        settings: AppSettings,
        stopUsd: Double,
        tpUsd: Double,
        trailPct: Double,
        auto: Boolean,
        threshold: Int?,
        digest: NewsDigest?,
        hist: List<PricePoint>,
        guardNote: String?
    ) {
        try {
            val factor = if (asset.price > 0) trade.priceUsd / asset.price else 1.0
            val fc = com.saeidkazemi.trader.analysis.Forecast.build(
                hist.map { PricePoint(it.t, it.price * factor) }, trade.priceUsd, score = sig?.score, simulated = asset.isSimulated
            )
            journal.open(
                com.saeidkazemi.trader.data.model.JournalEntry(
                    id = trade.id,
                    assetId = asset.id,
                    symbol = asset.symbol,
                    name = asset.name,
                    market = asset.market,
                    auto = auto,
                    mode = trade.mode,
                    openedAt = trade.ts,
                    entryUsd = trade.priceUsd,
                    entryNative = asset.price,
                    nativeCurrency = asset.baseCurrency,
                    usdIrr = market.usdIrr(settings),
                    amountUsd = trade.usdValue,
                    buyFeeUsd = trade.feeUsd,
                    qty = trade.qty,
                    entryReason = trade.reason,
                    score = sig?.score,
                    technicalScore = sig?.technicalScore,
                    newsAdj = sig?.newsAdj ?: 0,
                    proAdj = sig?.proAdj ?: 0,
                    threshold = threshold,
                    reasons = sig?.reasons,
                    proFactors = sig?.proFactors,
                    metrics = sig?.metrics,
                    newsLabel = digest?.label ?: sig?.newsLabel,
                    newsHeadlines = digest?.items?.take(5)?.map { n ->
                        "[" + n.kind.faTitle + " • " + n.sentimentLabel + "] " + n.title
                    },
                    newsSourcesOk = digest?.sourcesOk,
                    newsSourcesFailed = digest?.sourcesFailed,
                    dataSources = sourcesFor(asset),
                    simulated = asset.isSimulated,
                    stopUsd = stopUsd,
                    takeProfitUsd = tpUsd,
                    trailPct = trailPct,
                    riskLevel = settings.riskFor(asset.market),
                    forecastExpPct = fc?.expectedPct,
                    forecastProbUp = fc?.probUp,
                    guardNote = guardNote
                )
            )
        } catch (_: Exception) {
        }
    }

    private fun journalClose(
        pos: com.saeidkazemi.trader.data.model.Position,
        asset: Asset?,
        trade: com.saeidkazemi.trader.data.model.Trade,
        reason: String,
        sig: Signal?
    ) {
        try {
            val track = tracker.track(pos.assetId).map { it.price }
            val peak = maxOf(pos.peakUsd, track.maxOrNull() ?: 0.0, trade.priceUsd)
            val trough = minOf(track.minOrNull() ?: pos.avgBuyUsd, trade.priceUsd)
            val proceeds = trade.usdValue - trade.feeUsd
            val change = { e: com.saeidkazemi.trader.data.model.JournalEntry ->
                val cost = if (e.amountUsd > 0) e.amountUsd else pos.cost()
                val pnl = proceeds - cost
                e.copy(
                    closedAt = trade.ts,
                    exitUsd = trade.priceUsd,
                    exitNative = asset?.price,
                    exitReason = reason,
                    exitScore = sig?.score,
                    exitReasons = sig?.reasons?.take(8),
                    proceedsUsd = proceeds,
                    sellFeeUsd = trade.feeUsd,
                    pnlUsd = pnl,
                    pnlPct = if (cost > 0) pnl / cost * 100 else null,
                    peakUsd = peak,
                    troughUsd = trough,
                    profitLockedPct = pos.profitLockedPct
                )
            }
            if (!journal.close(pos.assetId, change)) {
                // موقعیتی که قبل از ژورنال باز شده بود
                journal.open(
                    com.saeidkazemi.trader.data.model.JournalEntry(
                        id = "p" + pos.openedAt,
                        assetId = pos.assetId,
                        symbol = pos.symbol,
                        name = pos.name,
                        market = pos.market,
                        auto = false,
                        mode = trade.mode,
                        openedAt = pos.openedAt,
                        entryUsd = pos.avgBuyUsd,
                        entryNative = pos.avgBuyUsd,
                        nativeCurrency = "USD",
                        usdIrr = 0.0,
                        amountUsd = pos.cost(),
                        buyFeeUsd = 0.0,
                        qty = pos.qty,
                        entryReason = "نامشخص (قبل از ژورنال)",
                        backfilled = true
                    )
                )
                journal.close(pos.assetId, change)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * چشم‌انداز همه موقعیت‌های باز: مسیر از خرید، سود/زیان فعلی و پیش‌بینی ۷ روز آینده.
     * فقط از داده‌های کش‌شده استفاده می‌کند (بدون درخواست شبکه).
     */
    fun outlooks(): Map<String, com.saeidkazemi.trader.analysis.PositionOutlook> {
        val settings = store.loadSettings()
        val assets = market.cachedAssets().associateBy { it.id }
        val scores = lastSignals.associate { it.assetId to it.score }
        val out = HashMap<String, com.saeidkazemi.trader.analysis.PositionOutlook>()
        for (pos in broker.account().positions) {
            try {
                val asset = assets[pos.assetId]
                val cur = asset?.let { market.usdPriceOf(it, settings) } ?: pos.avgBuyUsd
                // تاریخچه به ارز خود دارایی است؛ با نسبت قیمت دلاری به قیمت اصلی به دلار تبدیل می‌شود.
                val factor = if (asset != null && asset.price > 0) cur / asset.price else 1.0
                val histUsd = market.cachedHistory(pos.assetId).orEmpty().map { PricePoint(it.t, it.price * factor) }
                out[pos.assetId] = com.saeidkazemi.trader.analysis.PositionOutlook.build(
                    assetId = pos.assetId,
                    avgBuyUsd = pos.avgBuyUsd,
                    openedAt = pos.openedAt,
                    currentUsd = cur,
                    stopUsd = pos.stopLossUsd,
                    takeProfitUsd = pos.takeProfitUsd,
                    buyFee = feeFor(asset, pos.market, true, settings),
                    sellFee = feeFor(asset, pos.market, false, settings),
                    historyUsd = histUsd,
                    track = tracker.track(pos.assetId),
                    score = scores[pos.assetId],
                    simulated = asset?.isSimulated ?: false
                )
            } catch (_: Exception) {
            }
        }
        return out
    }

    suspend fun runCycle(trigger: String): CycleReport = mutex.withLock {
        ensureJournal()
        val settings = store.loadSettings()
        val buys = mutableListOf<String>()
        val sells = mutableListOf<String>()
        val notes = mutableListOf<String>()

        val heldIds = broker.account().positions.map { it.assetId }.toSet()
        val result = try {
            market.refresh(settings, heldIds)
        } catch (e: Exception) {
            notes.add("خطا در به‌روزرسانی بازار: " + (e.message ?: ""))
            RefreshResult(market.cachedAssets(), emptyList())
        }
        notes.addAll(result.notes)
        val assets = result.assets
        if (!IranMarket.isOpen() && assets.any { it.market == MarketKind.IR_STOCK && !it.isSimulated }) {
            notes.add("بازار بورس الان بسته است؛ سهام تحلیل می‌شوند ولی خرید و فروش آن‌ها فقط در ساعت کار بازار (شنبه تا چهارشنبه ۹ تا ۱۲:۳۰) انجام می‌شود.")
        }
        val usdIrr = market.usdIrr(settings)

        // ۱) تحلیل تکنیکال همه دارایی‌های دارای داده کافی
        val histories = HashMap<String, List<PricePoint>>()
        val technical = mutableListOf<Signal>()
        val analyzable = assets.filter { !it.isDisplayOnly }
        // سهام بورس: تاریخچه تازه‌نشده‌ها به ترتیب نقدشوندگی و حداکثر IR_HISTORY_PER_CYCLE مورد در هر دور.
        val irPending = analyzable
            .filter { it.market == MarketKind.IR_STOCK && !it.isSimulated && !market.hasFreshHistory(it) }
            .sortedWith(compareBy<Asset> { it.id !in heldIds }.thenBy { it.rank ?: Int.MAX_VALUE })
        val deferred = irPending.drop(IR_HISTORY_PER_CYCLE).map { it.id }.toSet()
        val sem = Semaphore(HISTORY_PARALLELISM)
        val fetched = coroutineScope {
            analyzable.filter { it.id !in deferred }.map { asset ->
                async {
                    sem.withPermit {
                        val h: List<PricePoint> = try {
                            market.historyFor(asset)
                        } catch (e: Exception) {
                            emptyList()
                        }
                        asset to h
                    }
                }
            }.awaitAll()
        }
        for ((asset, hist) in fetched) {
            histories[asset.id] = hist
            val sig = strategy.analyze(asset, hist, settings, null) ?: continue
            technical.add(sig)
        }
        if (deferred.isNotEmpty()) {
            notes.add("تحلیل " + deferred.size + " سهم دیگر در دورهای بعدی انجام می‌شود (پویش تدریجی کل بازار).")
        }

        // اصلاح موقعیت‌های سهام پس از افزایش سرمایه/تقسیم سود (تا افت قیمت پس از مجمع، زیان کاذب یا حد ضرر نسازد).
        for (pos in broker.account().positions) {
            if (pos.market != MarketKind.IR_STOCK) continue
            val (day, factor) = market.iranAdjustment(pos.assetId) ?: continue
            if (pos.openedAt >= IranMarket.dayStartMs(day)) continue
            if (broker.adjustPosition(pos.assetId, factor, day)) {
                notes.add("موقعیت " + pos.symbol + " بابت افزایش سرمایه/تقسیم سود تعدیل شد (ضریب " + Format.num(factor, 3) + ").")
            }
        }
        technical.sortByDescending { it.score }
        val noHistory = assets.count {
            !it.isDisplayOnly && it.market == MarketKind.CRYPTO && (histories[it.id]?.size ?: 0) < StrategyEngine.MIN_HISTORY
        }
        if (noHistory > 0) {
            notes.add("تاریخچه قیمت " + noHistory + " ارز دیجیتال دریافت نشد؛ این ارزها فعلاً تحلیل نمی‌شوند.")
        }

        // ۲) بررسی اخبار برای بهترین فرصت‌ها + دارایی‌های داخل پرتفوی
        val digests = HashMap<String, NewsDigest>()
        if (settings.newsEnabled) {
            val held = broker.account().positions.map { it.assetId }.toSet()
            val wanted = LinkedHashSet<String>()
            technical.take(NEWS_CANDIDATES).forEach { wanted.add(it.assetId) }
            wanted.addAll(held)
            val targets = assets.filter { it.id in wanted && !it.isDisplayOnly }
            try {
                digests.putAll(news.digests(targets))
            } catch (e: Exception) {
                notes.add("بررسی اخبار با خطا مواجه شد: " + (e.message ?: ""))
            }
            // برای بقیه دارایی‌ها از کش اخبار (بدون درخواست شبکه) استفاده می‌شود.
            for (a in assets) {
                if (a.id !in digests) news.cached(a.id)?.let { digests[a.id] = it }
            }
            val withNews = digests.values.count { it.available }
            val okSources = digests.values.flatMap { it.sourcesOk }.distinct()
            val failedSources = digests.values.flatMap { it.sourcesFailed }.distinct() - okSources.toSet()
            if (targets.isNotEmpty()) {
                notes.add(
                    "اخبار " + targets.size + " دارایی بررسی شد؛ برای " + withNews + " مورد خبر مرتبط پیدا شد" +
                        (if (okSources.isNotEmpty()) " (منابع: " + okSources.joinToString("، ") + ")" else "") + "."
                )
            }
            if (failedSources.isNotEmpty()) {
                notes.add("منبع خبری در دسترس نبود: " + failedSources.joinToString("، "))
            }
        }

        // ۳) تحلیل تخصصی (جریان پول، حجم، ارزش‌گذاری، ترس و طمع، روند بیت‌کوین، دفتر سفارش)
        val assetMap = assets.associateBy { it.id }
        val heldNow = broker.account().positions.map { it.assetId }.toSet()
        val btcAsset = assets.firstOrNull { it.market == MarketKind.CRYPTO && it.symbol == "BTC" }
        val btcHistory = btcAsset?.let { histories[it.id] }.orEmpty()
        val books = HashMap<String, Double>()
        if (settings.proAnalysis) {
            if (assets.any { it.market == MarketKind.CRYPTO && !it.isDisplayOnly }) {
                try { market.insights.fearGreed() } catch (_: Exception) { }
                val bookTargets = (technical.filter { it.market == MarketKind.CRYPTO }.take(BOOK_CANDIDATES).map { it.assetId } +
                    heldNow.filter { assetMap[it]?.market == MarketKind.CRYPTO }).distinct()
                val got = coroutineScope {
                    bookTargets.mapNotNull { assetMap[it] }.map { a ->
                        async { sem.withPermit { a.id to (try { market.insights.bidShare(a.symbol) } catch (_: Exception) { null }) } }
                    }.awaitAll()
                }
                for ((id, v) in got) if (v != null) books[id] = v
            }
            val proNotes = mutableListOf<String>()
            market.insights.cachedFearGreed()?.let { proNotes.add("ترس و طمع ارز دیجیتال: " + it.value + " (" + (fearGreedFa(it.label) ?: "") + ")") }
            if (btcHistory.size >= 50) {
                val v = btcHistory.map { it.price }.toDoubleArray()
                val sma = com.saeidkazemi.trader.analysis.Indicators.sma(v, 50)
                if (sma != null) proNotes.add("بیت‌کوین " + (if (v.last() >= sma) "بالای" else "زیر") + " میانگین ۵۰روزه")
            }
            market.iranStats?.let { st ->
                val b = st.breadth
                if (b != null && assets.any { it.market == MarketKind.IR_STOCK && !it.isSimulated }) {
                    proNotes.add("بورس: " + Format.num(b * 100, 0) + "٪ نمادها مثبت" +
                        (st.realNetIrr?.let { n -> "، " + (if (n >= 0) "ورود" else "خروج") + " پول حقیقی " + Format.compactIrr(kotlin.math.abs(n)) } ?: ""))
                }
            }
            if (proNotes.isNotEmpty()) notes.add("تحلیل تخصصی — " + proNotes.joinToString("؛ ") + ".")
            if (market.iranFlowError != null && assets.any { it.market == MarketKind.IR_STOCK && !it.isSimulated }) {
                notes.add("داده حقیقی/حقوقی بورس دریافت نشد؛ تحلیل جریان پول سهام در این دور انجام نشد.")
            }
        }

        // ۴) سیگنال نهایی = تکنیکال + اثر اخبار + تحلیل تخصصی (با آستانه خرید اختصاصی هر بازار)
        val signals = technical.map { t ->
            val asset = assetMap[t.assetId] ?: return@map t
            val hist = histories[t.assetId].orEmpty()
            val pro = proFor(asset, hist, settings, btcHistory, books[asset.id])
            val th = settings.buyThreshold + planFor(settings, asset.market).buyThresholdDelta
            strategy.analyze(asset, hist, settings, digests[t.assetId], pro, th) ?: t
        }.sortedByDescending { it.score }
        lastSignals = signals
        val signalMap = signals.associateBy { it.assetId }
        val priceMap = assets.associate { it.id to market.usdPriceOf(it, settings) }

        // ۵) حد ضرر متحرک: با هر قله تازه، حد ضرر بالا کشیده می‌شود تا سود قفل شود.
        broker.trail(priceMap)

        // ۵-ب) قفل سود: وقتی سود خالص (پس از کارمزد) به آستانه (پیش‌فرض ۱۰٪) رسید، حد ضرر روی قیمتی می‌رود
        // که فروش در آن همان سود (پیش‌فرض ۱۰٪) را حفظ کند. از آن به بعد، معامله دیگر نمی‌تواند زیان‌ده یا کم‌سودتر بسته شود
        // (مگر پرش ناگهانی قیمت یا صف فروش). اگر قیمت باز هم بالا برود، حد ضرر متحرک آن را بالاتر می‌برد.
        if (settings.profitLock) {
            for (pos in broker.account().positions) {
                val cur = priceMap[pos.assetId] ?: continue
                if (!cur.isFinite() || cur <= 0) continue
                val a = assetMap[pos.assetId]
                val bf = feeFor(a, pos.market, true, settings)
                val sf = feeFor(a, pos.market, false, settings)
                val peak = maxOf(pos.peakUsd, cur)
                val stop = RiskManager.ProfitLock.stopFor(
                    pos.avgBuyUsd, peak, bf, sf, settings.profitLockTriggerPct, settings.profitLockKeepPct
                ) ?: continue
                val keep = minOf(settings.profitLockKeepPct, settings.profitLockTriggerPct)
                if (broker.lockProfit(pos.assetId, stop, keep)) {
                    notes.add(
                        "قفل سود " + pos.symbol + ": سود خالص به " + Format.num(settings.profitLockTriggerPct, 0) +
                            "٪ رسید؛ حد ضرر روی $" + Format.price(stop) + " رفت تا حداقل " + Format.num(keep, 0) + "٪ سود حفظ شود."
                    )
                }
            }
        }

        // ۶) مدیریت ریسک و فروش موقعیت‌های باز
        for (pos in broker.account().positions) {
            val asset = assetMap[pos.assetId] ?: continue
            val curUsd = priceMap[asset.id] ?: continue
            if (!curUsd.isFinite() || curUsd <= 0) continue
            val sig = signalMap[pos.assetId]
            val reason: String? = when {
                curUsd <= pos.stopLossUsd && pos.profitLockedPct > 0 ->
                    "حفظ سود (قفل سود حداقل " + Format.num(pos.profitLockedPct, 0) + "٪)"
                curUsd <= pos.stopLossUsd && pos.stopLossUsd > pos.avgBuyUsd -> "حد ضرر متحرک (قفل سود)"
                curUsd <= pos.stopLossUsd -> "فعال شدن حد ضرر"
                curUsd >= pos.takeProfitUsd -> "فعال شدن حد سود"
                sig != null && sig.newsBlocked -> "خروج به‌خاطر خبر منفی مهم"
                sig != null && sig.proBlocked && sig.score < settings.buyThreshold - 10 -> "خروج به‌خاطر شرایط تخصصی: " + (sig.proBlockReason ?: "")
                sig != null && sig.score <= settings.sellThreshold -> "ضعیف شدن سیگنال (امتیاز " + sig.score + ")"
                else -> null
            }
            if (reason != null) {
                val blocked = irBlock(asset, buy = false)
                if (blocked != null) {
                    if (IranMarket.isOpen()) notes.add("فروش " + pos.symbol + " (" + reason + ") ممکن نشد: " + blocked + ".")
                    continue
                }
                announce("SELL", asset, pos.qty * curUsd, reason)
                val trade = broker.sell(pos.assetId, curUsd, feeFor(asset, null, false, settings), reason)
                if (trade != null) {
                    sells.add(pos.symbol)
                    journalClose(pos, asset, trade, reason, sig)
                    val pnl = trade.usdValue - trade.feeUsd - pos.cost()
                    maybeRealSell(settings, asset, pos.qty, notes)
                    completed("SELL", asset, true, "فروش " + pos.symbol + " — " + reason, pnl)
                } else {
                    completed("SELL", asset, false, "فروش " + pos.symbol + " انجام نشد")
                }
            }
        }

        // ۷) خرید خودکار — هر بازار جدا و فقط با سرمایه اختصاصی خودش (بدون تأیید موردی)
        if (settings.autoTrade) {
            for (m in riskManager.tradableMarkets) {
                if (settings.allocationPct(m) <= 0.0) continue
                val plan = planFor(settings, m)
                val acc0 = broker.account()
                val equity = sleeveEquity(acc0, m, priceMap)
                val reserve = equity * plan.cashReservePct
                // محافظ نرخ برد: اگر معاملات اخیر این بازار کم‌برد و زیان‌ده بوده، سخت‌گیرتر و با حجم کمتر خرید می‌شود
                val guard = com.saeidkazemi.trader.analysis.Performance.guard(journal.all(), m, settings)
                val th = settings.buyThreshold + plan.buyThresholdDelta
                if (guard.active) notes.add("محافظ نرخ برد (" + m.faTitle + "): " + guard.text + ".")
                for (sig in signals) {
                    if (sig.market != m || sig.action != Action.BUY) continue
                    val acc = broker.account()
                    if (acc.positions.count { it.market == m } >= plan.maxPositions) break
                    if (acc.positions.any { it.assetId == sig.assetId }) continue
                    if (sells.contains(sig.symbol)) continue
                    val asset = assetMap[sig.assetId] ?: continue
                    // روی داده شبیه‌سازی‌شده خودکار خرید نمی‌شود تا سود/زیان دمو واقعی بماند.
                    if (asset.isSimulated) continue
                    // سهام: فقط در ساعت کار بازار و وقتی نماد در صف خرید نیست.
                    if (irBlock(asset, buy = true) != null) continue
                    if (guard.active && sig.score < th + com.saeidkazemi.trader.analysis.Performance.GUARD_EXTRA_THRESHOLD) continue
                    val usdPrice = priceMap[asset.id] ?: continue
                    if (!usdPrice.isFinite() || usdPrice <= 0) continue
                    val budget = equity * plan.positionPct *
                        (if (guard.active) com.saeidkazemi.trader.analysis.Performance.GUARD_SIZE_FACTOR else 1.0)
                    val available = (acc.cashByMarket[m.name] ?: 0.0) - reserve
                    val amount = minOf(budget, available)
                    if (amount < plan.minTradeUsd) break
                    val newsPart = if (sig.newsAdj != 0) "، اخبار " + ProAnalysis.signed(sig.newsAdj) else ""
                    val proPart = if (sig.proAdj != 0) "، تخصصی " + ProAnalysis.signed(sig.proAdj) else ""
                    val reason = "خرید خودکار (امتیاز " + sig.score + newsPart + proPart + ")"
                    val stopPct = plan.stopFor(sig.metrics.volatility)
                    announce("BUY", asset, amount, reason)
                    val trade = broker.buy(
                        asset = asset,
                        usdPrice = usdPrice,
                        usdAmount = amount,
                        feePct = feeFor(asset, null, true, settings),
                        stopLossUsd = usdPrice * (1 - stopPct),
                        takeProfitUsd = usdPrice * (1 + plan.tpPct),
                        reason = reason,
                        trailPct = plan.trailPct
                    )
                    if (trade != null) {
                        buys.add(asset.symbol)
                        journalOpen(
                            asset, trade, sig, settings,
                            stopUsd = usdPrice * (1 - stopPct), tpUsd = usdPrice * (1 + plan.tpPct), trailPct = plan.trailPct,
                            auto = true, threshold = th, digest = digests[asset.id], hist = histories[asset.id].orEmpty(),
                            guardNote = if (guard.active) guard.text else null
                        )
                        maybeRealBuy(settings, asset, amount, usdIrr, notes)
                        completed("BUY", asset, true, "خرید " + asset.symbol + " به مبلغ " + Format.num(amount) + " دلار")
                    } else {
                        completed("BUY", asset, false, "خرید " + asset.symbol + " انجام نشد")
                    }
                }
            }
            if (signals.isEmpty()) notes.add("دارایی با داده کافی برای تحلیل پیدا نشد.")
        }

        store.saveAccount(broker.account())
        tracker.record(broker.account().positions, priceMap)
        val report = CycleReport(
            ts = System.currentTimeMillis(),
            trigger = trigger,
            buys = buys,
            sells = sells,
            notes = notes.distinct(),
            auto = settings.autoTrade
        )
        _reports.tryEmit(report)
        report
    }

    /** اخبار یک دارایی (برای صفحه جزئیات)؛ در صورت نیاز از شبکه دریافت می‌شود. */
    suspend fun newsFor(asset: Asset, force: Boolean = false): NewsDigest = news.digest(asset, force)

    fun cachedNews(): List<NewsDigest> = news.allCached()

    /** خرید دستی از صفحه جزئیات. */
    suspend fun manualBuy(assetId: String, usdAmount: Double): String {
        val settings = store.loadSettings()
        val asset = market.cachedAssets().firstOrNull { it.id == assetId }
            ?: return "دارایی پیدا نشد؛ ابتدا بازار را به‌روزرسانی کنید."
        if (asset.isDisplayOnly) return "این دارایی فقط نمایشی است و معامله نمی‌شود."
        val plan = planFor(settings, asset.market)
        irBlock(asset, buy = true)?.let { return "خرید ممکن نیست: " + it + "." }
        val usdPrice = market.usdPriceOf(asset, settings)
        if (!usdPrice.isFinite() || usdPrice <= 0) return "قیمت معتبر در دسترس نیست."
        val account = broker.account()
        if (account.positions.any { it.assetId == assetId }) return "این دارایی را از قبل در پرتفوی دارید."
        if (usdAmount < plan.minTradeUsd) return "حداقل مبلغ معامله " + plan.minTradeUsd.toInt() + " دلار است."
        val sleeve = account.cashByMarket[asset.market.name] ?: 0.0
        if (usdAmount > sleeve + 1e-9) {
            return "موجودی نقد بخش " + asset.market.faTitle + " کافی نیست (" + Format.num(sleeve) + " دلار). " +
                "هر بازار فقط با سرمایه اختصاصی خودش معامله می‌کند؛ سهم بازارها را در تنظیمات تغییر دهید."
        }
        val vol = market.cachedHistory(asset.id)?.let { h ->
            com.saeidkazemi.trader.analysis.Indicators.dailyVolatilityPct(h.map { it.price }.toDoubleArray(), 30)
        }
        val stopPct = plan.stopFor(vol)
        announce("BUY", asset, usdAmount, "خرید دستی")
        val trade = broker.buy(
            asset = asset,
            usdPrice = usdPrice,
            usdAmount = usdAmount,
            feePct = feeFor(asset, null, true, settings),
            stopLossUsd = usdPrice * (1 - stopPct),
            takeProfitUsd = usdPrice * (1 + plan.tpPct),
            reason = "خرید دستی",
            trailPct = plan.trailPct
        )
        if (trade == null) {
            completed("BUY", asset, false, "خرید " + asset.symbol + " انجام نشد")
            return "خرید انجام نشد."
        }
        tracker.record(broker.account().positions, market.cachedAssets().associate { it.id to market.usdPriceOf(it, settings) })
        ensureJournal()
        journalOpen(
            asset, trade, lastSignals.firstOrNull { it.assetId == assetId }, settings,
            stopUsd = usdPrice * (1 - stopPct), tpUsd = usdPrice * (1 + plan.tpPct), trailPct = plan.trailPct,
            auto = false, threshold = settings.buyThreshold + plan.buyThresholdDelta,
            digest = news.cached(assetId), hist = market.cachedHistory(assetId).orEmpty(), guardNote = null
        )
        completed("BUY", asset, true, "خرید دستی " + asset.symbol + " به مبلغ " + Format.num(usdAmount) + " دلار")
        if (settings.realTrading) {
            val notes = mutableListOf<String>()
            maybeRealBuy(settings, asset, usdAmount, market.usdIrr(settings), notes)
            if (notes.isNotEmpty()) return "خرید در دفتر ثبت شد. " + notes.joinToString(" ")
        }
        return "خرید " + asset.symbol + " به مبلغ " + Format.num(usdAmount) + " دلار ثبت شد."
    }

    /** فروش دستی کل یک موقعیت. */
    suspend fun manualSell(assetId: String): String {
        val settings = store.loadSettings()
        val pos = broker.account().positions.firstOrNull { it.assetId == assetId }
            ?: return "موقعیتی برای فروش ندارید."
        val asset = market.cachedAssets().firstOrNull { it.id == assetId }
        val usdPrice = if (asset != null) market.usdPriceOf(asset, settings) else pos.avgBuyUsd
        if (asset != null) irBlock(asset, buy = false)?.let { return "فروش ممکن نیست: " + it + "." }
        announce("SELL", pos.symbol, pos.market, pos.qty * usdPrice, "فروش دستی")
        val trade = broker.sell(assetId, usdPrice, feeFor(asset, pos.market, false, settings), "فروش دستی")
        if (trade == null) {
            completed("SELL", pos.symbol, pos.market, false, "فروش " + pos.symbol + " انجام نشد")
            return "فروش انجام نشد."
        }
        ensureJournal()
        journalClose(pos, asset, trade, "فروش دستی", lastSignals.firstOrNull { it.assetId == assetId })
        completed("SELL", pos.symbol, pos.market, true, "فروش دستی " + pos.symbol,
            trade.usdValue - trade.feeUsd - pos.cost())
        if (settings.realTrading && asset != null) {
            val notes = mutableListOf<String>()
            maybeRealSell(settings, asset, pos.qty, notes)
            if (notes.isNotEmpty()) return "فروش در دفتر ثبت شد. " + notes.joinToString(" ")
        }
        return "فروش " + pos.symbol + " با قیمت " + Format.num(usdPrice) + " دلار ثبت شد."
    }

    fun resetPaperAccount(capitalUsd: Double) {
        broker.reset(capitalUsd, store.loadSettings().allocations)
        journal.clear()
    }

    // ---- بخش معامله واقعی (آزمایشی، فقط نوبیتکس) ----

    private suspend fun maybeRealBuy(
        settings: com.saeidkazemi.trader.data.model.AppSettings,
        asset: Asset,
        usdAmount: Double,
        usdIrr: Double,
        notes: MutableList<String>
    ) {
        if (!settings.realTrading) return
        val sym = asset.nobitexSymbol
        if (asset.market != MarketKind.CRYPTO || sym == null) {
            notes.add("معامله واقعی فقط برای ارزهای متصل به نوبیتکس فعال است؛ " + asset.symbol + " فقط در دفتر دمو ثبت شد.")
            return
        }
        val lastRls = nobitex.lastPriceRls(sym)
        if (lastRls == null || !lastRls.isFinite() || lastRls <= 0) {
            notes.add("قیمت ریالی " + asset.symbol + " از نوبیتکس گرفته نشد؛ سفارش واقعی ارسال نشد.")
            return
        }
        val baseQty = usdAmount * usdIrr / lastRls
        when (val r = nobitex.placeOrder(settings, sym, "buy", baseQty)) {
            is OrderResult.Success -> notes.add("سفارش واقعی خرید " + asset.symbol + " در نوبیتکس ثبت شد.")
            is OrderResult.Failure -> notes.add("خطای سفارش واقعی " + asset.symbol + ": " + r.message)
            OrderResult.NotConfigured -> notes.add("توکن نوبیتکس تنظیم نشده؛ معامله فقط در حالت دمو ثبت شد.")
        }
    }

    private suspend fun maybeRealSell(
        settings: com.saeidkazemi.trader.data.model.AppSettings,
        asset: Asset,
        qty: Double,
        notes: MutableList<String>
    ) {
        if (!settings.realTrading) return
        val sym = asset.nobitexSymbol
        if (asset.market != MarketKind.CRYPTO || sym == null) {
            notes.add("فروش واقعی فقط برای ارزهای متصل به نوبیتکس فعال است؛ " + asset.symbol + " فقط در دفتر دمو ثبت شد.")
            return
        }
        when (val r = nobitex.placeOrder(settings, sym, "sell", qty)) {
            is OrderResult.Success -> notes.add("سفارش واقعی فروش " + asset.symbol + " در نوبیتکس ثبت شد.")
            is OrderResult.Failure -> notes.add("خطای سفارش واقعی " + asset.symbol + ": " + r.message)
            OrderResult.NotConfigured -> notes.add("توکن نوبیتکس تنظیم نشده؛ معامله فقط در حالت دمو ثبت شد.")
        }
    }
}
