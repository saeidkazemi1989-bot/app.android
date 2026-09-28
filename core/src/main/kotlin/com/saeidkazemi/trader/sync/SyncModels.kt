package com.saeidkazemi.trader.sync

import com.saeidkazemi.trader.analysis.Backtest
import com.saeidkazemi.trader.data.model.AccountState
import com.saeidkazemi.trader.data.model.AppSettings
import com.saeidkazemi.trader.data.model.JournalEntry
import com.saeidkazemi.trader.trading.MarketActivity

/** نقش این دستگاه در اتصال اندروید و ویندوز. */
object SyncRole {
    /** مستقل (بدون اتصال). */
    const val OFF = "OFF"

    /** دستگاه اصلی: معامله می‌کند و وضعیتش را به دستگاه دیگر می‌دهد (پیش‌فرض گوشی). */
    const val HOST = "HOST"

    /** آینه: وضعیت دستگاه اصلی را نشان می‌دهد و فرمان‌ها را برای آن می‌فرستد. */
    const val FOLLOWER = "FOLLOWER"
}

/** تنظیمات اتصال (روی هر دستگاه جدا ذخیره می‌شود و هرگز کپی نمی‌شود). */
data class SyncConfig(
    val role: String = "",
    /** کد اتصال مشترک (کلید رمزنگاری از آن ساخته می‌شود). */
    val code: String = "",
    val lanEnabled: Boolean = true,
    val relayEnabled: Boolean = true,
    /** سرور واسط اینترنتی (ntfy؛ قابل تغییر به سرور شخصی). */
    val relayUrl: String = "https://ntfy.sh",
    /** آخرین نشانی دستگاه اصلی در شبکه محلی (ip:port). */
    val lastHost: String = "",
    val deviceName: String = ""
)

/** وضعیت کامل دستگاه اصلی که به آینه فرستاده می‌شود. */
data class SyncSnapshot(
    val v: Int = 1,
    /** اثر انگشت محتوا (برای تشخیص تغییر). */
    val hash: String = "",
    val createdAt: Long = 0L,
    val device: String = "",
    val appVersion: String = "",
    val settings: AppSettings? = null,
    val hostHasToken: Boolean = false,
    val account: AccountState? = null,
    val journal: List<JournalEntry>? = null,
    val journalTrimmed: Int = 0,
    val backtest: Backtest.Report? = null,
    val activity: List<MarketActivity>? = null,
    val notes: List<String>? = null,
    val lastCycleAt: Long = 0L,
    val results: List<SyncResult>? = null,
    /** خلاصه خودارزیابی دستگاه اصلی (کجا درست، کجا غلط). */
    val review: com.saeidkazemi.trader.review.ReviewSummary? = null
)

/** فرمانی که آینه برای دستگاه اصلی می‌فرستد. */
data class SyncCommand(
    val id: String = "",
    val at: Long = 0L,
    /** settings | allocations | capital | reset | buy | sell | backtest | refresh | report */
    val type: String = "",
    val args: Map<String, String>? = null,
    /** برای type=settings: فقط فیلدهای تغییرکرده تنظیمات (JSON). */
    val patch: String? = null,
    val from: String = ""
)

/** نتیجه اجرای فرمان روی دستگاه اصلی. */
data class SyncResult(
    val id: String = "",
    val at: Long = 0L,
    val ok: Boolean = true,
    val message: String = "",
    /** داده همراه (مثلاً متن گزارش خودارزیابی)؛ در فهرست نتیجه‌های وضعیت ذخیره نمی‌شود. */
    val data: String? = null
)

/** وضعیت اتصال برای نمایش در برنامه. */
data class SyncStatus(
    val role: String = SyncRole.OFF,
    val code: String = "",
    val lanEnabled: Boolean = true,
    val relayEnabled: Boolean = true,
    val relayUrl: String = "https://ntfy.sh",
    /** نشانی‌های این دستگاه در شبکه محلی (برای دستگاه اصلی). */
    val localAddresses: List<String> = emptyList(),
    val lastHost: String = "",
    /** «شبکه محلی» / «اینترنت» / «قطع». */
    val channel: String = "",
    val connected: Boolean = false,
    val lastSyncAt: Long = 0L,
    val peerDevice: String = "",
    val peerVersion: String = "",
    val peerLastCycleAt: Long = 0L,
    /** برای دستگاه اصلی: آخرین باری که آینه وصل شد. */
    val lastPeerSeenAt: Long = 0L,
    val message: String = "",
    val pendingCommands: Int = 0
)
