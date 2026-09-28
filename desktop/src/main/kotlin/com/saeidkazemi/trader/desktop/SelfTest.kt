package com.saeidkazemi.trader.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.LayoutDirection
import com.saeidkazemi.trader.core.AppContainer
import com.saeidkazemi.trader.core.TraderController
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.ui.AppRoot
import com.saeidkazemi.trader.ui.PlatformInfo
import com.saeidkazemi.trader.ui.screens.AssetDetailScreen
import com.saeidkazemi.trader.ui.screens.NewsScreen
import com.saeidkazemi.trader.ui.screens.SignalsScreen
import com.saeidkazemi.trader.ui.theme.TraderTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files

/**
 * آزمون خودکار برای CI (بدون نمایش پنجره): یک دور کامل تحلیل را با داده واقعی اجرا می‌کند،
 * نتیجه منابع داده و اخبار را در فایل متنی می‌نویسد و از صفحه‌ها تصویر PNG می‌سازد.
 * اجرا: `MoameleYar.exe --selftest=<پوشه خروجی>`
 */
@OptIn(ExperimentalComposeUiApi::class)
fun runSelfTest(outDir: File): Int {
    outDir.mkdirs()
    val log = StringBuilder()
    fun out(s: String) {
        log.appendLine(s)
        println(s)
    }
    var exit = 0
    try {
        val dataDir = Files.createTempDirectory("moameleyar-selftest").toFile()
        val container = AppContainer(dataDir)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = TraderController(container, scope, object : TraderController.PlatformHooks {
            override val runsOwnLoop = false
            override fun platformInfo() = PlatformInfo(
                isDesktop = true, name = "ویندوز", autostartSupported = true,
                dataLocation = dataDir.absolutePath
            )
        })
        val evStart = java.util.concurrent.atomic.AtomicInteger()
        val evDone = java.util.concurrent.atomic.AtomicInteger()
        scope.launch {
            container.tradeEngine.events.collect { e ->
                if (e is com.saeidkazemi.trader.data.model.TradeEvent.Starting) evStart.incrementAndGet() else evDone.incrementAndGet()
            }
        }
        controller.start()
        runBlocking {
            withTimeoutOrNull(150_000) {
                while (controller.state.value.loading || controller.state.value.lastCycle == null) delay(500)
            }
        }
        val st = controller.state.value
        out("assets=${st.assets.size} simulated=${st.assets.count { it.isSimulated }}")
        MarketKind.values().forEach { k ->
            val list = st.assets.filter { it.market == k }
            out("  $k: ${list.size} (sim ${list.count { it.isSimulated }})")
        }
        out("usdIrr=${st.usdIrr} fallback=${st.rateIsFallback}")
        container.marketDataService.lastIranScan?.let { sc ->
            out("iranScan live=${sc.live} scanned=${sc.scanned} stocks=${sc.stocks} liquid=${sc.liquid} buyQ=${sc.buyQueues} sellQ=${sc.sellQueues} error=${container.marketDataService.iranError}")
        }
        st.signals.filter { it.market == MarketKind.IR_STOCK }.take(5).forEach {
            out("  IR ${it.symbol} score=${it.score} tech=${it.technicalScore} ${it.action}")
        }
        out("cycle=${st.lastCycle?.summary()}")
        st.notes.forEach { out("note: $it") }
        out("signals=${st.signals.size} crypto=${st.signals.count { it.market == MarketKind.CRYPTO }} fx=${st.signals.count { it.market == MarketKind.FX }} ir=${st.signals.count { it.market == MarketKind.IR_STOCK }}")
        container.marketDataService.historyErrors.entries.take(6).forEach { out("historyError ${it.key}: ${it.value.take(200)}") }
        st.signals.take(12).forEach {
            out("  ${it.symbol} score=${it.score} tech=${it.technicalScore} news=${it.newsAdj} (${it.newsCount}) ${it.action} blocked=${it.newsBlocked}")
        }
        out("positions=${st.account.positions.map { it.symbol }} cash=${st.account.cashUsd}")
        out("sleeves " + st.sleeves().joinToString(" | ") { sl ->
            "${sl.market} ${"%.0f".format(sl.allocationPct)}% cash=${"%.0f".format(sl.cashUsd)} pos=${sl.positions} eq=${"%.0f".format(sl.equityUsd)}"
        })
        out("events start=${evStart.get()} done=${evDone.get()}")
        val fg = container.marketDataService.insights.cachedFearGreed()
        out("pro fng=${fg?.value} (${fg?.label}) iranStats=${container.marketDataService.iranStats?.let { "breadth=${it.breadth} net=${it.realNetIrr}" }} flowErr=${container.marketDataService.iranFlowError?.take(60)}")
        st.signals.filter { it.proFactors.isNotEmpty() }.take(3).forEach { sg ->
            out("pro ${sg.symbol} adj=${sg.proAdj} blocked=${sg.proBlocked} " + sg.proFactors.joinToString("; ") { f -> f.title + "=" + f.impact })
        }
        listOf("trade_start", "trade_done").forEach { n ->
            val ok = try {
                val r = SoundAlerts::class.java.classLoader.getResourceAsStream("sounds/$n.wav")
                r != null && javax.sound.sampled.AudioSystem.getAudioInputStream(java.io.BufferedInputStream(r)).frameLength > 1000
            } catch (e: Throwable) { false }
            out("sound $n ok=$ok")
        }
        out("newsFeed=${st.newsFeed.size} digests=${st.newsDigests.size}")
        run {
            val j = container.tradeEngine.journal.all()
            val first = j.firstOrNull()
            out("journal n=${j.size} open=${j.count { it.isOpen }} first=${first?.symbol} score=${first?.score} reasons=${first?.reasons?.size} pro=${first?.proFactors?.size} news=${first?.newsHeadlines?.size} fc=${first?.forecastExpPct?.let { "%.2f".format(it) }} sections=" +
                (first?.let { com.saeidkazemi.trader.journal.JournalExport.sections(it).joinToString("|") { s -> s.title } } ?: "-"))
            val perf = com.saeidkazemi.trader.analysis.Performance.report(j, container.store.loadSettings())
            out("perf closed=${perf.all.closed} winRate=${perf.all.winRate} guards=" + perf.guards.joinToString("; ") { it.market.name + ":" + it.active })
        }
        run {
            val set = container.store.loadSettings()
            out("fees " + com.saeidkazemi.trader.trading.Fees.table(set).joinToString(" | ") {
                it.market.name + " buy=" + com.saeidkazemi.trader.util.Format.trim(it.buyPct, 4) + " sell=" + com.saeidkazemi.trader.util.Format.trim(it.sellPct, 4) + " real=" + it.real
            })
            val sp = (st.assets.filter { it.market == MarketKind.CRYPTO }.take(6) + st.assets.filter { it.market == MarketKind.IR_STOCK }.take(3) +
                st.assets.filter { it.market == MarketKind.METAL && !it.isDisplayOnly })
                .joinToString(" ") { it.symbol + "=" + "%.3f".format(container.tradeEngine.halfSpread(it, null) * 100) }
            val j0 = container.tradeEngine.journal.all().firstOrNull()
            out("fees halfSpread% " + sp + " | journal buySpread=" + j0?.buySpreadPct?.let { "%.3f".format(it) } + " feePct=" + j0?.let { if (it.amountUsd > 0) "%.3f".format(it.buyFeeUsd / it.amountUsd * 100) else null })
        }
        run {
            val set = container.store.loadSettings()
            val acc = container.broker.account()
            out("metal alloc=" + set.allocations + " cashMETAL=" + "%.2f".format(acc.cashByMarket["METAL"] ?: -1.0) +
                " cashFX=" + "%.2f".format(acc.cashByMarket["FX"] ?: -1.0) + " pos=" + acc.positions.count { it.market == MarketKind.METAL })
            st.assets.filter { it.market == MarketKind.METAL }.forEach { a ->
                val h = container.marketDataService.cachedHistory(a.id).orEmpty()
                val sg = container.tradeEngine.lastSignals.firstOrNull { it.assetId == a.id }
                out("metal ${a.id} ${a.symbol} price=${"%.0f".format(a.price)} ${a.baseCurrency} bid=${a.bidPrice?.let { "%.0f".format(it) }} ask=${a.askPrice?.let { "%.0f".format(it) }} " +
                    "chg=${a.changePct24h?.let { "%.2f".format(it) }} hist=${h.size} last=${h.lastOrNull()?.price?.let { "%.0f".format(it) }} " +
                    "score=${sg?.score} action=${sg?.action} display=${a.isDisplayOnly} sim=${a.isSimulated} " +
                    "fee=${"%.4f".format(com.saeidkazemi.trader.trading.Fees.commission(a, null, true, set) * 100)}")
            }
        }
        container.tradeEngine.lastActivity.forEach { a ->
            out("activity ${a.market.name} pos=${a.positions}/${a.maxPositions} buySig=${a.buySignals} best=${a.bestSymbol}:${a.bestScore} bought=${a.boughtNow} status=${a.status} details=${a.details.size}")
        }
        container.tradeEngine.marketTrends().forEach { r ->
            out("trend ${r.market.name} label=${r.trendLabel} score=${r.trendScore} c1=${r.change1d?.let { "%.2f".format(it) }} c7=${r.change7d?.let { "%.2f".format(it) }} c30=${r.change30d?.let { "%.2f".format(it) }} " +
                "breadth=${r.breadthAboveSma20?.let { "%.2f".format(it) }} n=${r.constituents} pts=${r.index.size} fc=${r.forecast?.trendLabel} facts=${r.facts.size} sim=${r.simulated}")
        }
        container.tradeEngine.outlooks().values.take(4).forEach { o ->
            val f = o.forecast
            out(
                "outlook ${o.assetId} pnl=${"%.2f".format(o.pnlPct)} net=${"%.2f".format(o.netPnlPct)} trend=${o.trend.size} " +
                    (if (f == null) "fc=none" else "fc=${f.trendLabel} exp=${"%.2f".format(o.expectedPnlPct)} [${"%.1f".format(o.lowPnlPct)}..${"%.1f".format(o.highPnlPct)}] " +
                        "pProfit=${"%.2f".format(o.probProfit)} pStop=${o.probStop?.let { "%.2f".format(it) }} pTp=${o.probTakeProfit?.let { "%.2f".format(it) }} conf=${f.confidence}")
            )
        }

        val probe = listOfNotNull(
            st.assets.firstOrNull { it.market == MarketKind.IR_STOCK && it.symbol == "شپنا" }
                ?: st.assets.firstOrNull { it.market == MarketKind.IR_STOCK },
            st.assets.firstOrNull { it.id == "bitcoin" },
            st.assets.firstOrNull { it.market == MarketKind.FX }
        )
        runBlocking {
            probe.forEach { a ->
                val d = container.tradeEngine.newsFor(a, force = true)
                out("news ${a.id} ${a.symbol}: items=${d.items.size} score=${"%.2f".format(d.score)} adj=${d.adjustment} ok=${d.sourcesOk} failed=${d.sourcesFailed} block=${d.blockBuy}")
                d.items.take(4).forEach { n -> out("   [${n.kind}] ${"%.2f".format(n.sentiment)} ${n.title.take(110)}") }
                if (a.id == "bitcoin" || a.market == MarketKind.FX) d.items.take(4).forEach { n ->
                    out("tr ${a.symbol} fa=${n.titleFa != null} | ${n.displayTitle.take(90)} | ${n.title.take(60)}")
                }
            }
        }
        if (st.assets.isEmpty() || st.signals.isEmpty()) exit = 2

        // ---- تصاویر صفحه‌ها ----
        fun shot(name: String, w: Int, h: Int, content: @Composable () -> Unit) {
            try {
                val scene = ImageComposeScene(w, h, Density(1f)) {
                    TraderTheme {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { content() }
                    }
                }
                scene.render(0)
                Thread.sleep(300)
                scene.render(1_000_000_000L)
                val img = scene.render(2_000_000_000L)
                val bytes = img.encodeToData(EncodedImageFormat.PNG)?.bytes
                if (bytes != null) File(outDir, "$name.png").writeBytes(bytes)
                scene.close()
                out("screenshot $name ok")
            } catch (e: Throwable) {
                out("screenshot $name FAILED: $e")
                exit = 3
            }
        }
        shot("1-dashboard", 1280, 900) { AppRoot(controller) }
        shot("2-signals", 1100, 1400) {
            val s by controller.state.collectAsState()
            SignalsScreen(s, onBuy = { _, _ -> }, onOpenAsset = {})
        }
        shot("3-news", 1100, 1400) {
            val s by controller.state.collectAsState()
            NewsScreen(s, onRefreshNews = {}, onOpenAsset = {})
        }
        probe.firstOrNull()?.let { a ->
            controller.openAsset(a.id)
            runBlocking {
                withTimeoutOrNull(60_000) {
                    while (controller.state.value.detail?.newsLoading != false) delay(300)
                }
            }
            shot("4-detail", 1100, 2200) {
                val s by controller.state.collectAsState()
                AssetDetailScreen(a.id, s, onBuy = { _, _ -> }, onSell = {}, onBack = {})
            }
        }
        st.assets.firstOrNull { it.id == "bitcoin" }?.let { a ->
            controller.openAsset(a.id)
            runBlocking {
                withTimeoutOrNull(60_000) {
                    while (controller.state.value.detail?.asset?.id != a.id || controller.state.value.detail?.newsLoading != false) delay(300)
                }
            }
            controller.state.value.detail?.signal?.let { sg ->
                out("pro detail ${sg.symbol} adj=${sg.proAdj} " + sg.proFactors.joinToString("; ") { f -> f.title + " " + f.value + " " + f.impact })
            }
            shot("5-detail-btc", 1100, 2400) {
                val s by controller.state.collectAsState()
                AssetDetailScreen(a.id, s, onBuy = { _, _ -> }, onSell = {}, onBack = {})
            }
        }
        shot("6-settings", 1100, 3200) {
            val s by controller.state.collectAsState()
            com.saeidkazemi.trader.ui.screens.SettingsScreen(s, controller)
        }
        shot("7-portfolio", 1100, 1600) {
            val s by controller.state.collectAsState()
            com.saeidkazemi.trader.ui.screens.PortfolioScreen(s, onSell = {}, onOpenAsset = {})
        }
        // روند موقعیت: نقطه خرید، سود/زیان و پیش‌بینی
        controller.state.value.account.positions.firstOrNull()?.let { p ->
            controller.openAsset(p.assetId)
            runBlocking {
                withTimeoutOrNull(60_000) {
                    while (controller.state.value.detail?.asset?.id != p.assetId || controller.state.value.detail?.newsLoading != false) delay(300)
                }
            }
            shot("8-position-trend", 760, 1000) {
                val s by controller.state.collectAsState()
                AssetDetailScreen(p.assetId, s, onBuy = { _, _ -> }, onSell = {}, onBack = {})
            }
            shot("9-portfolio-trend", 760, 1300) {
                val s by controller.state.collectAsState()
                com.saeidkazemi.trader.ui.screens.PortfolioScreen(s, onSell = {}, onOpenAsset = {})
            }
        }
        run {
            val t0 = System.currentTimeMillis()
            val r = runBlocking {
                withTimeoutOrNull(900_000) {
                    while (container.tradeEngine.backtestRunning) delay(500)
                    container.tradeEngine.backtest ?: container.tradeEngine.runBacktest()
                }
            }
            fun f(s: com.saeidkazemi.trader.analysis.Backtest.Stats?): String =
                if (s == null) "-" else "n=${s.trades} wr=${"%.1f".format(s.winRate)} rr=${"%.2f".format(s.rr)} exp=${"%.2f".format(s.expectancyPct)} pf=${"%.2f".format(s.profitFactor)} dd=${"%.1f".format(s.maxDrawdownPct)}"
            fun pp(p: com.saeidkazemi.trader.analysis.Backtest.Params?): String = if (p == null) "-" else "f=${p.filter} " +
                if (p == null) "-" else "th=${p.threshold} tp=${"%.1f".format(p.tpPct * 100)} k=${p.stopMult} st=${"%.1f".format(p.minStopPct * 100)}-${"%.1f".format(p.maxStopPct * 100)} tr=${"%.0f".format(p.trailPct * 100)} hold=${p.maxHoldDays}"
            out("bt time=${(System.currentTimeMillis() - t0) / 1000}s results=${r?.results?.size} notes=${r?.notes}")
            r?.results?.forEach { m ->
                out("bt ${m.market.name} a=${m.assets} d=${m.days} applied=${m.applied} hit60=${m.reachedTarget} | CUR ${pp(m.current)} IN ${f(m.currentIn)} OUT ${f(m.currentOut)} | BEST ${pp(m.best)} IN ${f(m.bestIn)} OUT ${f(m.bestOut)} | exits=${m.exitMix}")
            }
        }
        // ---- آزمون به‌روزرسانی داخل برنامه (خواندن version.json و دانلود APK فعلی از صفحه دانلود) ----
        try {
            val info = try { com.saeidkazemi.trader.update.Updater.check() } catch (e: Exception) { out("update check err=${e.message}"); null }
            if (info != null) out("update check name=${info.name} code=${info.code} newer=${info.isNewer} apkSha=${info.apkSha256.take(12)} msiSha=${info.msiSha256.take(12)}")
            val t0 = System.currentTimeMillis()
            var last = -1
            val dest = java.io.File(System.getProperty("java.io.tmpdir"), "mupd/MoameleYar-test.apk")
            val f = com.saeidkazemi.trader.update.Updater.download(info?.apkUrl ?: (com.saeidkazemi.trader.update.Updater.BASE + "MoameleYar-android.apk"), dest, info?.apkSha256 ?: "") { last = it }
            out("update download ok size=${f.length()} pct=$last t=${(System.currentTimeMillis() - t0) / 1000}s")
            f.delete()
        } catch (e: Exception) {
            out("update download err=${e.message}")
        }
        // ---- آزمون اتصال اندروید و ویندوز: دستگاه اصلی (همین) + آینه (دستگاه دوم) ----
        var followerForShot: AppContainer? = null
        try {
            val host = container
            host.sync.start(com.saeidkazemi.trader.sync.SyncRole.OFF, "selftest-host")
            host.sync.becomeHost()
            val code = host.sync.status.value.code
            val port = host.sync.status.value.localAddresses.firstOrNull()?.substringAfterLast(':') ?: "47631"
            out("sync host code=${code.length} addrs=${host.sync.status.value.localAddresses} msg=${host.sync.status.value.message}")
            val f = AppContainer(Files.createTempDirectory("moameleyar-follower").toFile())
            followerForShot = f
            f.sync.start(com.saeidkazemi.trader.sync.SyncRole.OFF, "selftest-follower")
            f.sync.setRelay(false)
            f.sync.setManualHost("127.0.0.1:$port")
            val okF = f.sync.becomeFollower(code)
            val t0 = System.currentTimeMillis()
            runBlocking { withTimeoutOrNull(40_000) { while (f.sync.mirror == null) delay(300) } }
            val ha = host.broker.account()
            val fa = f.broker.account()
            out(
                "sync lan follower=$okF mirrored=${f.sync.mirror != null} t=${(System.currentTimeMillis() - t0) / 1000}s " +
                    "pos=${fa.positions.size}/${ha.positions.size} cash=${"%.2f".format(fa.cashUsd)}/${"%.2f".format(ha.cashUsd)} " +
                    "journal=${f.tradeEngine.journal.all().size}/${host.tradeEngine.journal.all().size} bt=${f.tradeEngine.backtest?.results?.size}/${host.tradeEngine.backtest?.results?.size} " +
                    "act=${f.tradeEngine.lastActivity.size} followerMode=${f.tradeEngine.followerMode} ch=${f.sync.status.value.channel}"
            )
            // فرمان خرید از آینه
            val held = ha.positions.map { it.assetId }.toSet()
            val cand = host.marketDataService.cachedAssets().firstOrNull {
                it.market == MarketKind.CRYPTO && !it.isSimulated && !it.isDisplayOnly && it.id !in held
            }
            if (cand != null) {
                val msg = runBlocking { f.sync.send("buy", mapOf("asset" to cand.id, "usd" to "15")) }
                runBlocking { withTimeoutOrNull(20_000) { while (f.broker.account().positions.none { it.assetId == cand.id }) delay(300) } }
                out("sync lan-cmd buy ${cand.symbol}: hostHas=${host.broker.account().positions.any { it.assetId == cand.id }} mirrorHas=${f.broker.account().positions.any { it.assetId == cand.id }} msg=${msg.take(120)}")
            }
            val old = f.store.loadSettings()
            val patch = f.sync.settingsPatch(old, old.copy(profitLockTriggerPct = 12.0, soundAlerts = !old.soundAlerts))
            val pm = runBlocking { f.sync.send("settings", mapOf("msg" to "ok"), patch) }
            out("sync lan-settings patch=$patch hostTrigger=${host.store.loadSettings().profitLockTriggerPct} hostSound=${host.store.loadSettings().soundAlerts} msg=$pm")

            // مسیر اینترنتی (ntfy)
            host.sync.setLan(false)
            f.sync.setLan(false)
            f.sync.setRelay(true)
            val t1 = System.currentTimeMillis()
            runBlocking {
                withTimeoutOrNull(100_000) {
                    while (!(f.sync.status.value.lastSyncAt > t1 && f.sync.status.value.channel == "اینترنت")) delay(1000)
                }
            }
            val st = f.sync.status.value
            out("sync relay ok=${st.lastSyncAt > t1} t=${(System.currentTimeMillis() - t1) / 1000}s ch=${st.channel} msg=${st.message} hostMsg=${host.sync.status.value.message}")
            if (cand != null && st.lastSyncAt > t1) {
                val t2 = System.currentTimeMillis()
                val msg = runBlocking { f.sync.send("sell", mapOf("asset" to cand.id)) }
                runBlocking {
                    withTimeoutOrNull(170_000) {
                        while (f.broker.account().positions.any { it.assetId == cand.id }) { f.sync.syncNow(); delay(15_000) }
                    }
                }
                out("sync relay-cmd sell t=${(System.currentTimeMillis() - t2) / 1000}s hostHas=${host.broker.account().positions.any { it.assetId == cand.id }} mirrorHas=${f.broker.account().positions.any { it.assetId == cand.id }} msg=${msg.take(100)}")
            }
            host.sync.setLan(true)
        } catch (e: Throwable) {
            out("sync ERROR $e")
        }
        followerForShot?.let { fc ->
            shot("15-sync", 760, 1500) {
                androidx.compose.foundation.layout.Column {
                    com.saeidkazemi.trader.ui.components.SyncBanner(fc.sync.status.value)
                    com.saeidkazemi.trader.ui.components.SyncSettingsCard(fc.sync.status.value, true, controller)
                    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.padding(8.dp))
                    com.saeidkazemi.trader.ui.components.SyncSettingsCard(container.sync.status.value, false, controller)
                }
            }
        }
        shot("14-backtest", 760, 2200) {
            val s by controller.state.collectAsState()
            com.saeidkazemi.trader.ui.components.BacktestCard(
                s.copy(backtest = container.tradeEngine.backtest, backtestRunning = false),
                onRun = {}, onToggle = {}
            )
        }
        shot("13-activity", 760, 1300) {
            val s by controller.state.collectAsState()
            com.saeidkazemi.trader.ui.components.BotActivityCard(s)
        }
        shot("12-market-trend", 760, 2100) {
            val s by controller.state.collectAsState()
            androidx.compose.foundation.layout.Column {
                listOf(MarketKind.CRYPTO, MarketKind.IR_STOCK, MarketKind.METAL).forEach { k ->
                    com.saeidkazemi.trader.ui.components.MarketTrendsCard(s, androidx.compose.ui.Modifier.padding(bottom = 10.dp), only = k)
                }
            }
        }
        shot("11-journal", 760, 2400) {
            val s by controller.state.collectAsState()
            com.saeidkazemi.trader.ui.screens.JournalScreen(s, onToast = {}, onOpenAsset = {})
        }
        // نمودار مصنوعی: قیمت اول زیر قیمت خرید رفته و بعد به سود رسیده (بررسی رنگ‌های سود/زیان و پیش‌بینی)
        run {
            val now = System.currentTimeMillis()
            val day = 86_400_000L
            val hist = (0 until 90).map { i ->
                val t = now - (89 - i) * day
                val p = 100.0 + 8 * Math.sin(i / 9.0) + i * 0.15
                com.saeidkazemi.trader.data.model.PricePoint(t, p)
            }
            val buyT = now - 20 * day
            val buyP = hist.first { it.t >= buyT }.price
            val fc = com.saeidkazemi.trader.analysis.Forecast.build(hist, hist.last().price, now, score = 72)
            out("synthetic buy=${"%.2f".format(buyP)} last=${"%.2f".format(hist.last().price)} fc=${fc?.trendLabel} exp=${fc?.expectedPct?.let { "%.2f".format(it) }}")
            shot("10-synthetic-trend", 760, 420) {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.padding(16.dp)
                ) {
                    com.saeidkazemi.trader.ui.components.TrendChart(
                        past = hist.filter { it.t >= now - 45 * day },
                        forecast = fc,
                        fmt = { "$" + com.saeidkazemi.trader.util.Format.price(it) },
                        buyPrice = buyP,
                        buyTime = buyT,
                        stopPrice = buyP * 0.9,
                        takeProfit = buyP * 1.2,
                        height = 360.dp
                    )
                }
            }
        }
    } catch (e: Throwable) {
        out("SELFTEST CRASH: $e")
        e.stackTrace.take(15).forEach { out("   at $it") }
        exit = 1
    }
    out("exit=$exit")
    File(outDir, "selftest.txt").writeText(log.toString())
    return exit
}
