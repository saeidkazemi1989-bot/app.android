package com.saeidkazemi.trader.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.saeidkazemi.trader.TraderApp

/**
 * نگهبان سرویس: با AlarmManager (حتی در حالت Doze و قفل بودن گوشی) هر ~۱۵ دقیقه بیدار می‌شود و
 * اگر معامله خودکار روشن باشد ولی سرویس توسط سیستم بسته شده باشد، دوباره آن را اجرا می‌کند.
 */
object Watchdog {

    private const val INTERVAL_MS = 15 * 60_000L
    private const val REQ = 4242

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, WatchdogReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(
            context, REQ, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    const val ACTION = "com.saeidkazemi.trader.WATCHDOG"

    fun schedule(context: Context, delayMs: Long = INTERVAL_MS) {
        try {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pending(context)
            )
        } catch (_: Exception) {
        }
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            val app = context.applicationContext as? TraderApp
            val auto = app?.container?.store?.loadSettings()?.autoTrade ?: true
            if (auto && !TradingService.running) TradingService.start(context)
        } catch (_: Exception) {
        }
        Watchdog.schedule(context)
    }
}
