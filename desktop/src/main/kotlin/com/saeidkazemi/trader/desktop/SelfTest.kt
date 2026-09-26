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
            val sp = (st.assets.filter { it.market == MarketKind.CRYPTO }.take(6) + st.assets.filter { it.market == MarketKind.IR_STOCK }.take(3))
                .joinToString(" ") { it.symbol + "=" + "%.3f".format(container.tradeEngine.halfSpread(it, null) * 100) }
            val j0 = container.tradeEngine.journal.all().firstOrNull()
            out("fees halfSpread% " + sp + " | journal buySpread=" + j0?.buySpreadPct?.let { "%.3f".format(it) } + " feePct=" + j0?.let { if (it.amountUsd > 0) "%.3f".format(it.buyFeeUsd / it.amountUsd * 100) else null })
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
        shot("12-market-trend", 760, 2100) {
            val s by controller.state.collectAsState()
            androidx.compose.foundation.layout.Column {
                listOf(MarketKind.CRYPTO, MarketKind.IR_STOCK, MarketKind.FX).forEach { k ->
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
