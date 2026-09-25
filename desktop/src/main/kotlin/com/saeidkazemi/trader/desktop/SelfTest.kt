package com.saeidkazemi.trader.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
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
        out("cycle=${st.lastCycle?.summary()}")
        st.notes.forEach { out("note: $it") }
        out("signals=${st.signals.size} crypto=${st.signals.count { it.market == MarketKind.CRYPTO }} fx=${st.signals.count { it.market == MarketKind.FX }} ir=${st.signals.count { it.market == MarketKind.IR_STOCK }}")
        container.marketDataService.historyErrors.entries.take(6).forEach { out("historyError ${it.key}: ${it.value.take(200)}") }
        st.signals.take(12).forEach {
            out("  ${it.symbol} score=${it.score} tech=${it.technicalScore} news=${it.newsAdj} (${it.newsCount}) ${it.action} blocked=${it.newsBlocked}")
        }
        out("positions=${st.account.positions.map { it.symbol }} cash=${st.account.cashUsd}")
        out("newsFeed=${st.newsFeed.size} digests=${st.newsDigests.size}")

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
    } catch (e: Throwable) {
        out("SELFTEST CRASH: $e")
        e.stackTrace.take(15).forEach { out("   at $it") }
        exit = 1
    }
    out("exit=$exit")
    File(outDir, "selftest.txt").writeText(log.toString())
    return exit
}
