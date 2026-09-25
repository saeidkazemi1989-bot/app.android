package com.saeidkazemi.trader.viewmodel

import android.app.Application
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
                dataLocation = "حافظه داخلی گوشی"
            )

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
}
