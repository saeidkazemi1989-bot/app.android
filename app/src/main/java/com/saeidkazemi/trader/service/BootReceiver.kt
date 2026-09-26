package com.saeidkazemi.trader.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.saeidkazemi.trader.TraderApp

/** اگر معامله خودکار روشن باشد، بعد از روشن شدن گوشی یا به‌روزرسانی برنامه، سرویس دوباره راه می‌افتد. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val ok = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        if (!ok) return
        val app = context.applicationContext as? TraderApp ?: return
        val settings = app.container.store.loadSettings()
        if (settings.autoTrade) {
            TradingService.start(context)
        }
        Watchdog.schedule(context)
    }
}
