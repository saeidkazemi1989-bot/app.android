package com.saeidkazemi.trader.data.remote

import retrofit2.http.GET
import retrofit2.http.Path

class ErDto(val result: String?, val rates: Map<String, Double>?)

interface ErApi {
    @GET("v6/latest/{base}")
    suspend fun latest(@Path("base") base: String): ErDto
}

/** نرخ دلار به ریال از open.er-api.com برای تبدیل‌های ریالی. */
class ErSource {

    private val api = Http.retrofit("https://open.er-api.com/", ErApi::class.java)

    suspend fun usdIrr(): Double? {
        return try {
            val r = api.latest("USD").rates?.get("IRR")
            if (r != null && r.isFinite() && r > 0) r else null
        } catch (e: Exception) {
            null
        }
    }
}
