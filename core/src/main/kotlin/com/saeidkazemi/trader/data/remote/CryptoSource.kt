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

        const val GECKO_BACKOFF_MS = 20 * 60_000L

        /** شناسه CoinGecko هر نماد که قبلاً دیده شده (تا شناسه دارایی‌ها با تغییر منبع عوض نشود). */
        private val learnedIds = java.util.concurrent.ConcurrentHashMap<String, String>()

        /**
         * ارزهای جایگزین از نوبیتکس (نماد نوبیتکس ← شناسه CoinGecko، نام، رتبه تقریبی بازار).
         * شناسه‌ها همان شناسه‌های CoinGecko هستند تا خریدهای باز با تغییر منبع قیمتشان را گم نکنند.
         */
        val FALLBACK: List<Pair<String, Triple<String, String, Int>>> = listOf(
            "btc" to Triple("bitcoin", "Bitcoin", 1),
            "eth" to Triple("ethereum", "Ethereum", 2),
            "xrp" to Triple("ripple", "XRP", 4),
            "bnb" to Triple("binancecoin", "BNB", 5),
            "sol" to Triple("solana", "Solana", 6),
            "doge" to Triple("dogecoin", "Dogecoin", 8),
            "trx" to Triple("tron", "TRON", 9),
            "ada" to Triple("cardano", "Cardano", 10),
            "link" to Triple("chainlink", "Chainlink", 12),
            "bch" to Triple("bitcoin-cash", "Bitcoin Cash", 13),
            "xlm" to Triple("stellar", "Stellar", 14),
            "sui" to Triple("sui", "Sui", 15),
            "avax" to Triple("avalanche-2", "Avalanche", 16),
            "hbar" to Triple("hedera-hashgraph", "Hedera", 17),
            "ltc" to Triple("litecoin", "Litecoin", 19),
            "ton" to Triple("the-open-network", "Toncoin", 20),
            "xmr" to Triple("monero", "Monero", 22),
            "dot" to Triple("polkadot", "Polkadot", 24),
            "uni" to Triple("uniswap", "Uniswap", 25),
            "near" to Triple("near", "NEAR Protocol", 26),
            "aave" to Triple("aave", "Aave", 27),
            "zec" to Triple("zcash", "Zcash", 28),
            "etc" to Triple("ethereum-classic", "Ethereum Classic", 30),
            "apt" to Triple("aptos", "Aptos", 35),
            "atom" to Triple("cosmos", "Cosmos Hub", 40),
            "fil" to Triple("filecoin", "Filecoin", 42),
            "arb" to Triple("arbitrum", "Arbitrum", 45),
            "algo" to Triple("algorand", "Algorand", 48),
            "op" to Triple("optimism", "Optimism", 55),
            "pol" to Triple("polygon-ecosystem-token", "POL (ex-MATIC)", 58),
            "dash" to Triple("dash", "Dash", 90)
        )

        /** استیبل‌کوین‌ها و توکن‌های بسته‌بندی‌شده: قیمتشان تکرار دارایی دیگر است و معامله‌شان سودی ندارد. */
        private val NON_TRADABLE = setOf(
            "USDT", "USDC", "DAI", "FDUSD", "USDE", "TUSD", "PYUSD", "USDS", "BUSD", "USD1", "USDD", "USDTB", "BSC-USD",
            "WBTC", "WETH", "STETH", "WSTETH", "WEETH", "CBBTC", "WBETH", "RETH", "METH", "LBTC", "SOLVBTC", "BUIDL"
        )
    }

    /** آخرین منبع فهرست ارزها: «CoinGecko» یا «نوبیتکس». */
    @Volatile var lastListSource: String = ""
        private set

    @Volatile private var geckoFailAt = 0L
    @Volatile private var statsCache: Pair<Long, Map<String, com.google.gson.JsonObject>>? = null

    /**
     * فهرست ارزها: اول CoinGecko؛ اگر در دسترس نبود (از ایران گاهی مسدود است)، از آمار بازارهای تتری نوبیتکس
     * (از ایران در دسترس). بعد از هر خطای CoinGecko، ۲۰ دقیقه مستقیم از نوبیتکس خوانده می‌شود.
     */
    suspend fun topAssets(limit: Int = 40): List<Asset> {
        val now = System.currentTimeMillis()
        var geckoError: Exception? = null
        if (now - geckoFailAt > GECKO_BACKOFF_MS) {
            try {
                val g = geckoTopAssets(limit)
                if (g.isNotEmpty()) {
                    lastListSource = "CoinGecko"
                    return g
                }
            } catch (e: Exception) {
                geckoError = e
            }
            geckoFailAt = now
        }
        val n = try { nobitexTopAssets() } catch (e: Exception) { throw geckoError ?: e }
        if (n.isEmpty()) throw geckoError ?: IllegalStateException("no crypto data")
        lastListSource = "نوبیتکس"
        return n
    }

    /** آمار همه بازارهای تتری نوبیتکس (کش ۵۰ ثانیه): کلید = نماد کوچک (مثل btc). */
    suspend fun nobitexUsdtStats(): Map<String, com.google.gson.JsonObject> {
        statsCache?.let { (t, m) -> if (System.currentTimeMillis() - t < 50_000) return m }
        val body = getJson("https://apiv2.nobitex.ir/market/stats?dstCurrency=usdt")
        val root = JsonParser.parseString(body).asJsonObject
        if (root.get("status")?.asString != "ok") throw IllegalStateException("nobitex stats: " + (root.get("message")?.asString ?: "failed"))
        val stats = root.getAsJsonObject("stats") ?: throw IllegalStateException("nobitex stats empty")
        val out = HashMap<String, com.google.gson.JsonObject>()
        for ((k, v) in stats.entrySet()) {
            if (!k.endsWith("-usdt") || !v.isJsonObject) continue
            out[k.removeSuffix("-usdt")] = v.asJsonObject
        }
        statsCache = System.currentTimeMillis() to out
        return out
    }

    /** قیمت لحظه‌ای یک ارز در بازار تتری نوبیتکس. */
    suspend fun nobitexUsdtPrice(sym: String): Double? {
        val o = nobitexUsdtStats()[sym.lowercase()] ?: return null
        return num(o, "latest")?.takeIf { it > 0 } ?: num(o, "mark")
    }

    private fun num(o: com.google.gson.JsonObject, k: String): Double? = try {
        o.get(k)?.takeIf { !it.isJsonNull }?.asString?.toDoubleOrNull()?.takeIf { it.isFinite() }
    } catch (_: Exception) {
        null
    }

    /** قیمت یک ارز مشخص (مثلاً یک خرید باز) از بازار تتری نوبیتکس؛ شناسه دارایی حفظ می‌شود. */
    suspend fun nobitexAssetFor(id: String, symbol: String, name: String): Asset? {
        val sym = symbol.lowercase().filter { it.isLetterOrDigit() || it == '_' }
        val o = nobitexUsdtStats()[sym] ?: return null
        if (o.get("isClosed")?.asBoolean == true) return null
        val price = num(o, "latest")?.takeIf { it > 0 } ?: return null
        return Asset(
            id = id, symbol = symbol.uppercase(), name = name, market = MarketKind.CRYPTO, baseCurrency = "USD",
            price = price, changePct24h = num(o, "dayChange"), updatedAt = System.currentTimeMillis(),
            isSimulated = false, isDisplayOnly = false, rank = null, nobitexSymbol = nobitexMap[id] ?: sym,
            bidPrice = num(o, "bestBuy")?.takeIf { it > 0 }, askPrice = num(o, "bestSell")?.takeIf { it > 0 }
        )
    }

    private suspend fun nobitexTopAssets(): List<Asset> {
        val now = System.currentTimeMillis()
        val stats = nobitexUsdtStats()
        val out = ArrayList<Asset>()
        for ((sym, info) in FALLBACK) {
            val o = stats[sym] ?: continue
            if (o.get("isClosed")?.asBoolean == true) continue
            val price = num(o, "latest")?.takeIf { it > 0 } ?: continue
            val id = learnedIds[sym.uppercase()] ?: info.first
            out.add(
                Asset(
                    id = id,
                    symbol = sym.uppercase(),
                    name = info.second,
                    market = MarketKind.CRYPTO,
                    baseCurrency = "USD",
                    price = price,
                    changePct24h = num(o, "dayChange"),
                    updatedAt = now,
                    isSimulated = false,
                    isDisplayOnly = info.third > HISTORY_LIMIT,
                    rank = info.third,
                    nobitexSymbol = nobitexMap[id] ?: sym,
                    bidPrice = num(o, "bestBuy")?.takeIf { it > 0 },
                    askPrice = num(o, "bestSell")?.takeIf { it > 0 }
                )
            )
        }
        return out.sortedBy { it.rank ?: Int.MAX_VALUE }
    }

    private suspend fun geckoTopAssets(limit: Int): List<Asset> {
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
            c.symbol?.let { learnedIds[it.uppercase()] = id }
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

    /** تاریخچه طولانی (برای بک‌تست) فقط از نوبیتکس؛ تا حدود ۲ سال. */
    suspend fun longHistory(symbol: String, days: Int): List<PricePoint> = nobitexHistory(symbol, days)

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
        val v = root.getAsJsonArray("v")
        return (0 until minOf(t.size(), c.size())).mapNotNull { i ->
            val price = c[i].asDouble
            val vol = if (v != null && i < v.size()) (try { v[i].asDouble } catch (_: Exception) { 0.0 }) else 0.0
            if (price.isFinite() && price > 0) PricePoint(t[i].asLong * 1000, price, if (vol.isFinite()) vol else 0.0) else null
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
            val vol = o.get("volumefrom")?.asDouble ?: 0.0
            if (close.isFinite() && close > 0) PricePoint(time * 1000, close, vol) else null
        }
    }

    private suspend fun coinGeckoHistory(coinId: String, days: Int): List<PricePoint> {
        val chart = api.marketChart(id = coinId, days = days, interval = null)
        return chart.prices.orEmpty().mapNotNull { row ->
            if (row.size < 2) null else PricePoint(row[0].toLong(), row[1])
        }.filter { it.price.isFinite() && it.price > 0 }
    }
}
