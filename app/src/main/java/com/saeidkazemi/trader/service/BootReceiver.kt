package com.saeidkazemi.trader.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.saeidkazemi.trader.TraderApp

/** اگر معامله خودکار روشن باشد، بعد از روشن شدن گوشی سرویس دوباره راه می‌افتد. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? TraderApp ?: return
        val settings = app.container.store.loadSettings()
        if (settings.autoTrade) {
            TradingService.start(context)
        }
    }
}
