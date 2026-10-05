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

        /** پوشه دانلود فایل به‌روزرسانی؛ null یعنی این نسخه به‌روزرسانی داخلی ندارد. */
        fun updateDir(): java.io.File? = null

        /** نوع فایل نصب این پلتفرم: "apk" یا "msi". */
        val updateKind: String get() = ""

        /** اجرای نصب‌کننده؛ خروجی: پیام برای کاربر (یا null اگر نیازی به پیام نیست). */
        fun launchInstaller(file: java.io.File): String? = "نصب خودکار در این نسخه پشتیبانی نمی‌شود."

        /**
         * ذخیره/اشتراک فایل گزارش خودارزیابی (اندروید: پنجره اشتراک‌گذاری، ویندوز: ذخیره روی دسکتاپ)؛
         * خروجی: پیام برای کاربر، یا null اگر این نسخه پشتیبانی نمی‌کند.
         */
        fun shareReport(fileName: String, text: String): String? = null
    }

    private val _state = MutableStateFlow(UiState(platform = hooks.platformInfo()))
    val state: StateFlow<UiState> = _state

    private var started = false
    private var loopJob: Job? = null

    /** وضعیت به‌روزرسانی داخل برنامه. */
    private val _update = MutableStateFlow(com.saeidkazemi.trader.update.UpdateState())
    val updateState: StateFlow<com.saeidkazemi.trader.update.UpdateState> = _update

    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) {
            container.tradeEngine.reports.collect { report -> syncFromEngine(report) }
        }
        scope.launch(Dispatchers.IO) {
            container.sync.status.collect { st -> _state.update { it.copy(sync = st) } }
        }
        scope.launch(Dispatchers.IO) {
            container.sync.events.collect { ev ->
                if (ev == "data") syncFromEngine(null)
                else if (ev.startsWith("msg:")) {
                    captureCode(ev)
                    toast(ev.removePrefix("msg:"))
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            val settings = container.store.loadSettings()
            val account = container.broker.account()
            _state.update {
                it.copy(settings = settings, account = account, loading = true)
            }
            announceUpdate(settings, account)
            if (settings.autoTrade) hooks.onAutoTradeChanged(true)
            runCycleInternal("initial")
        }
        // بررسی خودکار نسخه جدید: کمی بعد از شروع و بعد هر ۶ ساعت
        _update.value = _update.value.copy(supported = hooks.updateDir() != null)
        scope.launch(Dispatchers.IO) { _update.collect { u -> _state.update { it.copy(appUpdate = u) } } }
        scope.launch(Dispatchers.IO) {
            delay(20_000)
            while (isActive) {
                checkUpdate(manual = false)
                delay(6 * 3600_000L)
            }
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

    /** بعد از به‌روزرسانی برنامه: اطمینان به کاربر که خریدها و ژورنال حفظ شده‌اند (یا گزارش بازیابی). */
    private fun announceUpdate(settings: com.saeidkazemi.trader.data.model.AppSettings, account: com.saeidkazemi.trader.data.model.AccountState) {
        val store = container.store
        val msgs = ArrayList<String>()
        if (settings.lastAppVersion != AppVersion.NAME) {
            val journalSize = container.tradeEngine.journal.all().size
            if (settings.lastAppVersion.isNotEmpty() || account.positions.isNotEmpty() || account.trades.isNotEmpty()) {
                msgs.add(
                    "به‌روزرسانی به نسخه " + AppVersion.NAME + " انجام شد؛ " + account.positions.size + " خرید باز، " +
                        account.trades.size + " معامله و " + journalSize + " ردیف ژورنال حفظ شد."
                )
            }
            store.saveSettings(store.loadSettings().copy(lastAppVersion = AppVersion.NAME))
        }
        msgs.addAll(store.recoveryNotes)
        store.recoveryNotes.clear()
        if (msgs.isNotEmpty()) toast(msgs.joinToString(" "))
    }

    /** یک دور کامل: به‌روزرسانی بازار، تحلیل، اخبار، مدیریت ریسک و (در حالت خودکار) خرید و فروش. */
    fun refresh() {
        if (container.sync.isFollower) container.sync.syncNow()
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

    private fun syncFromEngine(report: CycleReport?) {
        val settings = container.store.loadSettings()
        val market = container.marketDataService
        val assets = market.cachedAssets()
        val priceMap = HashMap<String, Double>()
        val heldNow = container.broker.account().positions
        for (a in assets) priceMap[a.id] = container.tradeEngine.usdPriceFor(a, settings, heldNow)
        val digests = container.tradeEngine.cachedNews().associateBy { it.assetId }
        val journal = container.tradeEngine.journal.all()
        val trends = container.tradeEngine.marketTrends()
        _state.update {
            it.copy(
                loading = false,
                refreshing = false,
                error = null,
                notes = mirrorNotes(report?.notes ?: it.notes),
                assets = assets,
                signals = container.tradeEngine.lastSignals,
                priceMap = priceMap,
                account = container.broker.account(),
                settings = settings,
                usdIrr = market.usdIrr(settings),
                rateIsFallback = market.rateIsFallback(),
                lastCycle = report ?: it.lastCycle,
                newsDigests = digests,
                newsFeed = buildFeed(digests),
                outlooks = container.tradeEngine.outlooks(),
                journal = journal,
                perf = com.saeidkazemi.trader.analysis.Performance.report(journal, settings),
                marketTrends = trends,
                activity = container.tradeEngine.lastActivity,
                backtest = container.tradeEngine.backtest,
                backtestRunning = container.tradeEngine.backtestRunning,
                backtestProgress = container.tradeEngine.backtestProgress,
                review = reviewSummary(false) ?: it.review
            )
        }
    }

    // ---- خودارزیابی (کجا درست فکر کردم، کجا اشتباه) ----

    private fun reviewSummary(force: Boolean): com.saeidkazemi.trader.review.ReviewSummary? = try {
        if (container.sync.isFollower) container.sync.mirror?.review
        else com.saeidkazemi.trader.review.ReviewReport.cachedSummary(container, force = force)
    } catch (_: Exception) {
        null
    }

    fun refreshReview() {
        scope.launch(Dispatchers.IO) {
            val r = reviewSummary(true)
            _state.update { it.copy(review = r ?: it.review) }
        }
    }

    /** متن کامل گزارش؛ در حالت آینه از دستگاه اصلی گرفته می‌شود. خروجی: (متن یا null، پیام). */
    private suspend fun reportText(online: Boolean): Pair<String?, String?> {
        if (container.sync.isFollower) {
            val r = container.sync.requestReport(online)
            return if (online) (null to r.message) else (r.data to (if (r.data == null) r.message else null))
        }
        return com.saeidkazemi.trader.review.ReviewReport.build(container, hooks.platformInfo().name) to null
    }

    private val codeRegex = Regex("کد گزارش: ([A-Z0-9]{4}-[A-Z0-9]{4})")

    /** اگر پیام (مثلاً از دستگاه اصلی) کد گزارش داشت، نگه داشته می‌شود تا در کارت خودارزیابی دیده شود. */
    private fun captureCode(msg: String) {
        codeRegex.find(msg)?.let { m -> _state.update { it.copy(reportCode = m.groupValues[1], reportCodeAt = System.currentTimeMillis()) } }
    }

    /** ارسال آنلاین گزارش برای تحلیلگر؛ یک کد کوتاه برمی‌گرداند که باید برای تحلیلگر فرستاده شود. */
    fun sendReportOnline() {
        if (_state.value.reportBusy) return
        scope.launch(Dispatchers.IO) {
            _state.update { it.copy(reportBusy = true) }
            try {
                val (text, msg) = reportText(online = true)
                if (text != null) {
                    val code = com.saeidkazemi.trader.review.ReportUploader.upload(text)
                    _state.update { it.copy(reportCode = code, reportCodeAt = System.currentTimeMillis()) }
                    toast("گزارش ارسال شد. کد گزارش: " + code + " — این کد را برای تحلیلگر بفرستید (تا حدود ۱۲ ساعت قابل خواندن است).")
                } else if (msg != null) {
                    captureCode(msg)
                    toast(msg)
                }
            } catch (e: Exception) {
                container.review.issue(com.saeidkazemi.trader.review.ReviewLog.AREA_SYNC, "ارسال آنلاین گزارش ناموفق بود: " + (e.message ?: ""))
                toast("ارسال آنلاین ممکن نشد (شاید سرویس ntfy.sh فیلتر است؛ با فیلترشکن امتحان کنید یا دکمه «فایل گزارش» را بزنید): " + (e.message ?: ""))
            } finally {
                _state.update { it.copy(reportBusy = false) }
            }
        }
    }

    /** ساخت فایل گزارش و اشتراک/ذخیره آن. */
    fun shareReport() {
        if (_state.value.reportBusy) return
        scope.launch(Dispatchers.IO) {
            _state.update { it.copy(reportBusy = true) }
            try {
                val (text, msg) = reportText(online = false)
                if (text == null) {
                    toast(msg ?: "ساخت گزارش ممکن نشد.")
                    return@launch
                }
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
                val res = hooks.shareReport("MoameleYar-report-$stamp.txt", text)
                toast(res ?: "اشتراک فایل در این نسخه پشتیبانی نمی‌شود؛ از «کپی خلاصه» استفاده کنید.")
            } catch (e: Exception) {
                toast("ساخت گزارش ناموفق بود: " + (e.message ?: ""))
            } finally {
                _state.update { it.copy(reportBusy = false) }
            }
        }
    }

    /** متن کوتاه گزارش (بدون JSON) برای کپی؛ در حالت آینه اگر دستگاه اصلی در دسترس نباشد null. */
    suspend fun reportShortText(): String? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            reportText(online = false).first?.let { com.saeidkazemi.trader.review.ReviewReport.shortText(it) }
        } catch (_: Exception) {
            null
        }
    }

    /** در حالت آینه، یادداشت‌های دستگاه اصلی (دلایل خرید/نخریدن) نشان داده می‌شود. */
    private fun mirrorNotes(local: List<String>): List<String> {
        val sync = container.sync
        if (!sync.isFollower) return local
        val m = sync.mirror ?: return listOf("حالت آینه: هنوز وضعیتی از دستگاه اصلی دریافت نشده است.") + local
        val head = "حالت آینه: وضعیت «" + m.device.ifBlank { "دستگاه اصلی" } + "» (نسخه " + m.appVersion + ") نمایش داده می‌شود؛ معاملات روی همان دستگاه انجام می‌شود." +
            (if (m.appVersion != AppVersion.NAME) " نسخه دو دستگاه یکسان نیست (" + AppVersion.NAME + " / " + m.appVersion + ")؛ هر دو را به‌روز کنید." else "")
        return listOf(head) + m.notes.orEmpty().filterNot { it.startsWith("حالت آینه") }
    }

    /** اگر این دستگاه آینه است، فرمان برای دستگاه اصلی فرستاده می‌شود و true برمی‌گردد. */
    private fun remote(type: String, args: Map<String, String> = emptyMap()): Boolean {
        if (!container.sync.isFollower) return false
        scope.launch(Dispatchers.IO) { toast(container.sync.send(type, args)) }
        return true
    }

    /** ذخیره تنظیمات؛ در حالت آینه، تغییرات برای دستگاه اصلی هم فرستاده می‌شود. */
    private suspend fun saveSettingsShared(new: com.saeidkazemi.trader.data.model.AppSettings, msg: String = "") {
        val old = container.store.loadSettings()
        container.store.saveSettings(new)
        if (container.sync.isFollower) {
            val patch = container.sync.settingsPatch(old, new) ?: return
            val res = container.sync.send("settings", if (msg.isNotEmpty()) mapOf("msg" to msg) else emptyMap(), patch)
            toast(res)
        }
    }

    // ---- به‌روزرسانی داخل برنامه ----

    fun checkUpdate(manual: Boolean = true) {
        if (_update.value.checking || _update.value.progress != null) return
        scope.launch(Dispatchers.IO) {
            _update.value = _update.value.copy(checking = true, error = null)
            try {
                val info = com.saeidkazemi.trader.update.Updater.check()
                _update.value = _update.value.copy(checking = false, info = info, lastCheckedAt = System.currentTimeMillis())
                if (manual) toast(
                    if (info.isNewer) "نسخه جدید " + info.name + " آماده است؛ دکمه «به‌روزرسانی» را بزنید."
                    else "برنامه به‌روز است (نسخه " + AppVersion.NAME + ")."
                )
            } catch (e: Exception) {
                _update.value = _update.value.copy(checking = false, error = e.message ?: "خطا", lastCheckedAt = System.currentTimeMillis())
                if (manual) toast("بررسی نسخه جدید ناموفق بود: " + (e.message ?: ""))
            }
        }
    }

    /** دانلود و نصب نسخه جدید با یک دکمه (خریدها، ژورنال و تنظیمات حفظ می‌شوند). */
    fun installUpdate() {
        val dir = hooks.updateDir()
        if (dir == null) {
            toast("به‌روزرسانی داخلی در این نسخه پشتیبانی نمی‌شود؛ از صفحه دانلود نصب کنید.")
            return
        }
        if (_update.value.progress != null || _update.value.installing) return
        scope.launch(Dispatchers.IO) {
            try {
                val info = _update.value.info?.takeIf { it.isNewer } ?: com.saeidkazemi.trader.update.Updater.check().also {
                    _update.value = _update.value.copy(info = it, lastCheckedAt = System.currentTimeMillis())
                }
                if (!info.isNewer) {
                    toast("برنامه به‌روز است (نسخه " + AppVersion.NAME + ").")
                    return@launch
                }
                val apk = hooks.updateKind == "apk"
                val url = if (apk) info.apkUrl else info.msiUrl
                val sha = if (apk) info.apkSha256 else info.msiSha256
                val file = java.io.File(dir, "MoameleYar-" + info.name + (if (apk) ".apk" else ".msi"))
                _update.value = _update.value.copy(progress = 0, error = null)
                com.saeidkazemi.trader.update.Updater.download(url, file, sha) { p ->
                    _update.value = _update.value.copy(progress = p)
                }
                _update.value = _update.value.copy(progress = null, installing = true)
                // وضعیت فعلی قبل از نصب ذخیره می‌شود (خریدها و ژورنال همیشه روی فایل هستند)
                container.store.saveAccount(container.broker.account())
                val msg = hooks.launchInstaller(file)
                _update.value = _update.value.copy(installing = false)
                if (msg != null) toast(msg)
            } catch (e: Exception) {
                _update.value = _update.value.copy(progress = null, installing = false, error = e.message)
                container.review.issue(com.saeidkazemi.trader.review.ReviewLog.AREA_UPDATE, "نصب نسخه جدید ناموفق بود: " + (e.message ?: ""))
                toast("به‌روزرسانی ناموفق بود: " + (e.message ?: ""))
            }
        }
    }

    // ---- اتصال اندروید و ویندوز ----

    fun syncBecomeHost() {
        scope.launch(Dispatchers.IO) {
            container.sync.becomeHost()
            toast("این دستگاه «دستگاه اصلی» شد؛ کد اتصال را در دستگاه دیگر وارد کنید.")
        }
    }

    fun syncBecomeFollower(code: String) {
        scope.launch(Dispatchers.IO) {
            if (!container.sync.becomeFollower(code)) {
                toast("کد اتصال معتبر نیست (۱۰ حرف و عدد، مثل ABCDE-12345).")
                return@launch
            }
            toast("در حال اتصال به دستگاه اصلی… این دستگاه از این به بعد خودش معامله نمی‌کند و وضعیت دستگاه اصلی را نشان می‌دهد.")
            container.sync.syncNow()
        }
    }

    fun syncTurnOff() {
        scope.launch(Dispatchers.IO) {
            val wasFollower = container.sync.isFollower
            container.sync.turnOff()
            toast(
                if (wasFollower) "اتصال قطع شد؛ این دستگاه از آخرین وضعیت دریافتی، مستقل ادامه می‌دهد."
                else "اتصال خاموش شد؛ این دستگاه مستقل کار می‌کند."
            )
        }
    }

    fun syncNewCode() {
        scope.launch(Dispatchers.IO) {
            container.sync.regenerateCode()
            toast("کد اتصال جدید ساخته شد؛ دستگاه‌های قبلی دیگر وصل نمی‌شوند تا کد جدید را وارد کنند.")
        }
    }

    fun syncSetLan(on: Boolean) { scope.launch(Dispatchers.IO) { container.sync.setLan(on) } }

    fun syncSetRelay(on: Boolean) { scope.launch(Dispatchers.IO) { container.sync.setRelay(on) } }

    fun syncSetHost(addr: String) {
        scope.launch(Dispatchers.IO) {
            container.sync.setManualHost(addr)
            container.sync.syncNow()
            toast(if (addr.isBlank()) "نشانی دستی پاک شد؛ دستگاه اصلی خودکار جستجو می‌شود." else "نشانی دستگاه اصلی ذخیره شد.")
        }
    }

    fun syncSetRelayUrl(url: String) { scope.launch(Dispatchers.IO) { container.sync.setRelayUrl(url) } }

    fun syncNow() {
        container.sync.syncNow()
        toast("همگام‌سازی…")
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
            saveSettingsShared(settings)
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

    /** خرید پله‌ای با تأیید برای یک بازار. */
    fun toggleScaledEntry(m: com.saeidkazemi.trader.data.model.MarketKind, on: Boolean) {
        updateSettings { s ->
            val cur = s.scaledEntryMarkets.orEmpty().toMutableSet()
            if (on) cur.add(m.name) else cur.remove(m.name)
            s.copy(scaledEntryMarkets = cur.toList())
        }
        toast(if (on) "خرید پله‌ای با تأیید برای " + m.faTitle + " روشن شد (برای خریدهای جدید)." else "خرید پله‌ای " + m.faTitle + " خاموش شد؛ خریدهای جدید یک‌جا انجام می‌شوند.")
    }

    /** سرمایه مشترک بین بازارها (با سقف سهم هر بازار). */
    fun toggleSharedCapital(on: Boolean) {
        updateSettings { it.copy(sharedCapital = on) }
        toast(if (on) "سرمایه مشترک روشن شد؛ نقد آزاد هر بازار برای خرید در بازارهای دیگر هم استفاده می‌شود (حداکثر ۶۰٪ کل سرمایه در یک بازار)."
            else "سرمایه مشترک خاموش شد؛ هر بازار فقط با نقد خودش خرید می‌کند.")
    }

    /** افزودن به خرید قبلی وقتی در سود است و سیگنال تازه می‌دهد. */
    fun toggleTopUp(on: Boolean) {
        updateSettings { it.copy(topUpWinners = on) }
        toast(if (on) "افزودن به خرید قبلی روشن شد." else "افزودن به خرید قبلی خاموش شد؛ هر دارایی فقط یک بار خریده می‌شود.")
    }

    fun toggleTopUpSmart(on: Boolean) {
        updateSettings { it.copy(topUpSmart = on) }
        toast(if (on) "افزودن در همه بازارها، جز جایی که آزمون روزانه نشان دهد سود را کم می‌کند." else "افزودن در همه بازارها (بدون شرط آزمون).")
    }

    /** بی‌ضرر کردن بعد از ۳٪ سود. */
    fun toggleBreakEven(on: Boolean) {
        updateSettings { it.copy(breakEvenAfterPct = if (on) 3.0 else 0.0) }
        toast(if (on) "بی‌ضرر کردن روشن شد: بعد از ۳٪ سود، حد ضرر روی قیمت خرید + کارمزد می‌رود." else "بی‌ضرر کردن خاموش شد.")
    }

    fun toggleBreakEvenSmart(on: Boolean) {
        updateSettings { it.copy(breakEvenSmart = on) }
        toast(if (on) "بی‌ضرر کردن فقط در بازارهایی اجرا می‌شود که آزمون روزانه نشان ندهد سود را کم می‌کند." else "بی‌ضرر کردن در همه بازارها اجرا می‌شود.")
    }

    /** انتخاب خودکار روش ورود/خروج هر بازار از آزمون روزانه. */
    fun toggleAutoScaleStyle(on: Boolean) {
        updateSettings { it.copy(autoScaleStyle = on) }
        toast(if (on) "انتخاب خودکار روش روشن شد؛ هر بازار با روشی معامله می‌کند که در آزمون روزانه بهتر بوده." else "انتخاب خودکار خاموش شد؛ کلیدهای دستی خرید پله‌ای اعمال می‌شوند.")
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
            saveSettingsShared(settings)
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
     * تقسیم جدید سرمایه بین بازارها. خریدهای باز **بسته نمی‌شوند**؛ فقط نقد آزاد بین بازارها جابه‌جا می‌شود.
     */
    fun setAllocations(crypto: Double, ir: Double, metal: Double, fx: Double) {
        val vals = listOf(crypto, ir, metal, fx)
        if (vals.any { !it.isFinite() || it < 0 }) {
            toast("درصدهای معتبر وارد کنید.")
            return
        }
        val sum = vals.sum()
        if (sum < 99.5 || sum > 100.5) {
            toast("جمع درصدها باید ۱۰۰ باشد (الان " + com.saeidkazemi.trader.util.Format.num(sum, 1) + ").")
            return
        }
        if (remote("allocations", mapOf("CRYPTO" to crypto.toString(), "IR_STOCK" to ir.toString(), "METAL" to metal.toString(), "FX" to fx.toString()))) return
        scope.launch(Dispatchers.IO) {
            val alloc = mapOf("CRYPTO" to crypto, "IR_STOCK" to ir, "METAL" to metal, "FX" to fx)
            val settings = container.store.loadSettings().copy(allocations = alloc)
            container.store.saveSettings(settings)
            val prices = _state.value.priceMap
            val acc = container.broker.reallocate(alloc) { p -> (prices[p.assetId] ?: p.avgBuyUsd) * p.qty }
            _state.update { it.copy(settings = settings, account = acc) }
            toast(
                "تقسیم جدید سرمایه اعمال شد" +
                    (if (acc.positions.isNotEmpty()) "؛ " + acc.positions.size + " خرید باز دست نخورد و فقط نقد آزاد جابه‌جا شد." else ".")
            )
        }
    }

    /** تغییر سرمایه بدون بستن خریدها (افزایش: به نقد اضافه می‌شود؛ کاهش: فقط از نقد آزاد). */
    fun changeCapital(amountUsd: Double) {
        if (!amountUsd.isFinite() || amountUsd <= 0) {
            toast("سرمایه معتبر وارد کنید.")
            return
        }
        if (remote("capital", mapOf("usd" to amountUsd.toString()))) return
        scope.launch(Dispatchers.IO) {
            val s0 = container.store.loadSettings()
            val acc = container.broker.adjustCapital(amountUsd, s0.allocations)
            if (acc == null) {
                toast("نقد آزاد برای این کاهش سرمایه کافی نیست (بخشی از پول در خریدهای باز است). خریدها بسته نشدند.")
                return@launch
            }
            val settings = s0.copy(capitalUsd = amountUsd)
            container.store.saveSettings(settings)
            _state.update { it.copy(settings = settings, account = acc) }
            toast("سرمایه به " + com.saeidkazemi.trader.util.Format.num(amountUsd, 0) + " دلار تغییر کرد؛ خریدهای باز حفظ شدند.")
        }
    }

    /** اجرای دستی بک‌تست (چند ده ثانیه تا چند دقیقه؛ در پس‌زمینه). */
    fun runBacktest() {
        if (remote("backtest")) return
        if (container.tradeEngine.backtestRunning) {
            toast("بک‌تست در حال اجراست…")
            return
        }
        scope.launch(Dispatchers.IO) {
            _state.update { it.copy(backtestRunning = true, backtestProgress = "شروع بک‌تست…") }
            val progress = scope.launch(Dispatchers.IO) {
                while (true) {
                    delay(1500)
                    _state.update { it.copy(backtestProgress = container.tradeEngine.backtestProgress ?: it.backtestProgress) }
                }
            }
            val r = try {
                container.tradeEngine.runBacktest()
            } catch (e: Exception) {
                null
            }
            progress.cancel()
            _state.update { it.copy(backtest = container.tradeEngine.backtest, backtestRunning = false, backtestProgress = null) }
            toast(
                if (r == null || r.results.isEmpty()) "بک‌تست انجام نشد (داده در دسترس نبود)."
                else "بک‌تست تمام شد: " + r.results.joinToString("، ") { m ->
                    m.market.faTitle + " " + (m.bestOut ?: m.currentOut).let { Math.round(it.winRate).toString() + "٪ برد" } +
                        (if (m.applied) " (اعمال شد)" else "")
                }
            )
        }
    }

    fun setUseBacktest(on: Boolean) {
        updateSettings { it.copy(useBacktestParams = on) }
        toast(if (on) "پارامترهای بک‌تست برای خریدهای جدید استفاده می‌شود." else "تنظیمات پیش‌فرض ریسک استفاده می‌شود.")
    }

    fun setFeePct(pct: Double) {
        if (!pct.isFinite()) return
        updateSettings { it.copy(feePct = maxOf(0.0, minOf(0.02, pct))) }
        toast("کارمزد فرضی فارکس ذخیره شد.")
    }

    /** پله کارمزد نوبیتکس (بر اساس حجم معاملات ۳۰ روز شما در نوبیتکس). */
    fun setNobitexFeeTier(tier: Int) {
        val t = tier.coerceIn(0, com.saeidkazemi.trader.trading.Fees.NOBITEX_TIERS.size - 1)
        updateSettings { it.copy(nobitexFeeTier = t) }
        toast("پله کارمزد نوبیتکس: " + com.saeidkazemi.trader.trading.Fees.NOBITEX_TIERS[t].name)
    }

    /** شروع مجدد کامل حساب دمو — همه خریدها و ژورنال پاک می‌شوند. فقط بعد از تأیید صریح کاربر صدا زده می‌شود. */
    fun setCapitalAndReset(amountUsd: Double) {
        if (!amountUsd.isFinite() || amountUsd <= 0) {
            toast("سرمایه معتبر وارد کنید.")
            return
        }
        if (remote("reset", mapOf("usd" to amountUsd.toString()))) return
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
            saveSettingsShared(settings)
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
        if (remote("buy", mapOf("asset" to assetId, "usd" to amount.toString()))) return
        scope.launch(Dispatchers.IO) {
            val msg = container.tradeEngine.manualBuy(assetId, amount)
            syncAccount()
            toast(msg)
        }
    }

    fun manualSell(assetId: String) {
        if (remote("sell", mapOf("asset" to assetId))) return
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
            val usdPrice = container.tradeEngine.usdPriceFor(asset, settings)
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
