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
