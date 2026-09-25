package com.saeidkazemi.trader.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.saeidkazemi.trader.MainActivity
import com.saeidkazemi.trader.R
import com.saeidkazemi.trader.TraderApp
import com.saeidkazemi.trader.core.AppContainer
import com.saeidkazemi.trader.data.model.CycleReport
import com.saeidkazemi.trader.util.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * سرویس پیش‌زمینه برای معامله‌گری خودکار: هر ۶۰ ثانیه یک دور کامل
 * (به‌روزرسانی بازار، تحلیل، اعمال حد ضرر/حد سود، خرید و فروش خودکار) اجرا می‌کند.
 */
class TradingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopStarted = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("در حال پایش بازارها…"))
        if (!loopStarted) {
            loopStarted = true
            scope.launch { loop() }
        }
        return START_STICKY
    }

    private suspend fun loop() {
        while (coroutineContext.isActive) {
            val container = TraderApp.instance.container
            val settings = container.store.loadSettings()
            if (settings.autoTrade) {
                try {
                    val report = container.tradeEngine.runCycle("service")
                    if (report.tradesCount > 0) notifyTrades(container, report)
                    updateNotification(
                        "آخرین بررسی: " + Format.dateTime(report.ts) + " — " + report.summary()
                    )
                } catch (e: Exception) {
                    updateNotification("خطا در بررسی بازار: " + (e.message ?: ""))
                }
            } else {
                updateNotification("معامله خودکار خاموش است؛ فقط پایش بازار")
            }
            delay(CYCLE_MS)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, AppContainer.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_trader)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    private fun notifyTrades(container: com.saeidkazemi.trader.core.AppContainer, report: CycleReport) {
        try {
            val lines = mutableListOf<String>()
            if (report.buys.isNotEmpty()) lines.add("خرید: " + report.buys.joinToString("، "))
            if (report.sells.isNotEmpty()) lines.add("فروش: " + report.sells.joinToString("، "))
            val notif = NotificationCompat.Builder(this, AppContainer.CHANNEL_TRADES)
                .setSmallIcon(R.drawable.ic_stat_trader)
                .setContentTitle("معامله خودکار انجام شد")
                .setContentText(lines.joinToString(" | "))
                .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(this).notify(NOTIF_TRADES, notif)
        } catch (_: Exception) {
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 1001
        private const val NOTIF_TRADES = 1002
        private const val CYCLE_MS = 60_000L

        fun start(context: Context) {
            val intent = Intent(context, TradingService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, TradingService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
