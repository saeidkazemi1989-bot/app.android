package com.saeidkazemi.trader.desktop

import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState
import com.saeidkazemi.trader.core.TraderController
import com.saeidkazemi.trader.data.model.CycleReport
import com.saeidkazemi.trader.ui.PlatformInfo
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.util.concurrent.TimeUnit

/** مسیرهای ذخیره‌سازی نسخه ویندوز. */
object DesktopPaths {

    /** ویندوز: %APPDATA%\MoameleYar — سایر سیستم‌ها: ~/.moameleyar */
    fun dataDir(): File {
        val appData = System.getenv("APPDATA")
        val dir = if (!appData.isNullOrBlank()) File(appData, "MoameleYar")
        else File(System.getProperty("user.home"), ".moameleyar")
        dir.mkdirs()
        return dir
    }
}

/**
 * جلوگیری از اجرای هم‌زمان دو نسخه (مثلاً یکی از Startup ویندوز و یکی دستی)
 * تا دو موتور روی یک حساب معامله نکنند.
 */
object SingleInstance {
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    fun acquire(dir: File): Boolean {
        return try {
            val raf = RandomAccessFile(File(dir, "app.lock"), "rw")
            val ch = raf.channel
            val l = ch.tryLock()
            if (l == null) {
                ch.close()
                false
            } else {
                channel = ch
                lock = l
                true
            }
        } catch (e: Exception) {
            true // اگر قفل ممکن نبود، اجرای برنامه را متوقف نمی‌کنیم.
        }
    }
}

/** اجرای خودکار برنامه با روشن شدن ویندوز از طریق کلید Run رجیستری کاربر جاری. */
object WindowsAutostart {
    private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val VALUE = "MoameleYar"

    val isWindows: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("windows")

    /** مسیر فایل اجرایی برنامه نصب‌شده (توسط jpackage تنظیم می‌شود). */
    private val exePath: String? get() = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }

    val supported: Boolean get() = isWindows && exePath != null

    fun isEnabled(): Boolean {
        if (!isWindows) return false
        return run(listOf("reg", "query", RUN_KEY, "/v", VALUE)) == 0
    }

    fun set(on: Boolean): Boolean {
        if (!supported) return false
        val code = if (on) {
            run(listOf("reg", "add", RUN_KEY, "/v", VALUE, "/t", "REG_SZ", "/d", "\"" + exePath + "\" --minimized", "/f"))
        } else {
            run(listOf("reg", "delete", RUN_KEY, "/v", VALUE, "/f"))
        }
        return code == 0
    }

    private fun run(cmd: List<String>): Int {
        return try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroy()
                -1
            } else p.exitValue()
        } catch (e: Exception) {
            -1
        }
    }
}

/** ارسال اعلان ویندوز (کنار ساعت) از طریق System Tray. */
class TrayNotifier {
    @Volatile
    var trayState: TrayState? = null

    fun notify(title: String, message: String, warning: Boolean = false) {
        try {
            trayState?.sendNotification(
                Notification(title, message, if (warning) Notification.Type.Warning else Notification.Type.Info)
            )
        } catch (_: Exception) {
        }
    }
}

/** رفتارهای مخصوص ویندوز برای کنترلر مشترک. */
class DesktopHooks(private val notifier: TrayNotifier) : TraderController.PlatformHooks {

    // در ویندوز خود برنامه (حتی وقتی پنجره بسته و در Tray است) حلقه ۶۰ثانیه‌ای را اجرا می‌کند.
    override val runsOwnLoop: Boolean = true

    override fun platformInfo(): PlatformInfo = PlatformInfo(
        isDesktop = true,
        name = "ویندوز",
        autostartSupported = WindowsAutostart.supported,
        autostartEnabled = WindowsAutostart.isEnabled(),
        dataLocation = DesktopPaths.dataDir().absolutePath
    )

    override fun onCycleReport(report: CycleReport) {
        if (report.tradesCount == 0) return
        val lines = mutableListOf<String>()
        if (report.buys.isNotEmpty()) lines.add("خرید: " + report.buys.joinToString("، "))
        if (report.sells.isNotEmpty()) lines.add("فروش: " + report.sells.joinToString("، "))
        notifier.notify("معامله خودکار انجام شد", lines.joinToString("\n"))
    }

    override fun setAutostart(on: Boolean): Boolean = WindowsAutostart.set(on)
}
