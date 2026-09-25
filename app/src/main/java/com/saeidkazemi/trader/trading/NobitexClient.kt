package com.saeidkazemi.trader.trading

import com.saeidkazemi.trader.data.local.JsonStore
import com.saeidkazemi.trader.data.model.AppSettings
import com.saeidkazemi.trader.data.model.OrderResult
import com.saeidkazemi.trader.data.remote.Http
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

class NobitexTradeDto(val price: String?, val amount: String?)

class NobitexTradesDto(val ok: Boolean?, val lastTrades: List<NobitexTradeDto>?)

class NobitexOrderDto(val ok: Boolean?, val error: String?, val order: Map<String, Any>?)

interface NobitexApi {

    @GET("market/last-trades")
    suspend fun lastTrades(
        @Query("symbol") symbol: String,
        @Query("limit") limit: Int
    ): NobitexTradesDto

    @FormUrlEncoded
    @POST("orders/add")
    suspend fun addOrder(
        @Header("Authorization") auth: String,
        @Field("symbol") symbol: String,
        @Field("type") type: String,
        @Field("side") side: String,
        @Field("amount") amount: String,
        @Field("price") price: String?
    ): NobitexOrderDto
}

/**
 * اتصال به صرافی نوبیتکس برای معامله واقعی.
 * نکته مهم: این بخش «آزمایشی» است و به‌صورت پیش‌فرض خاموش است.
 * برای فعال‌سازی باید توکن ای‌پی‌آی حساب خودتان را از پنل نوبیتکس بگیرید و در تنظیمات وارد کنید.
 * سفارش‌ها به‌صورت بازار (مارکت) و در بازارهای تومانی ارسال می‌شوند.
 */
class NobitexClient(private val store: JsonStore) {

    private val api by lazy { Http.retrofit("https://api.nobitex.ir/", NobitexApi::class.java) }

    fun configured(settings: AppSettings): Boolean =
        settings.realTrading && settings.nobitexToken.isNotBlank()

    suspend fun lastPriceIrt(marketSymbol: String): Double? {
        return try {
            val trades = api.lastTrades(marketSymbol, 1).lastTrades
            trades?.firstOrNull()?.price?.toDoubleOrNull()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun placeOrder(
        settings: AppSettings,
        marketSymbol: String,
        side: String,
        amountBase: Double
    ): OrderResult {
        if (!configured(settings)) return OrderResult.NotConfigured
        if (!amountBase.isFinite() || amountBase <= 0) return OrderResult.Failure("مقدار سفارش نامعتبر است")
        return try {
            val resp = api.addOrder(
                auth = "Bearer " + settings.nobitexToken.trim(),
                symbol = marketSymbol,
                type = "market",
                side = side,
                amount = amountBase.toString(),
                price = null
            )
            if (resp.ok == true) {
                val id = resp.order?.get("id")?.toString() ?: "ثبت شد"
                OrderResult.Success(id)
            } else {
                OrderResult.Failure(resp.error ?: "پاسخ نامعتبر از نوبیتکس")
            }
        } catch (e: Exception) {
            OrderResult.Failure(e.message ?: "خطای شبکه در ارسال سفارش")
        }
    }
}
