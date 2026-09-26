package com.saeidkazemi.trader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saeidkazemi.trader.core.TraderController
import com.saeidkazemi.trader.ui.screens.AssetDetailScreen
import com.saeidkazemi.trader.ui.screens.DashboardScreen
import com.saeidkazemi.trader.ui.screens.JournalScreen
import com.saeidkazemi.trader.ui.screens.MarketsScreen
import com.saeidkazemi.trader.ui.screens.NewsScreen
import com.saeidkazemi.trader.ui.screens.PortfolioScreen
import com.saeidkazemi.trader.ui.screens.SettingsScreen
import com.saeidkazemi.trader.ui.screens.SignalsScreen

private data class NavItem(val route: String, val label: String, val icon: ImageVector)

private val navItems = listOf(
    NavItem("dashboard", "داشبورد", Icons.Filled.Home),
    NavItem("markets", "بازارها", Icons.Filled.List),
    NavItem("signals", "سیگنال‌ها", Icons.Filled.Star),
    NavItem("news", "اخبار", Icons.Filled.Notifications),
    NavItem("portfolio", "پرتفوی", Icons.Filled.ShoppingCart),
    NavItem("journal", "ژورنال", Icons.Filled.DateRange),
    NavItem("settings", "تنظیمات", Icons.Filled.Settings)
)

/**
 * ریشه رابط کاربری — مشترک بین اندروید و ویندوز.
 * روی صفحه باریک (گوشی) نوار پایین و روی صفحه عریض (ویندوز/تبلت) نوار کناری نمایش داده می‌شود.
 *
 * @param backHandler در اندروید دکمه برگشت سیستم را به صفحه قبل وصل می‌کند؛ در ویندوز کاری نمی‌کند.
 */
@Composable
fun AppRoot(
    controller: TraderController,
    backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> }
) {
    val state by controller.state.collectAsState()
    var route by remember { mutableStateOf("dashboard") }
    var assetId by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.toastMessage) {
        val msg = state.toastMessage
        if (msg != null) {
            controller.consumeToast()
            snackbar.showSnackbar(msg)
        }
    }

    val openAsset: (String) -> Unit = { id ->
        controller.openAsset(id)
        assetId = id
    }
    val closeAsset: () -> Unit = {
        assetId = null
        controller.closeAsset()
    }

    backHandler(assetId != null) { closeAsset() }
    backHandler(assetId == null && route != "dashboard") { route = "dashboard" }

    val content: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier = modifier.fillMaxSize()) {
            val current = assetId
            if (current != null) {
                AssetDetailScreen(
                    assetId = current,
                    state = state,
                    onBuy = controller::manualBuy,
                    onSell = controller::manualSell,
                    onBack = closeAsset
                )
            } else {
                when (route) {
                    "markets" -> MarketsScreen(state = state, onOpenAsset = openAsset)
                    "signals" -> SignalsScreen(state = state, onBuy = controller::manualBuy, onOpenAsset = openAsset)
                    "news" -> NewsScreen(state = state, onRefreshNews = controller::refreshNews, onOpenAsset = openAsset)
                    "portfolio" -> PortfolioScreen(state = state, onSell = controller::manualSell, onOpenAsset = openAsset)
                    "journal" -> JournalScreen(state = state, onToast = controller::toast, onOpenAsset = openAsset)
                    "settings" -> SettingsScreen(state = state, vm = controller)
                    else -> DashboardScreen(
                        state = state,
                        onRefresh = controller::refresh,
                        onToggleAuto = controller::toggleAutoTrade,
                        onOpenAsset = openAsset
                    )
                }
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!wide && assetId == null) {
                    NavigationBar {
                        navItems.forEach { item ->
                            NavigationBarItem(
                                selected = route == item.route,
                                onClick = { route = item.route },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label, fontSize = 10.sp, maxLines = 1) },
                                alwaysShowLabel = true
                            )
                        }
                    }
                }
            }
        ) { padding ->
            if (wide) {
                Row(modifier = Modifier.fillMaxSize().padding(padding)) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight(),
                        header = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "معامله‌یار",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(top = 12.dp)
                                )
                                Text(
                                    if (state.settings.autoTrade) "● خودکار روشن" else "○ خودکار خاموش",
                                    fontSize = 10.sp,
                                    color = if (state.settings.autoTrade) MaterialTheme.colorScheme.secondary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(12.dp))
                            }
                        }
                    ) {
                        navItems.forEach { item ->
                            NavigationRailItem(
                                selected = assetId == null && route == item.route,
                                onClick = {
                                    if (assetId != null) closeAsset()
                                    route = item.route
                                },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label, fontSize = 12.sp) }
                            )
                        }
                    }
                    // محتوای وسط‌چین با حداکثر عرض مناسب برای خوانایی روی مانیتورهای بزرگ
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        content(Modifier.widthIn(max = 1100.dp))
                    }
                }
            } else {
                content(Modifier.padding(padding))
            }
        }
    }
}
