package com.saeidkazemi.trader.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.saeidkazemi.trader.TraderApp
import com.saeidkazemi.trader.core.TraderController
import com.saeidkazemi.trader.data.model.CycleReport
import com.saeidkazemi.trader.service.TradingService
import com.saeidkazemi.trader.ui.PlatformInfo

/**
 * ویومدل اندروید: فقط یک [TraderController] مشترک (همان منطق نسخه ویندوز) را نگه می‌دارد و
 * رفتارهای مخصوص اندروید (شروع/توقف سرویس پیش‌زمینه) را به آن وصل می‌کند.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    val controller = TraderController(
        container = (app as TraderApp).container,
        scope = viewModelScope,
        hooks = object : TraderController.PlatformHooks {
            // حلقه ۶۰ثانیه‌ای در سرویس پیش‌زمینه اجرا می‌شود تا با بستن اپ هم ادامه پیدا کند.
            override val runsOwnLoop: Boolean = false

            override fun platformInfo(): PlatformInfo = PlatformInfo(
                isDesktop = false,
                name = "اندروید",
                autostartSupported = false,
                autostartEnabled = false,
                dataLocation = "حافظه داخلی گوشی",
                batteryOptimizationIgnored = batteryIgnored(app),
                vibrationSupported = (app as TraderApp).alerts.hasVibrator(),
                manufacturer = Build.MANUFACTURER ?: ""
            )

            override fun requestBatteryExemption() = requestIgnoreBattery(app)

            override fun openAppSettings() {
                try {
                    app.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) {
                }
            }

            override fun onAutoTradeChanged(on: Boolean) {
                if (on) TradingService.start(getApplication()) else TradingService.stop(getApplication())
            }

            override fun onCycleReport(report: CycleReport) {
                // اعلان معاملات در اندروید توسط سرویس ارسال می‌شود.
            }
        }
    )

    init {
        controller.start()
    }

    companion object {
        fun batteryIgnored(app: Application): Boolean = try {
            app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) == true
        } catch (_: Exception) {
            false
        }

        /** نمایش پنجره سیستمی «اجازه اجرا در پس‌زمینه بدون محدودیت باتری». */
        fun requestIgnoreBattery(app: Application) {
            if (batteryIgnored(app)) return
            try {
                @Suppress("BatteryLife")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + app.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(intent)
            } catch (_: Exception) {
                try {
                    app.startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) {
                }
            }
        }
    }
}
