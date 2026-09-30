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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.saeidkazemi.trader.analysis.Backtest
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
        /** کمترین مبلغ خرید با «باقی‌مانده نقد» یک بازار (بالاتر از حداقل سفارش ۳۰۰ هزار تومانی نوبیتکس). */
        const val LEFTOVER_MIN_USD = 4.0
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

    /** برنامه ریسک هر بازار؛ اگر بک‌تست پارامتر تأییدشده داشته باشد، حد سود/ضرر/آستانه/مهلت از آن می‌آید. */
    fun planFor(settings: AppSettings, m: MarketKind): RiskManager.Plan {
        val base = riskManager.plan(settings.riskFor(m), m)
        if (!settings.useBacktestParams) return base
        val p = backtest?.appliedFor(m) ?: return base
        return Backtest.applyTo(base, p, settings.buyThreshold)
    }

    // ---- بک‌تست روزانه ----

    @Volatile
    var backtest: Backtest.Report? = try { store.loadBacktest() } catch (_: Exception) { null }
        private set

    @Volatile
    var backtestRunning: Boolean = false
        private set

    @Volatile
    var backtestProgress: String? = null
        private set

    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** هر ۲۴ ساعت یک بار بک‌تست در پس‌زمینه (بدون معطل کردن معاملات). */
    private fun maybeStartBacktest() {
        if (backtestRunning) return
        val last = backtest?.createdAt ?: 0L
        // نسخه‌های قبل مطالعه پله‌ای نداشتند: یک بار زودتر تکرار می‌شود
        if (System.currentTimeMillis() - last < 24 * 3_600_000L && backtest?.scale != null) return
        if (market.cachedAssets().isEmpty()) return
        bgScope.launch {
            try {
                runBacktest()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * بک‌تست همه بازارهای قابل معامله روی تاریخچه طولانی و انتخاب پارامتر با هدف نرخ برد ۶۰٪ و امید ریاضی مثبت.
     * نتیجه ذخیره و (در صورت تأیید خارج از نمونه) برای خریدهای جدید اعمال می‌شود.
     */
    suspend fun runBacktest(): Backtest.Report? {
        if (backtestRunning) return backtest
        backtestRunning = true
        try {
            val settings = store.loadSettings()
            val assets = market.cachedAssets()
            val results = ArrayList<Backtest.MarketResult>()
            val notes = ArrayList<String>()
            val scale = ArrayList<com.saeidkazemi.trader.analysis.ScaleStudy.Row>()
            val sem = Semaphore(4)
            for (m in listOf(MarketKind.CRYPTO, MarketKind.IR_STOCK, MarketKind.METAL)) {
                val pool = assets.filter { it.market == m && !it.isDisplayOnly && !it.isSimulated }
                    .sortedBy { it.rank ?: Int.MAX_VALUE }
                    .take(if (m == MarketKind.IR_STOCK) 40 else 30)
                if (pool.isEmpty()) {
                    notes.add(m.faTitle + ": داده زنده در دسترس نبود؛ بک‌تست انجام نشد.")
                    continue
                }
                backtestProgress = "دریافت تاریخچه " + m.faTitle + " (" + pool.size + " دارایی)…"
                val series = coroutineScope {
                    pool.map { a ->
                        async {
                            sem.withPermit {
                                val h = try { market.longHistory(a, 730) } catch (_: Exception) { emptyList() }
                                val costs = Backtest.Costs(
                                    feeFor(a, m, true, settings), feeFor(a, m, false, settings), halfSpread(a, m)
                                )
                                Backtest.prepare(a, h, settings, costs)
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
                if (series.isEmpty()) {
                    notes.add(m.faTitle + ": تاریخچه کافی دریافت نشد.")
                    continue
                }
                backtestProgress = "بهینه‌سازی " + m.faTitle + "…"
                val base = riskManager.plan(settings.riskFor(m), m)
                val current = Backtest.paramsOf(base, settings.buyThreshold + base.buyThresholdDelta)
                Backtest.optimize(m, series, current, settings)?.let { res ->
                    results.add(res)
                    val studied = res.best?.takeIf { res.applied } ?: res.current
                    try { scale.addAll(com.saeidkazemi.trader.analysis.ScaleStudy.run(m, series, studied, settings)) } catch (_: Exception) { }
                }
            }
            if (results.isEmpty() && backtest != null) return backtest
            val report = Backtest.Report(System.currentTimeMillis(), results, notes, scale)
            backtest = report
            store.saveBacktest(report)
            if (results.any { it.applied }) {
                val s = store.loadSettings()
                if (s.backtestSince <= 0) store.saveSettings(s.copy(backtestSince = report.createdAt))
            }
            return report
        } finally {
            backtestRunning = false
            backtestProgress = null
        }
    }

    /** مهلت نگهداری بک‌تست فقط برای خریدهایی که بعد از اعمال همان بک‌تست انجام شده‌اند. */
    private fun holdExpired(pos: com.saeidkazemi.trader.data.model.Position, settings: AppSettings): Boolean {
        val plan = planFor(settings, pos.market)
        if (!plan.tuned || plan.maxHoldDays <= 0) return false
        val since = settings.backtestSince
        if (since <= 0 || pos.openedAt < since) return false
        return System.currentTimeMillis() - pos.openedAt >= plan.maxHoldDays * 86_400_000L
    }

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
    /** یک خط خلاصه: داده زنده هر بازار الان رسید یا نه (✓ / ✗). */
    private fun dataStatusLine(assets: List<Asset>, settings: AppSettings): String {
        fun live(m: MarketKind) = assets.count { it.market == m && !it.isSimulated && !it.isDisplayOnly }
        val parts = ArrayList<String>()
        val c = live(MarketKind.CRYPTO)
        parts.add(if (c > 0) "ارز دیجیتال ✓ " + c + " ارز (" + market.cryptoListSource.ifEmpty { "زنده" } + ")" else "ارز دیجیتال ✗")
        val ir = assets.count { it.market == MarketKind.IR_STOCK && !it.isSimulated }
        parts.add(if (ir > 0) "بورس ✓ " + ir + " سهم" else "بورس ✗")
        val mt = live(MarketKind.METAL)
        parts.add(if (mt > 0) "طلا و دلار ✓ " + mt else "طلا و دلار ✗")
        if (settings.allocationPct(MarketKind.FX) > 0) parts.add(if (live(MarketKind.FX) > 0) "ارز خارجی ✓" else "ارز خارجی ✗")
        return "وضعیت داده الان: " + parts.joinToString(" • ")
    }

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

    /**
     * قیمت دلاری یک دارایی برای دفتر حساب. سهام ریالی که در پرتفوی است با نرخ دلارِ ثابتِ لحظه خرید تبدیل می‌شود،
     * تا سود/زیان و نمودار دقیقاً حرکت قیمت ریالی سهم را نشان دهد (نه نوسان دلار).
     */
    fun usdPriceFor(
        asset: Asset,
        settings: AppSettings,
        positions: List<com.saeidkazemi.trader.data.model.Position> = broker.account().positions
    ): Double {
        if (asset.baseCurrency == "IRR") {
            val rate = positions.firstOrNull { it.assetId == asset.id }?.fxRate ?: 0.0
            if (rate > 0) return asset.price / rate
        }
        return market.usdPriceOf(asset, settings)
    }

    private fun buildActivity(
        m: MarketKind,
        settings: AppSettings,
        maxPositions: Int,
        minTradeUsd: Double,
        th: Int,
        reserve: Double,
        signals: List<Signal>,
        priceMap: Map<String, Double>,
        bought: Int,
        soldSymbols: List<String>,
        skipped: Map<String, Int>,
        stopReason: String?
    ): MarketActivity {
        val acc = broker.account()
        val mine = signals.filter { it.market == m }
        val buySigs = mine.filter { it.action == Action.BUY }
        val best = mine.filter { s -> acc.positions.none { it.assetId == s.assetId } }.maxByOrNull { it.score }
        val held = acc.positions.filter { it.market == m }
        val closedHere = journal.all().filter { it.market == m && !it.isOpen }.map { it.symbol }.toSet()
        val sold = soldSymbols.count { it in closedHere }
        val blockedHigh = mine.count { it.score >= th && it.action != Action.BUY && (it.newsBlocked || it.proBlocked) }
        val status = when {
            bought > 0 -> "این دور " + bought + " خرید انجام شد"
            m == MarketKind.IR_STOCK && !IranMarket.isOpen() -> "بازار بورس بسته است (شنبه تا چهارشنبه ۹:۰۰ تا ۱۲:۳۰)؛ خرید و فروش سهام فقط در ساعت بازار"
            stopReason != null -> stopReason
            mine.isEmpty() -> "هنوز داده کافی برای تحلیل این بازار نیست"
            buySigs.isEmpty() -> "هیچ دارایی به آستانه خرید (" + th + ") نرسید" +
                (best?.let { "؛ بهترین: " + it.symbol + " با امتیاز " + it.score } ?: "")
            skipped.isNotEmpty() -> "همه " + buySigs.size + " کاندید خرید رد شدند: " +
                skipped.entries.sortedByDescending { it.value }.take(2).joinToString("، ") { it.key + " (" + it.value + ")" }
            else -> "کاندید مناسبی برای خرید نبود"
        }
        val details = ArrayList<String>()
        if (blockedHigh > 0) details.add(blockedHigh.toString() + " دارایی امتیاز کافی داشتند ولی به‌خاطر خبر منفی مهم یا شرایط تخصصی خطرناک خریده نشدند")
        if (skipped.isNotEmpty() && bought > 0) {
            details.add("رد شده‌ها: " + skipped.entries.sortedByDescending { it.value }.take(3).joinToString("، ") { it.key + " (" + it.value + ")" })
        }
        val free = (acc.cashByMarket[m.name] ?: 0.0)
        details.add(
            "موقعیت‌ها " + held.size + " از " + maxPositions + " • نقد آزاد $" + Format.num(free) +
                " (ذخیره $" + Format.num(reserve) + ") • آستانه خرید " + th
        )
        // فاصله هر موقعیت تا فروش
        for (p in held.take(6)) {
            val cur = priceMap[p.assetId] ?: continue
            if (cur <= 0 || p.avgBuyUsd <= 0) continue
            val pnl = (cur / p.avgBuyUsd - 1) * 100
            val toStop = (p.stopLossUsd / cur - 1) * 100
            val toTp = (p.takeProfitUsd / cur - 1) * 100
            val sig = mine.firstOrNull { it.assetId == p.assetId }
            val stopTxt = if (toStop < 0) "اگر " + Format.num(-toStop, 1) + "٪ دیگر افت کند" else "زیر حد ضرر است و در اولین فرصت ممکن"
            val tpTxt = if (toTp > 0) "یا اگر " + Format.num(toTp, 1) + "٪ دیگر رشد کند" else "به حد سود رسیده"
            details.add(
                p.symbol + ": الان " + Format.pct(pnl) + " • فروش " + stopTxt +
                    (if (p.profitLockedPct > 0) " (قفل سود " + Format.num(p.profitLockedPct, 0) + "٪)" else "") +
                    " " + tpTxt +
                    (sig?.let { " یا اگر امتیازش از " + it.score + " به " + settings.sellThreshold + " برسد" } ?: "")
            )
        }
        val lastTrade = journal.all().filter { it.market == m }.maxOfOrNull { maxOf(it.openedAt, it.closedAt ?: 0L) }
        return MarketActivity(
            market = m,
            ts = System.currentTimeMillis(),
            positions = held.size,
            maxPositions = maxPositions,
            freeCashUsd = free,
            minTradeUsd = minTradeUsd,
            threshold = th,
            buySignals = buySigs.size,
            bestSymbol = best?.symbol,
            bestScore = best?.score,
            boughtNow = bought,
            soldNow = sold,
            status = status,
            details = details,
            lastTradeAt = lastTrade
        )
    }

    /** موقعیت‌های ریالی قدیمی: نرخ دلار لحظه خرید از ژورنال بازسازی و ثابت می‌شود. */
    private fun migrateFxLocks(settings: AppSettings, notes: MutableList<String>) {
        for (pos in broker.account().positions) {
            if (pos.nativeCurrency != "IRR" || pos.fxRate > 0) continue
            val e = journal.all().firstOrNull { it.assetId == pos.assetId && it.isOpen }
            val fromJournal = e?.let { if (it.entryUsd > 0 && it.entryNative > 0) it.entryNative / it.entryUsd else it.usdIrr }
            val rate = fromJournal?.takeIf { it.isFinite() && it > 1000 } ?: market.usdIrr(settings)
            if (broker.setFxRate(pos.assetId, rate)) {
                tracker.clear(pos.assetId)
                notes.add("نرخ دلار موقعیت " + pos.symbol + " روی " + Format.num(rate, 0) + " ریال (لحظه خرید) ثابت شد؛ از این به بعد سود/زیان آن فقط با قیمت ریالی سهم تغییر می‌کند.")
            }
        }
    }

    /** کارمزد رسمی هر بازار (نوبیتکس طبق پله کارمزد، بورس طبق مصوبه سازمان بورس، فارکس فرضی). */
    private fun feeFor(asset: Asset?, kind: MarketKind?, buy: Boolean, settings: AppSettings): Double =
        Fees.commission(asset, kind, buy, settings)

    /**
     * نصف اسپرد واقعی (کسر): خرید با سفارش بازار به بهترین قیمت فروشنده و فروش به بهترین قیمت خریدار انجام می‌شود.
     * رمزارز از دفتر سفارش نوبیتکس، سهام از سرخط سفارش‌های TSETMC؛ اگر نبود، مقدار پیش‌فرض محافظه‌کارانه.
     */
    fun halfSpread(asset: Asset?, kind: MarketKind?): Double {
        val m = asset?.market ?: kind ?: return 0.0
        return when (m) {
            MarketKind.CRYPTO -> asset?.let { market.insights.cachedHalfSpread(it.symbol) } ?: (Fees.DEFAULT_SPREAD_CRYPTO / 2)
            MarketKind.IR_STOCK -> asset?.let { Fees.halfSpreadOf(it.bidPrice, it.askPrice) } ?: (Fees.DEFAULT_SPREAD_IR / 2)
            MarketKind.METAL -> asset?.let { Fees.halfSpreadOf(it.bidPrice, it.askPrice) } ?: (Fees.DEFAULT_SPREAD_CRYPTO / 2)
            MarketKind.FX -> 0.0
        }
    }

    /** دلیل ممنوعیت معامله سهام در این لحظه (بسته بودن بازار یا صف)، یا null اگر مجاز است. */
    private fun irBlock(asset: Asset, buy: Boolean): String? {
        // سهام و صندوق‌های طلای بورسی (هر دو در TSETMC با شناسه ir:)
        if (asset.market != MarketKind.IR_STOCK && !asset.id.startsWith("ir:")) return null
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

    /** وضعیت هر بازار در آخرین دور (چرا خرید/فروش شد یا نشد). */
    @Volatile
    var lastActivity: List<MarketActivity> = emptyList()
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
                assetId.startsWith("nbx:") -> MarketKind.METAL
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

    /** روند کلی هر بازار (شاخص هم‌وزن، پهنای بازار، پیش‌بینی) از داده‌های کش‌شده. */
    fun marketTrends(): List<com.saeidkazemi.trader.analysis.MarketTrendReport> {
        val all = market.cachedAssets()
        val settings = store.loadSettings()
        return MarketKind.TRADED.mapNotNull { m ->
            try {
                val list = all.filter { it.market == m && !it.isDisplayOnly }
                val live = list.filter { !it.isSimulated }
                val use = if (live.isNotEmpty()) live else list
                val hist = use.associate { it.id to market.cachedHistory(it.id).orEmpty() }
                com.saeidkazemi.trader.analysis.MarketTrend.build(m, use, hist, marketFacts(m, settings))
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun marketFacts(m: MarketKind, settings: AppSettings): List<String> {
        val f = ArrayList<String>()
        when (m) {
            MarketKind.CRYPTO -> {
                market.insights.cachedFearGreed()?.let { g ->
                    f.add("شاخص ترس و طمع: " + g.value + " (" + (fearGreedFa(g.label) ?: g.label) + ")" +
                        when {
                            g.value <= 25 -> " — ترس زیاد؛ معمولاً نزدیک کف‌ها"
                            g.value >= 75 -> " — طمع زیاد؛ ریسک اصلاح بالاتر"
                            else -> ""
                        })
                }
                val btc = market.cachedHistory("bitcoin").orEmpty().filter { it.price > 0 }
                if (btc.size >= 50) {
                    val closes = btc.map { it.price }
                    val s50 = closes.takeLast(50).average()
                    val c30 = com.saeidkazemi.trader.analysis.MarketTrend.changeOver(btc, 30)
                    f.add("بیت‌کوین " + (if (closes.last() > s50) "بالای" else "زیر") + " میانگین ۵۰ روزه" +
                        (c30?.let { " • ۳۰ روز " + Format.pct(it) } ?: ""))
                }
            }
            MarketKind.IR_STOCK -> {
                val scan = market.lastIranScan
                if (scan != null && !scan.live) f.add("⚠ TSETMC در دسترس نیست؛ روند بورس روی داده شبیه‌سازی‌شده است")
                market.iranStats?.let { st ->
                    val b = st.breadth
                    f.add("امروز: " + st.advancers + " نماد مثبت، " + st.decliners + " نماد منفی" +
                        (b?.let { " (" + Format.num(it * 100, 0) + "٪ مثبت)" } ?: ""))
                    st.realNetIrr?.let { v ->
                        f.add((if (v >= 0) "ورود" else "خروج") + " پول حقیقی کل بازار: " + Format.compactIrr(kotlin.math.abs(v) / 10) + " تومان")
                    }
                    if (st.totalValueIrr > 0) f.add("ارزش معاملات سهام: " + Format.compactIrr(st.totalValueIrr / 10) + " تومان")
                }
                if (scan != null && scan.live) f.add("صف خرید: " + scan.buyQueues + " • صف فروش: " + scan.sellQueues)
            }
            MarketKind.FX -> {
                f.add("شاخص بالا رفتن یعنی ضعیف شدن دلار در برابر این ارزها (و برعکس)")
                f.add("نرخ دلار بازار آزاد: " + Format.num(market.usdIrr(settings), 0) + " ریال" + (if (market.rateIsFallback()) " (پشتیبان)" else ""))
            }
            MarketKind.METAL -> {
                val all = market.cachedAssets()
                val usdt = all.firstOrNull { it.id == "nbx:usdt" }
                val paxg = all.firstOrNull { it.id == "nbx:paxg" }
                val xau = all.firstOrNull { it.id == "metal:XAU" }
                usdt?.let { a ->
                    f.add("دلار (تتر نوبیتکس): " + Format.num(a.price / 10, 0) + " تومان" +
                        (a.changePct24h?.let { " • ۲۴ساعت " + Format.pct(it) } ?: ""))
                }
                xau?.let { f.add("انس جهانی طلا: " + Format.num(it.price, 0) + " دلار") }
                if (paxg != null && xau != null && xau.price > 0) {
                    val implied = xau.price * market.usdIrr(settings)
                    if (implied > 0) {
                        val prem = (paxg.price / implied - 1) * 100
                        f.add("اختلاف طلای PAXG با انس جهانی × دلار: " + Format.pct(prem) +
                            (if (kotlin.math.abs(prem) > 3) " (حباب/کسری قابل‌توجه)" else ""))
                    }
                }
                val funds = all.count { it.market == MarketKind.METAL && it.id.startsWith("ir:") && !it.isSimulated }
                f.add(if (funds > 0) "صندوق‌های طلای بورسی در تحلیل: " + funds + " (فقط ساعت بازار بورس معامله می‌شوند)"
                    else "صندوق‌های طلای بورسی: داده TSETMC در دسترس نیست (فقط تتر و PAXG)")
            }
        }
        return f
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
        guardNote: String?,
        spreadPct: Double? = null
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
                    entryNative = asset.price * (1 + (spreadPct ?: 0.0) / 100),
                    buySpreadPct = spreadPct,
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
                        "[" + n.kind.faTitle + " • " + n.sentimentLabel + "] " + n.displayTitle + (if (n.titleFa != null) " (اصل: " + n.title + ")" else "")
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
        sig: Signal?,
        spreadPct: Double? = null
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
                    exitNative = asset?.price?.let { it * (1 - (spreadPct ?: 0.0) / 100) },
                    sellSpreadPct = spreadPct,
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
                val cur = asset?.let { usdPriceFor(it, settings) } ?: pos.avgBuyUsd
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
                    sellFee = feeFor(asset, pos.market, false, settings) + halfSpread(asset, pos.market),
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

    /**
     * نسخه ۱.۶: بازار «طلا و دلار» اضافه شد. برای کاربران قبلی سهم فارکس (که فقط شبیه‌سازی بود) به طلا و دلار
     * منتقل می‌شود؛ حساب از نو ساخته نمی‌شود و سود/زیان قبلی حفظ می‌شود.
     */
    private fun migrateMetal(notes: MutableList<String>) {
        val s = store.loadSettings()
        if (!s.allocations.containsKey(MarketKind.METAL.name)) {
            val fxPct = s.allocationPct(MarketKind.FX)
            store.saveSettings(
                s.copy(
                    allocations = s.allocations + (MarketKind.METAL.name to fxPct) + (MarketKind.FX.name to 0.0),
                    marketRisk = s.marketRisk + (MarketKind.METAL.name to (s.marketRisk[MarketKind.METAL.name] ?: "MED"))
                )
            )
            notes.add("بازار جدید «طلا و دلار» فعال شد و سهم " + Format.num(fxPct, 0) + "٪ فارکس به آن رسید (تتر، طلای PAXG و صندوق‌های طلای بورسی).")
        }
        // نقد باقی‌مانده فارکس (وقتی سهمش صفر است و موقعیت بازی ندارد) به طلا و دلار منتقل می‌شود.
        val cur = store.loadSettings()
        if (cur.allocationPct(MarketKind.FX) <= 0.0 && cur.allocationPct(MarketKind.METAL) > 0.0 &&
            (broker.account().cashByMarket[MarketKind.FX.name] ?: 0.0) > 0.01
        ) {
            val moved = broker.moveSleeve(MarketKind.FX, MarketKind.METAL)
            if (moved != null && moved > 0) {
                notes.add("نقد بخش فارکس ($" + Format.money(moved) + ") به بخش طلا و دلار منتقل شد؛ حساب از نو ساخته نشد و سود قبلی حفظ شد.")
            }
        }
    }

    /**
     * حالت آینه (اتصال به دستگاه اصلی): این دستگاه فقط تحلیل و نمایش می‌کند؛ خرید، فروش، حد ضرر متحرک،
     * قفل سود و بک‌تست را دستگاه اصلی انجام می‌دهد و حساب و ژورنال از آن دریافت می‌شود.
     */
    @Volatile
    var followerMode: Boolean = false

    /** جایگزینی وضعیت با وضعیت دستگاه اصلی (زیر همان قفل دور معامله تا تداخلی پیش نیاید). */
    suspend fun applyMirror(
        settings: AppSettings,
        account: AccountState,
        journalEntries: List<com.saeidkazemi.trader.data.model.JournalEntry>,
        report: Backtest.Report?,
        activity: List<MarketActivity>
    ) = mutex.withLock {
        store.saveSettings(settings)
        broker.replaceAccount(account)
        journal.replaceAll(journalEntries)
        backtest = report
        if (report != null) try { store.saveBacktest(report) } catch (_: Exception) { }
        lastActivity = activity
    }

    /** دفتر خودارزیابی (پیش‌بینی‌ها و مشکلات)؛ در AppContainer تنظیم می‌شود. */
    var review: com.saeidkazemi.trader.review.ReviewLog? = null

    /** ثبت پیش‌بینی‌ها، نتیجه پیش‌بینی‌های قبلی و مشکلات این دور برای گزارش خودارزیابی. */
    private fun recordReview(
        rl: com.saeidkazemi.trader.review.ReviewLog,
        cycleStart: Long,
        trigger: String,
        settings: AppSettings,
        assets: List<Asset>,
        signals: List<Signal>,
        histories: Map<String, List<PricePoint>>,
        notes: List<String>,
        buys: Int,
        sells: Int
    ) {
        val now = System.currentTimeMillis()
        val irOpen = IranMarket.isOpen(now)
        val native = HashMap<String, Double>()
        for (a in assets) if (!a.isSimulated && a.price.isFinite() && a.price > 0) native[a.id] = a.price
        rl.evaluate(native, irOpen, now)
        val held = broker.account().positions.map { it.assetId }.toSet()
        val assetMap = assets.associateBy { it.id }
        val real = signals.filter { !it.isSimulated }
        val pick = LinkedHashSet<String>()
        for (m in MarketKind.values()) {
            val ms = real.filter { it.market == m }.sortedByDescending { it.score }
            if (m == MarketKind.IR_STOCK) {
                ms.take(30).forEach { pick.add(it.assetId) }
                ms.takeLast(10).forEach { pick.add(it.assetId) }
            } else ms.forEach { pick.add(it.assetId) }
        }
        real.filter { it.assetId in held }.forEach { pick.add(it.assetId) }
        val sigMap = real.associateBy { it.assetId }
        val items = ArrayList<com.saeidkazemi.trader.review.Prediction>()
        for (id in pick) {
            val sig = sigMap[id] ?: continue
            val asset = assetMap[id] ?: continue
            if (asset.isSimulated || asset.price <= 0 || !asset.price.isFinite()) continue
            if (asset.market == MarketKind.IR_STOCK && !irOpen) continue
            if (now - rl.lastPredictionAt(id) < com.saeidkazemi.trader.review.ReviewLog.PREDICTION_GAP_MS) continue
            val fc = try {
                com.saeidkazemi.trader.analysis.Forecast.build(histories[id].orEmpty(), asset.price, now, sig.score, false)
            } catch (_: Exception) { null }
            val factors = sig.proFactors.filter { it.impact != 0 }
                .sortedByDescending { kotlin.math.abs(it.impact) }.take(6)
                .associate { it.title to it.impact }
            items.add(
                com.saeidkazemi.trader.review.Prediction(
                    ts = now,
                    assetId = id,
                    symbol = asset.symbol,
                    market = asset.market.name,
                    price = asset.price,
                    score = sig.score,
                    tech = sig.technicalScore,
                    news = sig.newsAdj,
                    pro = sig.proAdj,
                    th = settings.buyThreshold + planFor(settings, asset.market).buyThresholdDelta,
                    sellTh = settings.sellThreshold,
                    action = sig.action.name,
                    fcExp = fc?.expectedPct?.takeIf { it.isFinite() },
                    fcUp = fc?.probUp?.takeIf { it.isFinite() },
                    factors = factors.ifEmpty { null },
                    held = id in held
                )
            )
        }
        rl.record(items)
        val problems = rl.ingestNotes(notes)
        store.lastWriteError?.let { rl.issue(com.saeidkazemi.trader.review.ReviewLog.AREA_STORAGE, "خطای ذخیره فایل: $it", now) }
        val realBy = HashMap<String, Int>()
        val simBy = HashMap<String, Int>()
        for (a in assets) {
            if (a.isDisplayOnly) continue
            val map = if (a.isSimulated) simBy else realBy
            map[a.market.name] = (map[a.market.name] ?: 0) + 1
        }
        rl.cycle(
            com.saeidkazemi.trader.review.CycleDiag(
                ts = now, trigger = trigger, ms = now - cycleStart, assets = assets.size,
                real = realBy, sim = simBy, signals = signals.size, buys = buys, sells = sells, problems = problems
            )
        )
        rl.maybeSave()
    }

    suspend fun runCycle(trigger: String): CycleReport = mutex.withLock {
        val manage = !followerMode
        val cycleStart = System.currentTimeMillis()
        if (manage) ensureJournal()
        val buys = mutableListOf<String>()
        val sells = mutableListOf<String>()
        val notes = mutableListOf<String>()
        if (manage) try {
            migrateMetal(notes)
        } catch (_: Exception) {
        }
        val settings = store.loadSettings()

        val heldIds = broker.account().positions.map { it.assetId }.toSet()
        val result = try {
            market.refresh(
                settings, heldIds,
                broker.account().positions.filter { it.market == MarketKind.CRYPTO }.map { Triple(it.assetId, it.symbol, it.name) }
            )
        } catch (e: Exception) {
            notes.add("خطا در به‌روزرسانی بازار: " + (e.message ?: ""))
            RefreshResult(market.cachedAssets(), emptyList())
        }
        val assets = result.assets
        notes.add(dataStatusLine(assets, settings))
        notes.addAll(result.notes)
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

        if (manage) migrateFxLocks(settings, notes)

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
                notes.add("داده حقیقی/حقوقی بورس دریافت نشد؛ فقط عامل «جریان پول» سهام در این دور حساب نشد (بقیه تحلیل و معامله ادامه دارد).")
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
        val heldPositions = broker.account().positions
        val priceMap = assets.associate { it.id to usdPriceFor(it, settings, heldPositions) }

        // ۵) حد ضرر متحرک: با هر قله تازه، حد ضرر بالا کشیده می‌شود تا سود قفل شود.
        if (manage) broker.trail(priceMap)

        // ۵-ب) قفل سود: وقتی سود خالص (پس از کارمزد) به آستانه (پیش‌فرض ۱۰٪) رسید، حد ضرر روی قیمتی می‌رود
        // که فروش در آن همان سود (پیش‌فرض ۱۰٪) را حفظ کند. از آن به بعد، معامله دیگر نمی‌تواند زیان‌ده یا کم‌سودتر بسته شود
        // (مگر پرش ناگهانی قیمت یا صف فروش). اگر قیمت باز هم بالا برود، حد ضرر متحرک آن را بالاتر می‌برد.
        if (settings.profitLock && manage) {
            for (pos in broker.account().positions) {
                val cur = priceMap[pos.assetId] ?: continue
                if (!cur.isFinite() || cur <= 0) continue
                val a = assetMap[pos.assetId]
                val bf = feeFor(a, pos.market, true, settings)
                // فروش به بهترین قیمت خریدار انجام می‌شود، پس نصف اسپرد هم جزو هزینه فروش است
                val sf = feeFor(a, pos.market, false, settings) + halfSpread(a, pos.market)
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
        for (pos in if (manage) broker.account().positions else emptyList()) {
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
                holdExpired(pos, settings) -> "پایان مهلت نگهداری " + planFor(settings, pos.market).maxHoldDays + " روزه (طبق بک‌تست)"
                // یک تیتر منفی به‌تنهایی کافی نیست: جمع اثر اخبار هم باید منفی باشد، و برای طلا/دلار
                // (که تیترهای عمومی زیادی دارند) امتیاز کلی هم باید زیر آستانه خرید رفته باشد.
                sig != null && sig.newsBlocked && sig.newsAdj < 0 &&
                    ((pos.market != MarketKind.METAL && pos.market != MarketKind.FX) ||
                        sig.score < settings.buyThreshold + planFor(settings, pos.market).buyThresholdDelta) ->
                    "خروج به‌خاطر خبر منفی مهم"
                sig != null && sig.proBlocked && sig.score < settings.buyThreshold - 10 -> "خروج به‌خاطر شرایط تخصصی: " + (sig.proBlockReason ?: "")
                sig != null && sig.score <= settings.sellThreshold && planFor(settings, pos.market).entryMode == 0 -> "ضعیف شدن سیگنال (امتیاز " + sig.score + ")"
                else -> null
            }
            if (reason != null) {
                val blocked = irBlock(asset, buy = false)
                if (blocked != null) {
                    if (IranMarket.isOpen()) notes.add("فروش " + pos.symbol + " (" + reason + ") ممکن نشد: " + blocked + ".")
                    continue
                }
                announce("SELL", asset, pos.qty * curUsd, reason)
                val hs = halfSpread(asset, null)
                val trade = broker.sell(pos.assetId, curUsd * (1 - hs), feeFor(asset, null, false, settings), reason)
                if (trade != null) {
                    sells.add(pos.symbol)
                    journalClose(pos, asset, trade, reason, sig, spreadPct = hs * 100)
                    val pnl = trade.usdValue - trade.feeUsd - pos.cost()
                    maybeRealSell(settings, asset, pos.qty, notes)
                    completed("SELL", asset, true, "فروش " + pos.symbol + " — " + reason, pnl)
                } else {
                    completed("SELL", asset, false, "فروش " + pos.symbol + " انجام نشد")
                }
            }
        }

        // ۷) خرید خودکار — هر بازار جدا و فقط با سرمایه اختصاصی خودش (بدون تأیید موردی)
        val activity = ArrayList<MarketActivity>()
        if (settings.autoTrade && manage) {
            for (m in riskManager.tradableMarkets) {
                if (settings.allocationPct(m) <= 0.0) continue
                val plan = planFor(settings, m)
                val skipped = LinkedHashMap<String, Int>()
                fun skip(r: String) { skipped[r] = (skipped[r] ?: 0) + 1 }
                var stopReason: String? = null
                var boughtHere = 0
                val acc0 = broker.account()
                val equity = sleeveEquity(acc0, m, priceMap)
                val reserve = equity * plan.cashReservePct
                // محافظ نرخ برد: اگر معاملات اخیر این بازار کم‌برد و زیان‌ده بوده، سخت‌گیرتر و با حجم کمتر خرید می‌شود
                val guard = com.saeidkazemi.trader.analysis.Performance.guard(journal.all(), m, settings)
                val th = settings.buyThreshold + plan.buyThresholdDelta
                if (guard.active) notes.add("محافظ نرخ برد (" + m.faTitle + "): " + guard.text + ".")
                // فیلتر ورود بک‌تست: پهنای بازار = سهم دارایی‌های این بازار بالای میانگین ۲۰ روزه
                val breadthNow: Double? = if ((plan.entryFilter and 2) != 0) {
                    val hs = assets.filter { it.market == m && !it.isDisplayOnly && !it.isSimulated }
                        .mapNotNull { a -> histories[a.id]?.map { it.price }?.takeIf { it.size >= 20 } }
                    if (hs.size < 3) null else hs.count { v -> v.last() > v.takeLast(20).average() }.toDouble() / hs.size
                } else null
                if (plan.entryMode == 1) notes.add(m.faTitle + ": حالت ورود بک‌تست = خرید در اصلاح (RSI زیر " + Format.trim(plan.rsiMax, 0) + ")" +
                    (if (plan.entryFilter != 0) " با فیلتر " + Backtest.filterLabel(plan.entryFilter) else "") + ".")
                for (sig in signals) {
                    if (sig.market != m) continue
                    val entryOk = if (plan.entryMode == 1) {
                        (sig.metrics.rsi ?: 100.0) < plan.rsiMax && !sig.newsBlocked && !sig.proBlocked
                    } else sig.action == Action.BUY
                    if (!entryOk) continue
                    val acc = broker.account()
                    if (acc.positions.count { it.market == m } >= plan.maxPositions) {
                        stopReason = "سقف " + plan.maxPositions + " موقعیت همزمان این بازار پر است؛ خرید بعدی بعد از فروش یکی از موقعیت‌ها"
                        break
                    }
                    if (acc.positions.any { it.assetId == sig.assetId }) { skip("از قبل در پرتفوی است"); continue }
                    if (sells.contains(sig.symbol)) { skip("همین دور فروخته شد"); continue }
                    val asset = assetMap[sig.assetId] ?: continue
                    // روی داده شبیه‌سازی‌شده خودکار خرید نمی‌شود تا سود/زیان دمو واقعی بماند.
                    if (asset.isSimulated) { skip("داده شبیه‌سازی‌شده (منبع اصلی در دسترس نیست)"); continue }
                    // سهام: فقط در ساعت کار بازار و وقتی نماد در صف خرید نیست.
                    val blockedWhy = irBlock(asset, buy = true)
                    if (blockedWhy != null) {
                        skip(if (blockedWhy.startsWith("نماد")) "در صف خرید است" else blockedWhy)
                        continue
                    }
                    // خرید خودکار سهمِ در صف فروش ممنوع: فروشنده‌ها روی کف قیمت صف کشیده‌اند و فروش بعدی ممکن است روزها طول بکشد.
                    if (asset.sellQueue) { skip("در صف فروش است"); continue }
                    if (plan.entryFilter != 0) {
                        val v = histories[asset.id]?.map { it.price }.orEmpty()
                        val above100 = if (v.size >= 100) v.last() > v.takeLast(100).average() else null
                        if (!Backtest.filterOk(plan.entryFilter, above100, breadthNow)) {
                            skip("فیلتر بک‌تست: " + Backtest.filterLabel(plan.entryFilter) + " برقرار نیست")
                            continue
                        }
                    }
                    if (plan.entryMode == 0 && guard.active && sig.score < th + com.saeidkazemi.trader.analysis.Performance.GUARD_EXTRA_THRESHOLD) {
                        skip("محافظ نرخ برد: امتیاز کمتر از " + (th + com.saeidkazemi.trader.analysis.Performance.GUARD_EXTRA_THRESHOLD))
                        continue
                    }
                    val usdPrice = priceMap[asset.id]
                    if (usdPrice == null || !usdPrice.isFinite() || usdPrice <= 0) { skip("قیمت معتبر در دسترس نیست"); continue }
                    val budget = equity * plan.positionPct *
                        (if (guard.active) com.saeidkazemi.trader.analysis.Performance.GUARD_SIZE_FACTOR else 1.0)
                    val available = (acc.cashByMarket[m.name] ?: 0.0) - reserve
                    var amount = minOf(budget, available)
                    var sizeNote = ""
                    // سهم کوچک: اگر درصد هر موقعیت از حداقل معامله کمتر شد ولی نقد کافی هست، با حداقل مبلغ خرید می‌شود
                    // (به‌جای اینکه این بازار هیچ‌وقت معامله نکند). در عمل تعداد موقعیت‌ها کمتر از سقف می‌شود.
                    if (amount < plan.minTradeUsd && available >= plan.minTradeUsd) {
                        amount = plan.minTradeUsd
                        sizeNote = "، حداقل مبلغ معامله چون سهم این بازار کوچک است"
                    } else if (amount < plan.minTradeUsd && available >= LEFTOVER_MIN_USD) {
                        // باقی‌مانده نقد (بین ۴ تا ۱۰ دلار) بیکار نماند: کل آن خرج یک خرید می‌شود
                        // (۴ دلار بالاتر از حداقل سفارش نوبیتکس، ۳۰۰ هزار تومان، است)
                        amount = available
                        sizeNote = "، کل نقد باقی‌مانده این بازار"
                    }
                    if (amount < LEFTOVER_MIN_USD) {
                        val held = acc.positions.count { it.market == m }
                        stopReason = "نقد آزاد این بازار ($" + Format.num(maxOf(0.0, available)) + " پس از ذخیره نقدی) کمتر از حداقل معامله ($" +
                            Format.num(LEFTOVER_MIN_USD, 0) + ") است؛ " +
                            (if (held > 0) "خرید بعدی بعد از فروش یکی از " + held + " موقعیت فعلی"
                            else "برای معامله در این بازار سهم آن را در تنظیمات ← تقسیم سرمایه بیشتر کنید")
                        break
                    }
                    val newsPart = if (sig.newsAdj != 0) "، اخبار " + ProAnalysis.signed(sig.newsAdj) else ""
                    val proPart = if (sig.proAdj != 0) "، تخصصی " + ProAnalysis.signed(sig.proAdj) else ""
                    val rrPart = plan.plannedRR(sig.metrics.volatility).takeIf { it > 0 }?.let { "، ریسک به ریوارد ۱:" + Format.trim(it, 1) } ?: ""
                    val reason = "خرید خودکار (" + (if (plan.entryMode == 1) "خرید در اصلاح، RSI " + Format.num(sig.metrics.rsi ?: 0.0, 0) + "، " else "") + "امتیاز " + sig.score + newsPart + proPart + rrPart + (if (plan.tuned) "، پارامتر بک‌تست" else "") + sizeNote + ")"
                    val stopPct = plan.stopFor(sig.metrics.volatility)
                    announce("BUY", asset, amount, reason)
                    val hs = halfSpread(asset, null)
                    val trade = broker.buy(
                        asset = asset,
                        usdPrice = usdPrice * (1 + hs),
                        usdAmount = amount,
                        feePct = feeFor(asset, null, true, settings),
                        stopLossUsd = usdPrice * (1 - stopPct),
                        takeProfitUsd = usdPrice * (1 + plan.tpPct),
                        reason = reason,
                        trailPct = plan.trailPct,
                        fxRate = if (asset.baseCurrency == "IRR") usdIrr else 0.0
                    )
                    if (trade != null) {
                        buys.add(asset.symbol)
                        boughtHere++
                        journalOpen(
                            asset, trade, sig, settings,
                            stopUsd = usdPrice * (1 - stopPct), tpUsd = usdPrice * (1 + plan.tpPct), trailPct = plan.trailPct,
                            auto = true, threshold = th, digest = digests[asset.id], hist = histories[asset.id].orEmpty(),
                            guardNote = if (guard.active) guard.text else null,
                            spreadPct = hs * 100
                        )
                        maybeRealBuy(settings, asset, amount, usdIrr, notes)
                        completed("BUY", asset, true, "خرید " + asset.symbol + " به مبلغ " + Format.num(amount) + " دلار")
                    } else {
                        completed("BUY", asset, false, "خرید " + asset.symbol + " انجام نشد")
                    }
                }
                activity.add(
                    buildActivity(m, settings, plan.maxPositions, LEFTOVER_MIN_USD, th, reserve, signals, priceMap, boughtHere, sells, skipped, stopReason)
                )
            }
            if (signals.isEmpty()) notes.add("دارایی با داده کافی برای تحلیل پیدا نشد.")
        }
        if (manage) {
            lastActivity = activity
            store.saveAccount(broker.account())
        }
        tracker.record(broker.account().positions, priceMap)
        if (manage) maybeStartBacktest()
        val rl = review
        if (manage && rl != null) {
            try {
                recordReview(rl, cycleStart, trigger, settings, assets, signals, histories, notes.distinct(), buys.size, sells.size)
            } catch (_: Exception) {
            }
        }
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
        val hs = halfSpread(asset, null)
        val trade = broker.buy(
            asset = asset,
            usdPrice = usdPrice * (1 + hs),
            usdAmount = usdAmount,
            feePct = feeFor(asset, null, true, settings),
            stopLossUsd = usdPrice * (1 - stopPct),
            takeProfitUsd = usdPrice * (1 + plan.tpPct),
            reason = "خرید دستی",
            trailPct = plan.trailPct,
            fxRate = if (asset.baseCurrency == "IRR") market.usdIrr(settings) else 0.0
        )
        if (trade == null) {
            completed("BUY", asset, false, "خرید " + asset.symbol + " انجام نشد")
            return "خرید انجام نشد."
        }
        tracker.record(broker.account().positions, market.cachedAssets().associate { it.id to usdPriceFor(it, settings) })
        ensureJournal()
        journalOpen(
            asset, trade, lastSignals.firstOrNull { it.assetId == assetId }, settings,
            stopUsd = usdPrice * (1 - stopPct), tpUsd = usdPrice * (1 + plan.tpPct), trailPct = plan.trailPct,
            auto = false, threshold = settings.buyThreshold + plan.buyThresholdDelta,
            digest = news.cached(assetId), hist = market.cachedHistory(assetId).orEmpty(), guardNote = null,
            spreadPct = hs * 100
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
        val usdPrice = if (asset != null) usdPriceFor(asset, settings) else pos.avgBuyUsd
        if (asset != null) irBlock(asset, buy = false)?.let { return "فروش ممکن نیست: " + it + "." }
        announce("SELL", pos.symbol, pos.market, pos.qty * usdPrice, "فروش دستی")
        val hs = halfSpread(asset, pos.market)
        val trade = broker.sell(assetId, usdPrice * (1 - hs), feeFor(asset, pos.market, false, settings), "فروش دستی")
        if (trade == null) {
            completed("SELL", pos.symbol, pos.market, false, "فروش " + pos.symbol + " انجام نشد")
            return "فروش انجام نشد."
        }
        ensureJournal()
        journalClose(pos, asset, trade, "فروش دستی", lastSignals.firstOrNull { it.assetId == assetId }, spreadPct = hs * 100)
        completed("SELL", pos.symbol, pos.market, true, "فروش دستی " + pos.symbol,
            trade.usdValue - trade.feeUsd - pos.cost())
        if (settings.realTrading && asset != null) {
            val notes = mutableListOf<String>()
            maybeRealSell(settings, asset, pos.qty, notes)
            if (notes.isNotEmpty()) return "فروش در دفتر ثبت شد. " + notes.joinToString(" ")
        }
        return "فروش " + pos.symbol + " با قیمت " + Format.num(trade.priceUsd) + " دلار ثبت شد."
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
        if ((asset.market != MarketKind.CRYPTO && !asset.id.startsWith("nbx:")) || sym == null) {
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
        if ((asset.market != MarketKind.CRYPTO && !asset.id.startsWith("nbx:")) || sym == null) {
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
