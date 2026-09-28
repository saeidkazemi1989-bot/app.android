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

            override fun updateDir(): java.io.File = java.io.File(app.cacheDir, "updates")

            override val updateKind: String get() = "apk"

            override fun shareReport(fileName: String, text: String): String? {
                return try {
                    val dir = java.io.File(app.cacheDir, "reports").apply { mkdirs() }
                    dir.listFiles()?.forEach { if (it.name != fileName) it.delete() }
                    val file = java.io.File(dir, fileName)
                    file.writeText(text, Charsets.UTF_8)
                    val uri = androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".updates", file)
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_SUBJECT, "گزارش خودارزیابی معامله‌یار")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    app.startActivity(
                        Intent.createChooser(send, "ارسال گزارش برای تحلیل")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                    "فایل گزارش ساخته شد (" + (file.length() / 1024) + " کیلوبایت)؛ از پنجره باز شده آن را ذخیره کنید یا برای تحلیلگر بفرستید."
                } catch (e: Exception) {
                    "ساخت فایل گزارش ممکن نشد: " + (e.message ?: "")
                }
            }

            override fun launchInstaller(file: java.io.File): String? {
                return try {
                    if (!app.packageManager.canRequestPackageInstalls()) {
                        app.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        return "فقط بار اول: اجازه «نصب برنامه از این منبع» را برای معامله‌یار روشن کنید، برگردید و دوباره «به‌روزرسانی» را بزنید (فایل دانلودشده آماده است)."
                    }
                    val uri = androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".updates", file)
                    app.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    "صفحه نصب اندروید باز شد؛ «به‌روزرسانی» را بزنید. خریدها، ژورنال و تنظیمات حفظ می‌شوند و بعد از نصب، معامله‌گر خودکار دوباره روشن می‌شود."
                } catch (e: Exception) {
                    "باز کردن نصب‌کننده ممکن نشد: " + (e.message ?: "")
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
