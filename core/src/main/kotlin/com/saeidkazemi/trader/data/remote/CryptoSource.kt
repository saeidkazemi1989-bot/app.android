package com.saeidkazemi.trader.data.remote

import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.data.model.PricePoint
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

class CoinMarketDto(
    val id: String?,
    val symbol: String?,
    val name: String?,
    val current_price: Double?,
    val price_change_percentage_24h: Double?,
    val market_cap_rank: Int?
)

class MarketChartDto(val prices: List<List<Double>>?)

interface CryptoApi {
    @GET("api/v3/coins/markets")
    suspend fun markets(
        @Query("vs_currency") vsCurrency: String,
        @Query("order") order: String,
        @Query("per_page") perPage: Int,
        @Query("page") page: Int,
        @Query("price_change_percentage") priceChange: String
    ): List<CoinMarketDto>

    @GET("api/v3/coins/{id}/market_chart")
    suspend fun marketChart(
        @Path("id") id: String,
        @Query("days") days: Int,
        @Query("interval") interval: String
    ): MarketChartDto
}

/**
 * منبع داده ارزهای دیجیتال: CoinGecko (رایگان و بدون کلید).
 * برای ۱۲ ارز اول بازار، تاریخچه ۹۰ روزه گرفته می‌شود تا موتور تحلیل بتواند سیگنال بسازد؛
 * بقیه فقط نمایشی هستند (برای جلوگیری از محدودیت نرخ درخواست).
 */
class CryptoSource {

    private val api = Http.retrofit("https://api.coingecko.com/", CryptoApi::class.java)

    /** نماد ارز در نوبیتکس (بازار ریالی) برای ارزهایی که معامله واقعی دارند. */
    private val nobitexMap = mapOf(
        "bitcoin" to "btc",
        "ethereum" to "eth",
        "tether" to "usdt",
        "litecoin" to "ltc",
        "ripple" to "xrp",
        "dogecoin" to "doge",
        "tron" to "trx",
        "cardano" to "ada",
        "solana" to "sol",
        "binancecoin" to "bnb",
        "the-open-network" to "ton",
        "avalanche-2" to "avax",
        "polkadot" to "dot",
        "chainlink" to "link"
    )

    companion object {
        const val HISTORY_LIMIT = 12
    }

    suspend fun topAssets(limit: Int = 40): List<Asset> {
        val now = System.currentTimeMillis()
        val list = api.markets(
            vsCurrency = "usd",
            order = "market_cap_desc",
            perPage = limit,
            page = 1,
            priceChange = "24h"
        )
        return list.mapNotNull { c ->
            val id = c.id ?: return@mapNotNull null
            val price = c.current_price ?: return@mapNotNull null
            val rank = c.market_cap_rank
            Asset(
                id = id,
                symbol = (c.symbol ?: id).uppercase(),
                name = c.name ?: id,
                market = MarketKind.CRYPTO,
                baseCurrency = "USD",
                price = price,
                changePct24h = c.price_change_percentage_24h,
                updatedAt = now,
                isSimulated = false,
                isDisplayOnly = rank != null && rank > HISTORY_LIMIT,
                rank = rank,
                nobitexSymbol = nobitexMap[id]
            )
        }
    }

    suspend fun history(coinId: String, days: Int = 90): List<PricePoint> {
        val chart = api.marketChart(id = coinId, days = days, interval = "daily")
        return chart.prices.orEmpty().mapNotNull { row ->
            if (row.size < 2) null else PricePoint(row[0].toLong(), row[1])
        }.filter { it.price.isFinite() && it.price > 0 }
    }
}
