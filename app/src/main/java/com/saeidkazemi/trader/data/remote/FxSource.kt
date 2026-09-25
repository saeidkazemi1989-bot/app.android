package com.saeidkazemi.trader.data.remote

import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.data.model.PricePoint
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class FxLatestDto(val rates: Map<String, Double>?)

class FxHistoryDto(val rates: Map<String, Map<String, Double>>?)

interface FxApi {
    @GET("v1/latest")
    suspend fun latest(
        @Query("base") base: String,
        @Query("symbols") symbols: String
    ): FxLatestDto

    @GET("v1/{range}")
    suspend fun history(
        @Path("range", encoded = true) range: String,
        @Query("base") base: String,
        @Query("symbols") symbols: String
    ): FxHistoryDto
}

/**
 * منبع ارزهای خارجی: frankfurter.dev (نرخ‌های مرجع بانک مرکزی اروپا، رایگان و بدون کلید).
 * قیمت هر دارایی = ارزش یک واحد از آن ارز به دلار آمریکا.
 */
class FxSource {

    private val api = Http.retrofit("https://api.frankfurter.dev/", FxApi::class.java)

    private val currencies = listOf(
        "EUR" to "یورو",
        "GBP" to "پوند انگلستان",
        "CHF" to "فرانک سوئیس",
        "JPY" to "ین ژاپن",
        "AED" to "درهم امارات",
        "TRY" to "لیر ترکیه",
        "CNY" to "یوان چین"
    )

    suspend fun assets(): List<Asset> {
        val now = System.currentTimeMillis()
        val symbols = currencies.joinToString(",") { it.first }
        val latest = api.latest(base = "USD", symbols = symbols)
        val rates = latest.rates ?: return emptyList()
        return currencies.mapNotNull { (code, faName) ->
            val perUsd = rates[code] ?: return@mapNotNull null
            if (perUsd <= 0) return@mapNotNull null
            Asset(
                id = "fx:$code",
                symbol = code,
                name = faName,
                market = MarketKind.FX,
                baseCurrency = "USD",
                price = 1.0 / perUsd,
                changePct24h = null,
                updatedAt = now,
                isSimulated = false
            )
        }
    }

    suspend fun history(currency: String, days: Int = 90): List<PricePoint> {
        val end = LocalDate.now()
        val start = end.minusDays(days.toLong())
        val fmt = DateTimeFormatter.ISO_LOCAL_DATE
        val range = start.format(fmt) + ".." + end.format(fmt)
        val resp = api.history(range = range, base = currency, symbols = "USD")
        val rates = resp.rates ?: return emptyList()
        return rates.entries
            .sortedBy { it.key }
            .mapNotNull { (date, m) ->
                val v = m["USD"] ?: return@mapNotNull null
                val ts = try {
                    LocalDate.parse(date, fmt).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                } catch (e: Exception) {
                    0L
                }
                if (ts == 0L || !v.isFinite()) null else PricePoint(ts, v)
            }
    }
}
