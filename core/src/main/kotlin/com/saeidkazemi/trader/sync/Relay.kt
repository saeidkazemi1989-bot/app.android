package com.saeidkazemi.trader.sync

import com.google.gson.JsonParser
import com.saeidkazemi.trader.data.remote.Http
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * واسط اینترنتی از طریق سرویس رایگان ntfy (بدون ثبت‌نام). هر دستگاه فقط داده رمزشده می‌فرستد؛
 * نام کانال از کد اتصال ساخته می‌شود.
 *
 * محدودیت‌های سرور عمومی ntfy.sh: حدود ۲۵۰ پیام در روز برای هر نشانی اینترنتی، فایل حداکثر ۲ مگابایت
 * و نگهداری فایل ۳ ساعت؛ برای همین وضعیت حداکثر هر چند دقیقه یک بار (و فقط در صورت تغییر) فرستاده می‌شود.
 */
class Relay(private val baseUrl: String) {

    data class Message(val id: String, val time: Long, val text: String?, val attachmentUrl: String?)

    private val client by lazy {
        Http.client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun url(topic: String) = baseUrl.trimEnd('/') + "/" + topic

    /** ارسال داده دودویی (به‌صورت فایل پیوست در ntfy). */
    fun publishBinary(topic: String, data: ByteArray): String? {
        val req = Request.Builder()
            .url(url(topic))
            .header("Filename", "s.bin")
            .header("Title", "MoameleYar")
            .put(data.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP " + r.code)
            val b = r.body?.string() ?: return null
            return try { JsonParser.parseString(b).asJsonObject.get("id")?.asString } catch (_: Exception) { null }
        }
    }

    /** ارسال متن کوتاه (فرمان‌ها؛ base64 رمزشده). */
    fun publishText(topic: String, text: String) {
        val req = Request.Builder()
            .url(url(topic))
            .header("Title", "MoameleYar")
            .post(text.toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP " + r.code)
        }
    }

    /** پیام‌های کانال از [since] (شناسه پیام یا مدت مثل 3h). */
    fun poll(topic: String, since: String): List<Message> {
        val req = Request.Builder()
            .url(url(topic) + "/json?poll=1&since=" + since)
            .get()
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP " + r.code)
            val body = r.body?.string() ?: return emptyList()
            val out = ArrayList<Message>()
            for (line in body.split('\n')) {
                if (line.isBlank()) continue
                try {
                    val o = JsonParser.parseString(line).asJsonObject
                    if (o.get("event")?.asString != "message") continue
                    val att = o.getAsJsonObject("attachment")
                    out.add(
                        Message(
                            id = o.get("id").asString,
                            time = (o.get("time")?.asLong ?: 0L) * 1000,
                            text = o.get("message")?.takeIf { !it.isJsonNull }?.asString,
                            attachmentUrl = att?.get("url")?.takeIf { !it.isJsonNull }?.asString
                        )
                    )
                } catch (_: Exception) {
                }
            }
            return out
        }
    }

    fun download(url: String): ByteArray {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP " + r.code)
            return r.body?.bytes() ?: ByteArray(0)
        }
    }
}
