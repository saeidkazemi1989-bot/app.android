package com.saeidkazemi.trader.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * رمزنگاری اطلاعات بین دو دستگاه با کلیدی که از «کد اتصال» ساخته می‌شود (AES-256-GCM).
 * بدون کد، نه می‌شود اطلاعات را خواند و نه فرمانی فرستاد؛ سرور واسط اینترنتی هم فقط داده رمزشده می‌بیند.
 */
class SyncCrypto(code: String) {

    private val norm = normalize(code)
    private val key: SecretKeySpec
    /** نام کانال روی سرور واسط (از کد ساخته می‌شود ولی کد را لو نمی‌دهد). */
    val topic: String
    /** شناسه‌ای که دستگاه اصلی در شبکه محلی اعلام می‌کند تا آینه آن را بشناسد. */
    val lanId: String

    init {
        var h = sha256(("moameleyar-sync-key|" + norm).toByteArray())
        repeat(20000) { h = sha256(h + norm.toByteArray()) }
        key = SecretKeySpec(h, "AES")
        topic = "my" + hex(sha256(("moameleyar-topic|" + norm).toByteArray())).take(30)
        lanId = hex(sha256(("moameleyar-lan|" + norm).toByteArray())).take(16)
    }

    fun seal(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val body = c.doFinal(gzip(plain))
        return byteArrayOf(1) + iv + body
    }

    /** باز کردن داده؛ اگر کد اشتباه یا داده دست‌کاری شده باشد، null. */
    fun open(data: ByteArray): ByteArray? = try {
        if (data.size < 30 || data[0] != 1.toByte()) null
        else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data.copyOfRange(1, 13)))
            gunzip(c.doFinal(data.copyOfRange(13, data.size)))
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        private val rnd = SecureRandom()
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

        /** کد تصادفی ۱۰ حرفی به شکل XXXXX-XXXXX. */
        fun newCode(): String {
            val sb = StringBuilder()
            repeat(10) { i ->
                if (i == 5) sb.append('-')
                sb.append(ALPHABET[rnd.nextInt(ALPHABET.length)])
            }
            return sb.toString()
        }

        /** یکسان‌سازی کد واردشده (حروف کوچک، فاصله، خط تیره، ارقام فارسی). */
        fun normalize(code: String): String {
            val sb = StringBuilder()
            for (ch in code.trim()) {
                val c = when (ch) {
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    else -> ch
                }
                if (c.isLetterOrDigit() && c.code < 128) sb.append(c.uppercaseChar())
            }
            return sb.toString()
        }

        fun isValid(code: String): Boolean = normalize(code).length >= 8

        /** نمایش کد به شکل XXXXX-XXXXX. */
        fun pretty(code: String): String {
            val n = normalize(code)
            return if (n.length == 10) n.substring(0, 5) + "-" + n.substring(5) else n
        }

        fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

        fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

        fun gzip(b: ByteArray): ByteArray {
            val bos = ByteArrayOutputStream()
            GZIPOutputStream(bos).use { it.write(b) }
            return bos.toByteArray()
        }

        fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
    }
}
