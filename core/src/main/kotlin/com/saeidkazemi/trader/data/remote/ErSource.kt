package com.saeidkazemi.trader.data.remote

import com.google.gson.JsonObject
import okhttp3.Request
import retrofit2.http.GET
import retrofit2.http.Path

class ErDto(val result: String?, val rates: Map<String, Double>?)

interface ErApi {
    @GET("v6/latest/{base}")
    suspend fun latest(@Path("base") base: String): ErDto
}

/**
 * نرخ دلار به ریال برای تبدیل‌های ریالی.
 *
 * ۱) اولویت با نرخ «بازار آزاد» از قیمت تتر در نوبیتکس (USDT/RLS) است، چون نرخ رسمی برای
 *    ارزش‌گذاری سهام و ارز دیجیتال در ایران معنادار نیست.
 * ۲) اگر در دسترس نبود، از open.er-api.com استفاده می‌شود؛ ولی فقط اگر عدد به نرخ بازار نزدیک باشد
 *    (نرخ رسمی/دولتی نادیده گرفته می‌شود و نرخ پشتیبان تنظیمات به کار می‌رود).
 */
class ErSource {

    private val api = Http.retrofit("https://open.er-api.com/", ErApi::class.java)

    suspend fun usdIrr(): Double? {
        nobitexUsdtRls()?.let { return it }
        return try {
            val r = api.latest("USD").rates?.get("IRR")
            if (r != null && r.isFinite() && r > MIN_MARKET_RATE) r else null
        } catch (e: Exception) {
            null
        }
    }

    private fun nobitexUsdtRls(): Double? {
        return try {
            val req = Request.Builder()
                .url("https://apiv2.nobitex.ir/market/stats?srcCurrency=usdt&dstCurrency=rls")
                .header("User-Agent", "TraderBot/MoameleYar-1.1.0")
                .header("Accept", "application/json")
                .build()
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val root = Http.gson.fromJson(body, JsonObject::class.java) ?: return null
                val m = root.getAsJsonObject("stats")?.getAsJsonObject("usdt-rls") ?: return null
                val latest = m.get("latest")
                val v = if (latest == null || latest.isJsonNull) null else latest.asString.toDoubleOrNull()
                if (v != null && v.isFinite() && v > MIN_MARKET_RATE) v else null
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** هر نرخی کمتر از این مقدار (ریال) نرخ رسمی/نامعتبر فرض می‌شود. */
        const val MIN_MARKET_RATE = 200_000.0
    }
}
