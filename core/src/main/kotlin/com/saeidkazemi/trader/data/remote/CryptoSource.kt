package com.saeidkazemi.trader.data.remote

import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.data.model.PricePoint
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
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
        @Query("interval") interval: String?
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
        /** تعداد ارزهای برتر که تاریخچه و سیگنال می‌گیرند (بقیه فقط نمایشی‌اند). */
        const val HISTORY_LIMIT = 25

        /** استیبل‌کوین‌ها و توکن‌های بسته‌بندی‌شده: قیمتشان تکرار دارایی دیگر است و معامله‌شان سودی ندارد. */
        private val NON_TRADABLE = setOf(
            "USDT", "USDC", "DAI", "FDUSD", "USDE", "TUSD", "PYUSD", "USDS", "BUSD", "USD1", "USDD", "USDTB", "BSC-USD",
            "WBTC", "WETH", "STETH", "WSTETH", "WEETH", "CBBTC", "WBETH", "RETH", "METH", "LBTC", "SOLVBTC", "BUIDL"
        )
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
                isDisplayOnly = (rank != null && rank > HISTORY_LIMIT) ||
                    (c.symbol ?: "").uppercase() in NON_TRADABLE,
                rank = rank,
                nobitexSymbol = nobitexMap[id]
            )
        }
    }

    /**
     * تاریخچه روزانه قیمت (دلاری) با چند منبع پشت‌سرهم:
     * ۱) نوبیتکس (بازار تتری، عمومی، ۶۰ درخواست در دقیقه — از ایران هم در دسترس)
     * ۲) CryptoCompare  ۳) CoinGecko
     */
    suspend fun history(coinId: String, symbol: String, days: Int = 120): List<PricePoint> {
        val errors = mutableListOf<String>()
        for (src in listOf("nobitex", "cryptocompare", "coingecko")) {
            try {
                val h = when (src) {
                    "nobitex" -> nobitexHistory(symbol, days)
                    "cryptocompare" -> cryptoCompareHistory(symbol, days)
                    else -> coinGeckoHistory(coinId, minOf(days, 90))
                }
                if (h.size >= 40) return h
                errors.add("$src: ${h.size} points")
            } catch (e: Exception) {
                errors.add("$src: ${e.message}")
            }
        }
        throw IllegalStateException("no history for $symbol (" + errors.joinToString("; ") + ")")
    }

    private suspend fun getJson(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        Http.client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            body
        }
    }

    private suspend fun nobitexHistory(symbol: String, days: Int): List<PricePoint> {
        val sym = symbol.uppercase().filter { it.isLetterOrDigit() }
        if (sym.isEmpty() || sym == "USDT") return emptyList()
        val to = System.currentTimeMillis() / 1000
        val body = getJson(
            "https://apiv2.nobitex.ir/market/udf/history?symbol=${sym}USDT&resolution=D&to=$to&countback=$days"
        )
        val root = JsonParser.parseString(body).asJsonObject
        if (root.get("s")?.asString != "ok") return emptyList()
        val t = root.getAsJsonArray("t") ?: return emptyList()
        val c = root.getAsJsonArray("c") ?: return emptyList()
        return (0 until minOf(t.size(), c.size())).mapNotNull { i ->
            val price = c[i].asDouble
            if (price.isFinite() && price > 0) PricePoint(t[i].asLong * 1000, price) else null
        }.sortedBy { it.t }
    }

    private suspend fun cryptoCompareHistory(symbol: String, days: Int): List<PricePoint> {
        val sym = symbol.uppercase().filter { it.isLetterOrDigit() }
        if (sym.isEmpty()) return emptyList()
        val body = getJson("https://min-api.cryptocompare.com/data/v2/histoday?fsym=$sym&tsym=USD&limit=$days")
        val root = JsonParser.parseString(body).asJsonObject
        if (root.get("Response")?.asString != "Success") return emptyList()
        val arr = root.getAsJsonObject("Data")?.getAsJsonArray("Data") ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el.asJsonObject
            val close = o.get("close")?.asDouble ?: return@mapNotNull null
            val time = o.get("time")?.asLong ?: return@mapNotNull null
            if (close.isFinite() && close > 0) PricePoint(time * 1000, close) else null
        }
    }

    private suspend fun coinGeckoHistory(coinId: String, days: Int): List<PricePoint> {
        val chart = api.marketChart(id = coinId, days = days, interval = null)
        return chart.prices.orEmpty().mapNotNull { row ->
            if (row.size < 2) null else PricePoint(row[0].toLong(), row[1])
        }.filter { it.price.isFinite() && it.price > 0 }
    }
}
