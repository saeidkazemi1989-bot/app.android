package com.saeidkazemi.trader.update

import com.google.gson.JsonParser
import com.saeidkazemi.trader.core.AppVersion
import com.saeidkazemi.trader.data.remote.Http
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** اطلاعات آخرین نسخه منتشرشده (فایل version.json کنار فایل‌های دانلود). */
data class UpdateInfo(
    val name: String,
    val code: Int,
    val notes: String,
    val apkUrl: String,
    val apkSha256: String,
    val msiUrl: String,
    val msiSha256: String,
    val builtAt: String
) {
    val isNewer: Boolean get() = code > AppVersion.CODE
}

/** وضعیت به‌روزرسانی داخل برنامه برای نمایش. */
data class UpdateState(
    val checking: Boolean = false,
    val info: UpdateInfo? = null,
    val lastCheckedAt: Long = 0L,
    val error: String? = null,
    /** ۰ تا ۱۰۰ در حال دانلود؛ null یعنی دانلودی در کار نیست. */
    val progress: Int? = null,
    val installing: Boolean = false,
    val supported: Boolean = true
) {
    val available: Boolean get() = info?.isNewer == true
}

/**
 * به‌روزرسانی داخل برنامه: آخرین نسخه را از صفحه دانلود گیت‌هاب می‌خواند، فایل نصب را دانلود و
 * با اثر انگشت SHA-256 بررسی می‌کند. نصب را هر پلتفرم خودش انجام می‌دهد (اندروید: نصب‌کننده سیستم؛
 * ویندوز: MSI). اطلاعات برنامه (خریدها، ژورنال، تنظیمات) با به‌روزرسانی حفظ می‌شود.
 */
object Updater {

    const val BASE = "https://github.com/saeidkazemi1989-bot/app.android/releases/download/latest-build/"

    private val client by lazy {
        Http.client.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun check(): UpdateInfo {
        val req = Request.Builder()
            .url(BASE + "version.json?t=" + System.currentTimeMillis())
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        val body = client.newCall(req).execute().use { r ->
            if (r.code == 404) throw IllegalStateException("فایل نسخه پیدا نشد (شاید نسخه جدید در حال ساخت است؛ چند دقیقه بعد دوباره امتحان کنید)")
            if (!r.isSuccessful) throw IllegalStateException("HTTP " + r.code)
            r.body?.string() ?: throw IllegalStateException("پاسخ خالی")
        }
        val o = JsonParser.parseString(body).asJsonObject
        fun s(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asString ?: ""
        return UpdateInfo(
            name = s("name"),
            code = o.get("code")?.asInt ?: 0,
            notes = s("notes"),
            apkUrl = s("apk").ifEmpty { BASE + "MoameleYar-android.apk" },
            apkSha256 = s("apkSha256").lowercase(),
            msiUrl = s("msi").ifEmpty { BASE + "MoameleYar-windows-setup.msi" },
            msiSha256 = s("msiSha256").lowercase(),
            builtAt = s("builtAt")
        )
    }

    fun sha256Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** دانلود فایل با گزارش پیشرفت و بررسی SHA-256 (اگر در version.json باشد). */
    fun download(url: String, dest: File, sha256: String, progress: (Int) -> Unit): File {
        dest.parentFile?.mkdirs()
        // اگر همین فایل قبلاً کامل دانلود شده (مثلاً بعد از دادن اجازه نصب در اندروید)، دوباره دانلود نمی‌شود
        if (dest.exists() && sha256.isNotEmpty() && sha256Of(dest) == sha256) {
            progress(100)
            return dest
        }
        // نسخه‌های قدیمی‌تر دانلودشده پاک می‌شوند
        dest.parentFile?.listFiles()?.forEach { if (it.name != dest.name && it.name.startsWith("MoameleYar-")) it.delete() }
        val tmp = File(dest.parentFile, dest.name + ".part")
        val md = MessageDigest.getInstance("SHA-256")
        client.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("دانلود ناموفق (HTTP " + r.code + ")")
            val body = r.body ?: throw IllegalStateException("پاسخ خالی")
            val total = body.contentLength()
            var done = 0L
            var lastPct = -1
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; progress(pct) }
                        }
                    }
                }
            }
        }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        if (sha256.isNotEmpty() && got != sha256) {
            tmp.delete()
            throw IllegalStateException("فایل دانلودشده سالم نبود (اثر انگشت یکسان نیست)؛ دوباره امتحان کنید.")
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        return dest
    }
}
