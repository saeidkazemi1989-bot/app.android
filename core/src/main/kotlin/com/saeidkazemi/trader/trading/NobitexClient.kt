package com.saeidkazemi.trader.trading

import com.google.gson.JsonObject
import com.saeidkazemi.trader.data.local.JsonStore
import com.saeidkazemi.trader.data.model.AppSettings
import com.saeidkazemi.trader.data.model.OrderResult
import com.saeidkazemi.trader.data.remote.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * اتصال به صرافی نوبیتکس برای معامله واقعی (طبق مستندات رسمی apidocs.nobitex.ir).
 *
 * - قیمت: `GET https://apiv2.nobitex.ir/market/stats?srcCurrency=btc&dstCurrency=rls` (عمومی، بدون توکن)
 * - سفارش: `POST https://apiv2.nobitex.ir/market/orders/add` با بدنه JSON و هدر `Authorization: Token <توکن>`
 *
 * نکته مهم: این بخش «آزمایشی» است و به‌صورت پیش‌فرض خاموش است. سفارش‌ها از نوع «بازار» (market)
 * و در بازارهای ریالی ثبت می‌شوند. قیمت بازارهای ریالی نوبیتکس به «ریال» است.
 */
class NobitexClient(@Suppress("unused") private val store: JsonStore) {

    companion object {
        private const val BASE = "https://apiv2.nobitex.ir/"
        private const val USER_AGENT = "TraderBot/MoameleYar-1.1.0"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** دقت اعشار مجاز مقدار سفارش برای هر ارز (محافظه‌کارانه؛ مقدار رو به پایین گرد می‌شود). */
        private val precision = mapOf(
            "btc" to 6, "eth" to 4, "ltc" to 3, "bnb" to 3, "sol" to 2, "avax" to 2,
            "ton" to 1, "dot" to 1, "link" to 1, "xrp" to 1, "usdt" to 2,
            "ada" to 0, "doge" to 0, "trx" to 0
        )
    }

    fun configured(settings: AppSettings): Boolean =
        settings.realTrading && settings.nobitexToken.isNotBlank()

    /** آخرین قیمت ریالی یک ارز در نوبیتکس (مثلاً btc → قیمت هر بیت‌کوین به ریال). */
    suspend fun lastPriceRls(currency: String): Double? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(BASE + "market/stats?srcCurrency=" + currency + "&dstCurrency=rls")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val root = Http.gson.fromJson(body, JsonObject::class.java) ?: return@withContext null
                val stats = root.getAsJsonObject("stats") ?: return@withContext null
                val m = stats.getAsJsonObject("$currency-rls") ?: return@withContext null
                val latest = m.get("latest")
                if (latest == null || latest.isJsonNull) null else latest.asString.toDoubleOrNull()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun formatAmount(currency: String, amount: Double): String {
        val digits = precision[currency] ?: 4
        return BigDecimal(amount).setScale(digits, RoundingMode.DOWN).stripTrailingZeros().toPlainString()
    }

    /**
     * ثبت سفارش بازار.
     * @param currency نماد ارز در نوبیتکس (مثل btc)
     * @param side "buy" یا "sell"
     * @param amountBase مقدار به واحد خود ارز
     */
    suspend fun placeOrder(
        settings: AppSettings,
        currency: String,
        side: String,
        amountBase: Double
    ): OrderResult = withContext(Dispatchers.IO) {
        if (!configured(settings)) return@withContext OrderResult.NotConfigured
        if (!amountBase.isFinite() || amountBase <= 0) {
            return@withContext OrderResult.Failure("مقدار سفارش نامعتبر است")
        }
        val amountStr = formatAmount(currency, amountBase)
        if ((amountStr.toDoubleOrNull() ?: 0.0) <= 0.0) {
            return@withContext OrderResult.Failure("مقدار سفارش پس از گرد کردن صفر شد")
        }
        try {
            val payload = JsonObject().apply {
                addProperty("type", side)
                addProperty("execution", "market")
                addProperty("srcCurrency", currency)
                addProperty("dstCurrency", "rls")
                addProperty("amount", amountStr)
                addProperty("clientOrderId", "my" + System.currentTimeMillis().toString(36))
            }
            val req = Request.Builder()
                .url(BASE + "market/orders/add")
                .header("Authorization", "Token " + settings.nobitexToken.trim())
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody(JSON))
                .build()
            Http.client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val root = try {
                    Http.gson.fromJson(body, JsonObject::class.java)
                } catch (e: Exception) {
                    null
                }
                val status = root?.get("status")?.takeIf { !it.isJsonNull }?.asString
                if (resp.isSuccessful && status == "ok") {
                    val id = root?.getAsJsonObject("order")?.get("id")?.toString() ?: "ثبت شد"
                    OrderResult.Success(id)
                } else {
                    val msg = root?.get("message")?.takeIf { !it.isJsonNull }?.asString
                        ?: root?.get("code")?.takeIf { !it.isJsonNull }?.asString
                        ?: ("HTTP " + resp.code)
                    OrderResult.Failure(msg)
                }
            }
        } catch (e: Exception) {
            OrderResult.Failure(e.message ?: "خطای شبکه در ارسال سفارش")
        }
    }
}
