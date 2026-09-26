package com.saeidkazemi.trader.data.local

import com.google.gson.Gson
import com.saeidkazemi.trader.data.model.AccountState
import com.saeidkazemi.trader.data.model.AppSettings
import java.io.File

/** ذخیره‌سازی ساده و پایدار مبتنی بر فایل JSON برای حساب، تنظیمات و وضعیت بازار. */
class JsonStore(private val dir: File) {

    private val gson = Gson()
    private val lock = Any()

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

    private fun <T> read(name: String, type: Class<T>): T? {
        return try {
            synchronized(lock) {
                val f = File(dir, name)
                if (!f.exists()) null else gson.fromJson(f.readText(Charsets.UTF_8), type)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun write(name: String, obj: Any) {
        try {
            synchronized(lock) {
                val target = File(dir, name)
                val tmp = File(dir, "$name.tmp")
                tmp.writeText(gson.toJson(obj), Charsets.UTF_8)
                java.nio.file.Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }
        } catch (_: Exception) {
        }
    }
}
