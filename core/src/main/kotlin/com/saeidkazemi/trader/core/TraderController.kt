package com.saeidkazemi.trader.core

import com.saeidkazemi.trader.data.model.CycleReport
import com.saeidkazemi.trader.ui.AssetDetail
import com.saeidkazemi.trader.ui.NewsFeedEntry
import com.saeidkazemi.trader.ui.PlatformInfo
import com.saeidkazemi.trader.ui.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * نگهدارنده وضعیت و منطق صفحه‌ها — مشترک بین اندروید و ویندوز.
 *
 * - در اندروید، حلقه ۶۰ثانیه‌ای معامله در سرویس پیش‌زمینه اجرا می‌شود (runsOwnLoop = false)
 * - در ویندوز، خود کنترلر حلقه را اجرا می‌کند (runsOwnLoop = true) و برنامه در System Tray می‌ماند.
 *
 * هر دو حالت گزارش دورها را از [com.saeidkazemi.trader.trading.TradeEngine.reports] دریافت می‌کنند،
 * بنابراین صفحه‌ها همیشه آخرین وضعیت را نشان می‌دهند.
 */
class TraderController(
    val container: AppContainer,
    private val scope: CoroutineScope,
    private val hooks: PlatformHooks
) {

    /** رفتارهای مخصوص هر پلتفرم. */
    interface PlatformHooks {
        val runsOwnLoop: Boolean
        fun platformInfo(): PlatformInfo
        fun onAutoTradeChanged(on: Boolean) {}
        fun onCycleReport(report: CycleReport) {}
        fun setAutostart(on: Boolean): Boolean = false

        /** درخواست معافیت از بهینه‌سازی باتری (اندروید) تا با قفل بودن گوشی هم کار کند. */
        fun requestBatteryExemption() {}

        /** باز کردن تنظیمات برنامه در سیستم (برای اجرای خودکار/باتری در گوشی‌های شیائومی، هواوی، …). */
        fun openAppSettings() {}
    }

    private val _state = MutableStateFlow(UiState(platform = hooks.platformInfo()))
    val state: StateFlow<UiState> = _state

    private var started = false
    private var loopJob: Job? = null

    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) {
            container.tradeEngine.reports.collect { report -> syncFromEngine(report) }
        }
        scope.launch(Dispatchers.IO) {
            val settings = container.store.loadSettings()
            _state.update {
                it.copy(settings = settings, account = container.broker.account(), loading = true)
            }
            if (settings.autoTrade) hooks.onAutoTradeChanged(true)
            runCycleInternal("initial")
        }
        if (hooks.runsOwnLoop) {
            loopJob = scope.launch(Dispatchers.IO) {
                delay(AppContainer.CYCLE_MS)
                while (isActive) {
                    if (container.store.loadSettings().autoTrade) runCycleInternal("auto")
                    delay(AppContainer.CYCLE_MS)
                }
            }
        }
    }

    /** یک دور کامل: به‌روزرسانی بازار، تحلیل، اخبار، مدیریت ریسک و (در حالت خودکار) خرید و فروش. */
    fun refresh() {
        scope.launch(Dispatchers.IO) {
            _state.update { it.copy(refreshing = true) }
            runCycleInternal("manual")
        }
    }

    private suspend fun runCycleInternal(trigger: String) {
        try {
            val report = container.tradeEngine.runCycle(trigger)
            syncFromEngine(report)
            hooks.onCycleReport(report)
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    error = "خطا در اجرای موتور: " + (e.message ?: "")
                )
            }
        }
    }

    private fun syncFromEngine(report: CycleReport) {
        val settings = container.store.loadSettings()
        val market = container.marketDataService
        val assets = market.cachedAssets()
        val priceMap = HashMap<String, Double>()
        for (a in assets) priceMap[a.id] = market.usdPriceOf(a, settings)
        val digests = container.tradeEngine.cachedNews().associateBy { it.assetId }
        val journal = container.tradeEngine.journal.all()
        val trends = container.tradeEngine.marketTrends()
        _state.update {
            it.copy(
                loading = false,
                refreshing = false,
                error = null,
                notes = report.notes,
                assets = assets,
                signals = container.tradeEngine.lastSignals,
                priceMap = priceMap,
                account = container.broker.account(),
                settings = settings,
                usdIrr = market.usdIrr(settings),
                rateIsFallback = market.rateIsFallback(),
                lastCycle = report,
                newsDigests = digests,
                newsFeed = buildFeed(digests),
                outlooks = container.tradeEngine.outlooks(),
                journal = journal,
                perf = com.saeidkazemi.trader.analysis.Performance.report(journal, settings),
                marketTrends = trends
            )
        }
    }

    private fun buildFeed(digests: Map<String, com.saeidkazemi.trader.news.NewsDigest>): List<NewsFeedEntry> {
        val assets = container.marketDataService.cachedAssets().associateBy { it.id }
        val out = mutableListOf<NewsFeedEntry>()
        for ((id, d) in digests) {
            val a = assets[id] ?: continue
            for (item in d.items.take(8)) {
                out.add(NewsFeedEntry(id, a.symbol, a.name, a.market, item))
            }
        }
        return out.sortedByDescending { it.item.publishedAt ?: 0L }.take(150)
    }

    /** دریافت دوباره اخبار همه دارایی‌های مهم (پرتفوی + بهترین سیگنال‌ها). */
    fun refreshNews() {
        scope.launch(Dispatchers.IO) {
            _state.update { it.copy(newsRefreshing = true) }
            try {
                val held = container.broker.account().positions.map { it.assetId }.toSet()
                val top = container.tradeEngine.lastSignals.take(12).map { it.assetId }.toSet()
                val targets = container.marketDataService.cachedAssets()
                    .filter { !it.isDisplayOnly && (it.id in held || it.id in top) }
                container.newsService.digests(targets, force = true)
                val digests = container.tradeEngine.cachedNews().associateBy { it.assetId }
                _state.update { it.copy(newsDigests = digests, newsFeed = buildFeed(digests), newsRefreshing = false) }
                toast("اخبار " + targets.size + " دارایی به‌روزرسانی شد. اثر آن در دور بعدی تحلیل اعمال می‌شود.")
            } catch (e: Exception) {
                _state.update { it.copy(newsRefreshing = false) }
                toast("دریافت اخبار ناموفق بود: " + (e.message ?: ""))
            }
        }
    }

    // ---- تنظیمات ----

    fun toggleAutoTrade(on: Boolean) {
        scope.launch(Dispatchers.IO) {
            val settings = container.store.loadSettings().copy(autoTrade = on)
            container.store.saveSettings(settings)
            _state.update { it.copy(settings = settings) }
            hooks.onAutoTradeChanged(on)
            toast(
                if (on) "معامله‌گر خودکار روشن شد و هر ۶۰ ثانیه بازار و اخبار را بررسی می‌کند."
                else "معامله‌گر خودکار خاموش شد."
            )
        }
    }

    fun toggleNews(on: Boolean) {
        updateSettings { it.copy(newsEnabled = on) }
        toast(if (on) "بررسی اخبار و کدال فعال شد." else "بررسی اخبار غیرفعال شد؛ فقط تحلیل تکنیکال.")
    }

    fun setRiskLevel(level: String) = updateSettings { s ->
        s.copy(riskLevel = level, marketRisk = s.marketRisk.mapValues { level })
    }

    /** سطح ریسک جداگانه یک بازار. */
    fun setMarketRisk(market: com.saeidkazemi.trader.data.model.MarketKind, level: String) =
        updateSettings { it.copy(marketRisk = it.marketRisk + (market.name to level)) }

    fun toggleProAnalysis(on: Boolean) {
        updateSettings { it.copy(proAnalysis = on) }
        toast(if (on) "تحلیل تخصصی (جریان پول، حجم، ارزش‌گذاری، ترس و طمع، …) فعال شد." else "تحلیل تخصصی غیرفعال شد.")
    }

    fun toggleProfitLock(on: Boolean) {
        updateSettings { it.copy(profitLock = on) }
        toast(if (on) "قفل سود فعال شد." else "قفل سود غیرفعال شد؛ فقط حد ضرر عادی و متحرک اعمال می‌شود.")
    }

    /** آستانه قفل سود و سود حفظ‌شده (درصد خالص). */
    fun setProfitLockLevels(triggerPct: Double, keepPct: Double) {
        if (!triggerPct.isFinite() || !keepPct.isFinite() || triggerPct < 1 || triggerPct > 500 || keepPct <= 0) {
            toast("درصدهای معتبر وارد کنید (آستانه بین ۱ تا ۵۰۰).")
            return
        }
        if (keepPct > triggerPct) {
            toast("سود حفظ‌شده نمی‌تواند بیشتر از آستانه باشد.")
            return
        }
        updateSettings { it.copy(profitLockTriggerPct = triggerPct, profitLockKeepPct = keepPct) }
        toast(
            "ذخیره شد: وقتی سود خالص به " + com.saeidkazemi.trader.util.Format.num(triggerPct, 1) + "٪ برسد، حداقل " +
                com.saeidkazemi.trader.util.Format.num(keepPct, 1) + "٪ سود حفظ می‌شود."
        )
    }

    fun toggleWinRateGuard(on: Boolean) {
        updateSettings { it.copy(winRateGuard = on) }
        toast(if (on) "محافظ نرخ برد فعال شد." else "محافظ نرخ برد خاموش شد؛ معاملات بدون توجه به نتایج اخیر ادامه می‌یابند.")
    }

    /** حداقل نرخ برد (درصد) و تعداد معاملات اخیر برای محافظ نرخ برد. */
    fun setWinRateGuard(minWinRatePct: Double, window: Int) {
        if (!minWinRatePct.isFinite() || minWinRatePct < 5 || minWinRatePct > 95 || window < 3 || window > 100) {
            toast("نرخ برد بین ۵ تا ۹۵ و تعداد معاملات بین ۳ تا ۱۰۰ وارد کنید.")
            return
        }
        scope.launch(Dispatchers.IO) {
            val settings = container.store.loadSettings().copy(minWinRatePct = minWinRatePct, guardWindow = window)
            container.store.saveSettings(settings)
            val journal = container.tradeEngine.journal.all()
            _state.update { it.copy(settings = settings, perf = com.saeidkazemi.trader.analysis.Performance.report(journal, settings)) }
            toast(
                "ذخیره شد: اگر نرخ برد " + window + " معامله آخر یک بازار زیر " +
                    com.saeidkazemi.trader.util.Format.num(minWinRatePct, 0) + "٪ و جمعشان زیان‌ده باشد، آن بازار محتاط می‌شود."
            )
        }
    }

    fun toggleSound(on: Boolean) = updateSettings { it.copy(soundAlerts = on) }

    fun toggleVibrate(on: Boolean) = updateSettings { it.copy(vibrateAlerts = on) }

    fun toggleLoud(on: Boolean) = updateSettings { it.copy(loudAlerts = on) }

    /** پخش آزمایشی هر دو صدا (و لرزش) بدون انجام معامله. */
    fun testAlert() {
        scope.launch(Dispatchers.IO) { container.tradeEngine.testAlert() }
    }

    fun requestBatteryExemption() {
        hooks.requestBatteryExemption()
    }

    fun openAppSettings() {
        hooks.openAppSettings()
    }

    /** وضعیت پلتفرم (مثلاً معافیت باتری) پس از بازگشت به برنامه دوباره خوانده می‌شود. */
    fun refreshPlatform() {
        _state.update { it.copy(platform = hooks.platformInfo()) }
    }

    /**
     * تقسیم سرمایه بین بازارها (درصد). چون هر بازار صندوق جداگانه دارد، حساب دمو با همان سرمایه
     * و تقسیم‌بندی جدید از نو ساخته می‌شود.
     */
    fun setAllocationsAndReset(crypto: Double, ir: Double, fx: Double) {
        val vals = listOf(crypto, ir, fx)
        if (vals.any { !it.isFinite() || it < 0 }) {
            toast("درصدهای معتبر وارد کنید.")
            return
        }
        val sum = vals.sum()
        if (sum < 99.5 || sum > 100.5) {
            toast("جمع درصدها باید ۱۰۰ باشد (الان " + com.saeidkazemi.trader.util.Format.num(sum, 1) + ").")
            return
        }
        scope.launch(Dispatchers.IO) {
            val alloc = mapOf("CRYPTO" to crypto, "IR_STOCK" to ir, "FX" to fx)
            val settings = container.store.loadSettings().copy(allocations = alloc)
            container.store.saveSettings(settings)
            container.broker.reset(settings.capitalUsd, alloc)
            container.tradeEngine.journal.clear()
            _state.update { it.copy(settings = settings, account = container.broker.account(), journal = emptyList(), perf = com.saeidkazemi.trader.analysis.PerfReport()) }
            toast("سرمایه بین بازارها تقسیم شد و حساب دمو از نو ساخته شد.")
        }
    }

    fun setFeePct(pct: Double) {
        if (!pct.isFinite()) return
        updateSettings { it.copy(feePct = maxOf(0.0, minOf(0.02, pct))) }
        toast("کارمزد ذخیره شد.")
    }

    fun setCapitalAndReset(amountUsd: Double) {
        if (!amountUsd.isFinite() || amountUsd <= 0) {
            toast("سرمایه معتبر وارد کنید.")
            return
        }
        scope.launch(Dispatchers.IO) {
            val settings = container.store.loadSettings().copy(capitalUsd = amountUsd)
            container.store.saveSettings(settings)
            container.broker.reset(amountUsd, settings.allocations)
            container.tradeEngine.journal.clear()
            _state.update { it.copy(settings = settings, account = container.broker.account(), journal = emptyList(), perf = com.saeidkazemi.trader.analysis.PerfReport()) }
            toast("حساب دمو با سرمایه جدید از نو ساخته شد.")
        }
    }

    fun toggleRealTrading(on: Boolean) {
        updateSettings { it.copy(realTrading = on) }
        if (on) toast("هشدار: معامله واقعی فعال شد. فقط ارزهای متصل به نوبیتکس با پول واقعی معامله می‌شوند.")
    }

    fun saveNobitexToken(token: String) {
        updateSettings { it.copy(nobitexToken = token.trim()) }
        toast("توکن نوبیتکس به‌صورت محلی روی همین دستگاه ذخیره شد.")
    }

    fun setAutostart(on: Boolean) {
        scope.launch(Dispatchers.IO) {
            val ok = hooks.setAutostart(on)
            _state.update { it.copy(platform = hooks.platformInfo()) }
            toast(
                when {
                    !ok -> "تغییر اجرای خودکار ممکن نشد (فقط در نسخه نصب‌شده ویندوز فعال است)."
                    on -> "برنامه با روشن شدن ویندوز به‌صورت خودکار (در System Tray) اجرا می‌شود."
                    else -> "اجرای خودکار با ویندوز خاموش شد."
                }
            )
        }
    }

    private fun updateSettings(change: (com.saeidkazemi.trader.data.model.AppSettings) -> com.saeidkazemi.trader.data.model.AppSettings) {
        scope.launch(Dispatchers.IO) {
            val settings = change(container.store.loadSettings())
            container.store.saveSettings(settings)
            _state.update { it.copy(settings = settings, perf = com.saeidkazemi.trader.analysis.Performance.report(it.journal, settings)) }
        }
    }

    // ---- معاملات دستی ----

    fun manualBuy(assetId: String, usdAmountStr: String) {
        val amount = usdAmountStr.trim().replace(",", "").toDoubleOrNull()
        if (amount == null || !amount.isFinite() || amount <= 0) {
            toast("مبلغ معتبر (دلار) وارد کنید.")
            return
        }
        scope.launch(Dispatchers.IO) {
            val msg = container.tradeEngine.manualBuy(assetId, amount)
            syncAccount()
            toast(msg)
        }
    }

    fun manualSell(assetId: String) {
        scope.launch(Dispatchers.IO) {
            val msg = container.tradeEngine.manualSell(assetId)
            syncAccount()
            toast(msg)
        }
    }

    private fun syncAccount() {
        val held = container.broker.account().positions.map { it.assetId }.toSet()
        val journal = container.tradeEngine.journal.all()
        _state.update { s ->
            s.copy(
                journal = journal,
                perf = com.saeidkazemi.trader.analysis.Performance.report(journal, s.settings),
                account = container.broker.account(),
                outlooks = container.tradeEngine.outlooks(),
                detail = s.detail?.let { d -> d.copy(held = d.asset.id in held) }
            )
        }
    }

    fun openAsset(assetId: String) {
        scope.launch(Dispatchers.IO) {
            val asset = container.marketDataService.cachedAssets().firstOrNull { it.id == assetId }
                ?: return@launch
            val settings = container.store.loadSettings()
            val history = try {
                container.marketDataService.historyFor(asset)
            } catch (e: Exception) {
                emptyList()
            }
            val cachedNews = container.newsService.cached(assetId)
            val signal = try {
                container.tradeEngine.analyzeFull(asset, history, cachedNews)
            } catch (e: Exception) {
                container.strategyEngine.analyze(asset, history, settings, cachedNews)
            }
            val held = container.broker.account().positions.any { it.assetId == assetId }
            val usdPrice = container.marketDataService.usdPriceOf(asset, settings)
            val factor = if (asset.price > 0) usdPrice / asset.price else 1.0
            val forecast = try {
                com.saeidkazemi.trader.analysis.Forecast.build(
                    history.map { com.saeidkazemi.trader.data.model.PricePoint(it.t, it.price * factor) },
                    usdPrice,
                    score = signal?.score,
                    simulated = asset.isSimulated
                )
            } catch (e: Exception) {
                null
            }
            _state.update {
                it.copy(
                    detail = AssetDetail(
                        asset, history, signal, usdPrice, held,
                        news = cachedNews,
                        newsLoading = settings.newsEnabled,
                        forecast = forecast
                    ),
                    outlooks = container.tradeEngine.outlooks()
                )
            }
            if (!settings.newsEnabled) return@launch
            // دریافت اخبار تازه برای همین دارایی و بازسازی سیگنال با اثر اخبار
            val digest = try {
                container.tradeEngine.newsFor(asset)
            } catch (e: Exception) {
                null
            }
            val signal2 = try {
                container.tradeEngine.analyzeFull(asset, history, digest)
            } catch (e: Exception) {
                container.strategyEngine.analyze(asset, history, settings, digest)
            }
            _state.update { s ->
                val d = s.detail
                if (d == null || d.asset.id != assetId) s
                else s.copy(detail = d.copy(news = digest, signal = signal2 ?: d.signal, newsLoading = false))
            }
        }
    }

    fun closeAsset() {
        _state.update { it.copy(detail = null) }
    }

    fun toast(message: String) {
        _state.update { it.copy(toastMessage = message) }
    }

    fun consumeToast() {
        _state.update { it.copy(toastMessage = null) }
    }
}
