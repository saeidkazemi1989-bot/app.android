package com.saeidkazemi.trader

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.saeidkazemi.trader.alert.AlertPlayer
import com.saeidkazemi.trader.core.AppContainer
import com.saeidkazemi.trader.data.model.TradeEvent
import com.saeidkazemi.trader.service.Watchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TraderApp : Application() {

    lateinit var container: AppContainer
        private set

    lateinit var alerts: AlertPlayer
        private set

    /** اسکوپ کل برنامه (مستقل از صفحه‌ها) برای هشدارهای صوتی/لرزشی معاملات. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        container = AppContainer(filesDir)
        alerts = AlertPlayer(this)
        appScope.launch {
            container.tradeEngine.events.collect { ev -> onTradeEvent(ev) }
        }
        // نگهبان: هر ~۱۵ دقیقه (حتی در حالت Doze) مطمئن می‌شود سرویس معامله‌گر زنده است.
        Watchdog.schedule(this)
    }

    private fun onTradeEvent(ev: TradeEvent) {
        val s = container.store.loadSettings()
        when (ev) {
            is TradeEvent.Starting -> if (s.soundAlerts) alerts.playStart(s.loudAlerts)
            is TradeEvent.Completed -> {
                if (s.soundAlerts) alerts.playDone(s.loudAlerts)
                if (s.vibrateAlerts) alerts.vibrateDone(ev.success)
            }
        }
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val serviceChannel = NotificationChannel(
            AppContainer.CHANNEL_SERVICE,
            getString(R.string.notif_channel_service),
            NotificationManager.IMPORTANCE_LOW
        )
        serviceChannel.description = "وضعیت پایش و معامله‌گری خودکار"
        // کانال معاملات بی‌صدا است چون برنامه صدای شروع/پایان معامله و لرزش خودش را پخش می‌کند.
        nm.deleteNotificationChannel(AppContainer.CHANNEL_TRADES)
        val tradesChannel = NotificationChannel(
            CHANNEL_TRADES_QUIET,
            getString(R.string.notif_channel_trades),
            NotificationManager.IMPORTANCE_HIGH
        )
        tradesChannel.description = "اعلان انجام هر معامله"
        tradesChannel.setSound(null, null)
        tradesChannel.enableVibration(false)
        nm.createNotificationChannel(serviceChannel)
        nm.createNotificationChannel(tradesChannel)
    }

    companion object {
        const val CHANNEL_TRADES_QUIET = "trader_trades_quiet"

        lateinit var instance: TraderApp
            private set
    }
}
