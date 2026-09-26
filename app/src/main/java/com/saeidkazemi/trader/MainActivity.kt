package com.saeidkazemi.trader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saeidkazemi.trader.ui.AppRoot
import com.saeidkazemi.trader.ui.theme.TraderTheme
import com.saeidkazemi.trader.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private var viewModelRef: MainViewModel? = null

    override fun onResume() {
        super.onResume()
        // مثلاً پس از بازگشت از پنجره معافیت باتری، وضعیت دوباره خوانده شود.
        viewModelRef?.controller?.refreshPlatform()
    }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { askBatteryOnce() }

    /** یک‌بار درخواست معافیت از بهینه‌سازی باتری تا معامله‌گر با قفل بودن گوشی متوقف نشود. */
    private fun askBatteryOnce() {
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (!prefs.getBoolean("battery_asked", false) && !MainViewModel.batteryIgnored(application)) {
            prefs.edit().putBoolean("battery_asked", true).apply()
            MainViewModel.requestIgnoreBattery(application)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askBatteryOnce()
        }
        setContent {
            TraderTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    val vm: MainViewModel = viewModel()
                    viewModelRef = vm
                    AppRoot(
                        controller = vm.controller,
                        backHandler = { enabled, onBack -> BackHandler(enabled = enabled, onBack = onBack) }
                    )
                }
            }
        }
    }
}
