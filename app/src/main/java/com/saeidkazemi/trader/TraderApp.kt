package com.saeidkazemi.trader

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.saeidkazemi.trader.core.AppContainer

class TraderApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        container = AppContainer(this)
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val serviceChannel = NotificationChannel(
            AppContainer.CHANNEL_SERVICE,
            getString(R.string.notif_channel_service),
            NotificationManager.IMPORTANCE_LOW
        )
        serviceChannel.description = "وضعیت پایش و معامله‌گری خودکار"
        val tradesChannel = NotificationChannel(
            AppContainer.CHANNEL_TRADES,
            getString(R.string.notif_channel_trades),
            NotificationManager.IMPORTANCE_HIGH
        )
        tradesChannel.description = "اعلان انجام هر معامله"
        nm.createNotificationChannel(serviceChannel)
        nm.createNotificationChannel(tradesChannel)
    }

    companion object {
        lateinit var instance: TraderApp
            private set
    }
}
