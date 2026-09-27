package com.saeidkazemi.trader.data.local

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.saeidkazemi.trader.data.model.AccountState
import com.saeidkazemi.trader.data.model.AppSettings
import java.io.File

/**
 * ذخیره‌سازی ساده و پایدار مبتنی بر فایل JSON برای حساب، تنظیمات و وضعیت بازار.
 *
 * محافظت از داده‌ها (تا خریدها با به‌روزرسانی یا بسته شدن برنامه از بین نروند):
 *  - اعداد نامعتبر (NaN/بی‌نهایت) دیگر باعث شکست بی‌صدای ذخیره نمی‌شوند؛
 *  - قبل از هر بار ذخیره، نسخه قبلی در «name.bak» نگه داشته می‌شود؛
 *  - اگر فایلی خراب باشد، از نسخه پشتیبان بازیابی می‌شود و فایل خراب هم کنار گذاشته (نه پاک) می‌شود؛
 *  - حساب هرگز به‌خاطر خطای خواندن فایل، بی‌صدا از نو ساخته نمی‌شود.
 */
class JsonStore(private val dir: File) {

    private val gson: Gson = GsonBuilder().serializeSpecialFloatingPointValues().create()
    private val lock = Any()

    /** پیام‌های بازیابی/خطای ذخیره برای نمایش به کاربر. */
    val recoveryNotes: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    @Volatile
    var lastWriteError: String? = null
        private set

    init {
        try {
            dir.mkdirs()
        } catch (_: Exception) {
        }
    }

    fun loadSettings(): AppSettings {
        return read("settings.json", AppSettings::class.java) ?: AppSettings()
    }

    fun saveSettings(settings: AppSettings) {
        write("settings.json", settings)
    }

    fun loadAccount(defaultCapitalUsd: Double): AccountState {
        val acc = read("account.json", AccountState::class.java)
        if (acc != null) return acc
        if (File(dir, "account.json").exists()) {
            recoveryNotes.add("فایل حساب قابل خواندن نبود و نسخه پشتیبان هم نبود؛ نسخه خراب با نام account.json.corrupt-* نگه داشته شد.")
        }
        val fresh = AccountState(cashUsd = defaultCapitalUsd, initialCapitalUsd = defaultCapitalUsd)
        write("account.json", fresh)
        return fresh
    }

    fun saveAccount(account: AccountState) {
        write("account.json", account)
    }

    /** مسیر قیمت موقعیت‌های باز از لحظه خرید (برای نمودار روند). */
    data class TrackFile(val tracks: Map<String, List<com.saeidkazemi.trader.data.model.PricePoint>>? = null)

    fun loadTracks(): Map<String, List<com.saeidkazemi.trader.data.model.PricePoint>> =
        read("tracks.json", TrackFile::class.java)?.tracks.orEmpty().entries
            .mapNotNull { e ->
                // Gson ممکن است برای داده خراب null بگذارد
                val v: List<com.saeidkazemi.trader.data.model.PricePoint?>? = e.value
                if (v == null) null else e.key to v.filterNotNull()
            }
            .toMap()

    fun saveTracks(tracks: Map<String, List<com.saeidkazemi.trader.data.model.PricePoint>>) {
        write("tracks.json", TrackFile(tracks))
    }

    /** ژورنال معاملات (دلایل ورود/خروج و نتیجه). */
    data class JournalFile(val entries: List<com.saeidkazemi.trader.data.model.JournalEntry>? = null)

    @Suppress("SENSELESS_COMPARISON")
    fun loadJournal(): List<com.saeidkazemi.trader.data.model.JournalEntry> {
        val raw: List<com.saeidkazemi.trader.data.model.JournalEntry?> =
            read("journal.json", JournalFile::class.java)?.entries.orEmpty()
        // ردیف خراب (بدون شناسه یا بازار) کنار گذاشته می‌شود
        return raw.filterNotNull().filter { it.id != null && it.market != null && it.assetId != null }
    }

    fun saveJournal(entries: List<com.saeidkazemi.trader.data.model.JournalEntry>) {
        write("journal.json", JournalFile(entries))
    }

    private fun <T> parse(f: File, type: Class<T>): T? =
        try {
            if (!f.exists() || f.length() == 0L) null else gson.fromJson(f.readText(Charsets.UTF_8), type)
        } catch (e: Exception) {
            null
        }

    private fun <T> read(name: String, type: Class<T>): T? {
        return try {
            synchronized(lock) {
                val f = File(dir, name)
                val bak = File(dir, "$name.bak")
                if (!f.exists()) {
                    // اگر برنامه وسط ذخیره بسته شده باشد و فایل اصلی نباشد، پشتیبان جایگزین می‌شود.
                    val b = parse(bak, type) ?: return@synchronized null
                    recoveryNotes.add(label(name) + " از نسخه پشتیبان بازیابی شد.")
                    return@synchronized b
                }
                parse(f, type)?.let { return@synchronized it }
                // فایل خراب: کنار گذاشتن (نه پاک کردن) و بازیابی از پشتیبان
                try {
                    f.copyTo(File(dir, "$name.corrupt-" + System.currentTimeMillis()), overwrite = true)
                } catch (_: Exception) {
                }
                val b = parse(bak, type)
                if (b != null) {
                    recoveryNotes.add(label(name) + " خراب بود و از نسخه پشتیبان بازیابی شد.")
                    try {
                        bak.copyTo(f, overwrite = true)
                    } catch (_: Exception) {
                    }
                }
                b
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun label(name: String): String = when (name) {
        "account.json" -> "حساب (موجودی و خریدها)"
        "journal.json" -> "ژورنال معاملات"
        "settings.json" -> "تنظیمات"
        else -> name
    }

    private fun write(name: String, obj: Any) {
        try {
            val json = gson.toJson(obj)
            synchronized(lock) {
                val target = File(dir, name)
                val tmp = File(dir, "$name.tmp")
                tmp.writeText(json, Charsets.UTF_8)
                // نسخه سالم قبلی به‌عنوان پشتیبان
                if (target.exists() && target.length() > 0L && name != "tracks.json") {
                    try {
                        java.nio.file.Files.copy(
                            target.toPath(),
                            File(dir, "$name.bak").toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING
                        )
                    } catch (_: Exception) {
                    }
                }
                java.nio.file.Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }
            lastWriteError = null
        } catch (e: Exception) {
            lastWriteError = name + ": " + (e.message ?: e.toString())
        }
    }
}
