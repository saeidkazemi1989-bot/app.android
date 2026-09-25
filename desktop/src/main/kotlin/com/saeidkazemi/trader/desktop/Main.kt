package com.saeidkazemi.trader.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.saeidkazemi.trader.core.AppContainer
import com.saeidkazemi.trader.core.TraderController
import com.saeidkazemi.trader.ui.AppRoot
import com.saeidkazemi.trader.ui.theme.TraderTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import javax.swing.JOptionPane
import kotlin.system.exitProcess

/**
 * نقطه شروع نسخه ویندوز «معامله‌یار».
 *
 * - با بستن پنجره، برنامه بسته نمی‌شود و در System Tray (کنار ساعت) به معامله‌گری خودکار ادامه می‌دهد.
 * - خروج کامل از منوی آیکون کنار ساعت.
 * - آرگومان `--minimized` (برای اجرای خودکار با ویندوز) برنامه را بدون نمایش پنجره شروع می‌کند.
 */
fun main(args: Array<String>) {
    val startMinimized = args.contains("--minimized")
    val dataDir = DesktopPaths.dataDir()

    if (!SingleInstance.acquire(dataDir)) {
        JOptionPane.showMessageDialog(
            null,
            "معامله‌یار از قبل در حال اجراست.\nآیکون آن را کنار ساعت ویندوز (System Tray) پیدا کنید.",
            "معامله‌یار",
            JOptionPane.INFORMATION_MESSAGE
        )
        exitProcess(0)
    }

    val container = AppContainer(dataDir)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val notifier = TrayNotifier()
    val controller = TraderController(container, scope, DesktopHooks(notifier))
    controller.start()

    application {
        var visible by remember { mutableStateOf(!startMinimized) }
        var hintShown by remember { mutableStateOf(false) }
        val trayState = rememberTrayState()
        val icon = remember { appIcon() }
        val state by controller.state.collectAsState()

        LaunchedEffect(trayState) { notifier.trayState = trayState }

        val auto = state.settings.autoTrade
        Tray(
            state = trayState,
            icon = icon,
            tooltip = "معامله‌یار — " + (if (auto) "معامله خودکار روشن" else "معامله خودکار خاموش"),
            onAction = { visible = true },
            menu = {
                Item("نمایش معامله‌یار", onClick = { visible = true })
                Item("بررسی فوری بازار و اخبار", onClick = { controller.refresh() })
                Item(
                    if (auto) "توقف معامله خودکار" else "شروع معامله خودکار",
                    onClick = { controller.toggleAutoTrade(!auto) }
                )
                Separator()
                Item("خروج کامل", onClick = {
                    scope.cancel()
                    exitApplication()
                })
            }
        )

        Window(
            onCloseRequest = {
                visible = false
                if (!hintShown) {
                    hintShown = true
                    notifier.notify(
                        "معامله‌یار در پس‌زمینه فعال است",
                        "برنامه کنار ساعت ویندوز به بررسی بازار و معامله ادامه می‌دهد. برای خروج کامل از منوی آیکون استفاده کنید."
                    )
                }
            },
            visible = visible,
            title = "معامله‌یار — دستیار معاملاتی خودکار (بورس تهران، ارز، طلا، کریپتو)",
            icon = icon,
            state = rememberWindowState(
                size = DpSize(1280.dp, 840.dp),
                position = WindowPosition.PlatformDefault
            )
        ) {
            TraderTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    AppRoot(controller = controller)
                }
            }
        }
    }
}

private fun appIcon(): Painter {
    val stream = Thread.currentThread().contextClassLoader?.getResourceAsStream("app.png")
        ?: TraderController::class.java.classLoader.getResourceAsStream("app.png")
        ?: error("app.png not found in resources")
    return stream.use { BitmapPainter(loadImageBitmap(it)) }
}
