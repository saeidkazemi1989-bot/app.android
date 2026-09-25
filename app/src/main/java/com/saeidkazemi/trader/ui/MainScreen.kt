package com.saeidkazemi.trader.ui

import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.saeidkazemi.trader.ui.screens.AssetDetailScreen
import com.saeidkazemi.trader.ui.screens.DashboardScreen
import com.saeidkazemi.trader.ui.screens.MarketsScreen
import com.saeidkazemi.trader.ui.screens.PortfolioScreen
import com.saeidkazemi.trader.ui.screens.SettingsScreen
import com.saeidkazemi.trader.ui.screens.SignalsScreen
import com.saeidkazemi.trader.viewmodel.MainViewModel

private data class NavItem(val route: String, val label: String, val icon: ImageVector)

@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state.toastMessage) {
        val msg = state.toastMessage
        if (msg != null) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            vm.consumeToast()
        }
    }

    val navController = rememberNavController()
    val items = listOf(
        NavItem("dashboard", "داشبورد", Icons.Filled.Home),
        NavItem("markets", "بازارها", Icons.Filled.List),
        NavItem("signals", "سیگنال‌ها", Icons.Filled.Star),
        NavItem("portfolio", "پرتفوی", Icons.Filled.ShoppingCart),
        NavItem("settings", "تنظیمات", Icons.Filled.Settings)
    )
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = items.any { it.route == currentRoute }

    val openAsset: (String) -> Unit = { id ->
        vm.openAsset(id)
        navController.navigate("asset/" + id)
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    items.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo("dashboard") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label, fontSize = 11.sp) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(padding)
        ) {
            composable("dashboard") {
                DashboardScreen(
                    state = state,
                    onRefresh = vm::refresh,
                    onToggleAuto = vm::toggleAutoTrade,
                    onOpenAsset = openAsset
                )
            }
            composable("markets") {
                MarketsScreen(state = state, onOpenAsset = openAsset)
            }
            composable("signals") {
                SignalsScreen(state = state, onBuy = vm::manualBuy, onOpenAsset = openAsset)
            }
            composable("portfolio") {
                PortfolioScreen(state = state, onSell = vm::manualSell, onOpenAsset = openAsset)
            }
            composable("settings") {
                SettingsScreen(state = state, vm = vm)
            }
            composable("asset/{id}") { entry ->
                val id = entry.arguments?.getString("id") ?: ""
                AssetDetailScreen(
                    assetId = id,
                    state = state,
                    onBuy = vm::manualBuy,
                    onSell = vm::manualSell,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
