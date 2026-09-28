package com.saeidkazemi.trader.review

import com.saeidkazemi.trader.sync.Relay
import java.security.SecureRandom

/**
 * ارسال آنلاین گزارش خودارزیابی برای تحلیل: متن گزارش در چند پیام (هر کدام کمتر از ۴ کیلوبایت) روی یک
 * کانال تصادفی سرویس ntfy گذاشته می‌شود و فقط کسی که «کد گزارش» را دارد می‌تواند آن را بخواند.
 * سرور عمومی ntfy.sh پیام‌ها را حدود ۱۲ ساعت نگه می‌دارد. توکن نوبیتکس داخل گزارش نیست.
 *
 * خواندن: https://ntfy.sh/moameleyar-report-<کد بدون خط تیره، حروف کوچک>/json?poll=1&since=all
 * هر پیام: «MYR1 i/n» + خط جدید + بخشی از متن؛ بخش‌ها به ترتیب i پشت سر هم قرار می‌گیرند.
 */
object ReportUploader {

    const val TOPIC_PREFIX = "moameleyar-report-"
    private const val CHUNK_BYTES = 3700
    const val MAX_CHUNKS = 45
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private val rnd = SecureRandom()

    fun newCode(): String {
        val s = StringBuilder()
        repeat(8) { s.append(ALPHABET[rnd.nextInt(ALPHABET.length)]) }
        return s.substring(0, 4) + "-" + s.substring(4)
    }

    fun topic(code: String): String = TOPIC_PREFIX + code.replace("-", "").lowercase()

    fun readUrl(code: String, base: String = "https://ntfy.sh"): String = base.trimEnd('/') + "/" + topic(code) + "/json?poll=1&since=all"

    /** تقسیم متن به بخش‌هایی با حداکثر [CHUNK_BYTES] بایت (ترجیحاً سر خط). */
    fun split(text: String, maxBytes: Int = CHUNK_BYTES): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var bytes = 0
        fun flush() {
            if (cur.isNotEmpty()) out.add(cur.toString())
            cur.setLength(0)
            bytes = 0
        }
        val lines = text.split('\n')
        for ((idx, ln) in lines.withIndex()) {
            val piece = if (idx < lines.size - 1) ln + "\n" else ln
            if (piece.isEmpty()) continue
            val b = piece.toByteArray(Charsets.UTF_8).size
            if (b > maxBytes) {
                // خط خیلی بلند (مثل JSON): حرف‌به‌حرف تقسیم می‌شود
                for (ch in piece) {
                    val cb = ch.toString().toByteArray(Charsets.UTF_8).size
                    if (bytes + cb > maxBytes) flush()
                    cur.append(ch)
                    bytes += cb
                }
                continue
            }
            if (bytes + b > maxBytes) flush()
            cur.append(piece)
            bytes += b
        }
        flush()
        return out
    }

    /** ارسال و برگرداندن کد گزارش. */
    fun upload(text: String, relayUrl: String = "https://ntfy.sh"): String {
        var parts = split(text)
        if (parts.size > MAX_CHUNKS) {
            // گزارش خیلی بزرگ: بخش JSON خام کوتاه می‌شود
            parts = split(ReviewReport.shortText(text) + "\n(بخش JSON به‌خاطر حجم زیاد ارسال نشد؛ فایل کامل را از دکمه «فایل» بفرستید.)\n")
            if (parts.size > MAX_CHUNKS) parts = parts.take(MAX_CHUNKS)
        }
        val code = newCode()
        val relay = Relay(relayUrl)
        val t = topic(code)
        parts.forEachIndexed { i, p ->
            var tries = 0
            while (true) {
                try {
                    relay.publishText(t, "MYR1 " + (i + 1) + "/" + parts.size + "\n" + p)
                    break
                } catch (e: Exception) {
                    tries++
                    if (tries >= 3) throw e
                    Thread.sleep(1500L * tries)
                }
            }
        }
        return code
    }
}
