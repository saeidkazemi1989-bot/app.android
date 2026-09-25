package com.saeidkazemi.trader.data.remote

import retrofit2.http.GET
import retrofit2.http.Path

class GoldDto(val name: String?, val price: Double?)

interface GoldApi {
    @GET("price/{code}")
    suspend fun price(@Path("code") code: String): GoldDto
}

/** قیمت جهانی طلا و نقره از gold-api.com (رایگان، بدون تاریخچه → فقط نمایشی). */
class GoldSource {

    private val api = Http.retrofit("https://api.gold-api.com/", GoldApi::class.java)

    suspend fun xauUsd(): Double? {
        return try {
            val p = api.price("XAU").price
            if (p != null && p.isFinite() && p > 0) p else null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun xagUsd(): Double? {
        return try {
            val p = api.price("XAG").price
            if (p != null && p.isFinite() && p > 0) p else null
        } catch (e: Exception) {
            null
        }
    }
}
