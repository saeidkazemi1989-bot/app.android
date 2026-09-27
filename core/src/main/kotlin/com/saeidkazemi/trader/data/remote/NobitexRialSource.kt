package com.saeidkazemi.trader.data.remote

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.data.model.PricePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * بازار «طلا و دلار» در نوبیتکس با قیمت ریالی/تومانی واقعی:
 *  - USDT/IRT (دلار تتر؛ عملاً نرخ دلار بازار آزاد)
 *  - PAXG/IRT (پکس‌گلد؛ هر توکن = یک اونس طلای واقعی نگهداری‌شده در لندن)
 *
 * قیمت‌ها از market/stats (به ریال) و تاریخچه روزانه از UDF (به تومان؛ ×۱۰ به ریال) گرفته می‌شود.
 * سود و زیان این دارایی‌ها مثل سهام بورس بر پایه ریال/تومان حساب می‌شود.
 */
class NobitexRialSource {

    data class Def(val id: String, val currency: String, val udf: String, val symbol: String, val name: String)

    data class Stat(
        val latest: Double,
        val bestBuy: Double?,
        val bestSell: Double?,
        val dayChange: Double?,
        val volumeDst: Double?,
        val closed: Boolean
    )

    @Volatile
    var lastError: String? = null
        private set

    suspend fun assets(): List<Asset> {
        val body = get(STATS_URL)
        val stats = parseStats(body)
        val now = System.currentTimeMillis()
        val out = DEFS.mapNotNull { d ->
            val s = stats[d.currency + "-rls"] ?: return@mapNotNull null
            if (s.closed || !(s.latest > 0)) return@mapNotNull null
            Asset(
                id = d.id,
                symbol = d.symbol,
                name = d.name,
                market = MarketKind.METAL,
                baseCurrency = "IRR",
                price = s.latest,
                changePct24h = s.dayChange,
                updatedAt = now,
                nobitexSymbol = d.currency,
                tradeValue = s.volumeDst,
                // bestBuy = بالاترین پیشنهاد خرید (bid)، bestSell = پایین‌ترین پیشنهاد فروش (ask)
                bidPrice = s.bestBuy?.takeIf { it > 0 },
                askPrice = s.bestSell?.takeIf { it > 0 }
            )
        }
        if (out.isEmpty()) throw IllegalStateException("Nobitex stats empty")
        lastError = null
        return out
    }

    /** تاریخچه روزانه به ریال (هم‌واحد با قیمت زنده). */
    suspend fun history(asset: Asset, days: Int = 150): List<PricePoint> {
        val d = DEFS.firstOrNull { it.id == asset.id } ?: return emptyList()
        val to = System.currentTimeMillis() / 1000
        val body = get("https://apiv2.nobitex.ir/market/udf/history?symbol=${d.udf}&resolution=D&to=$to&countback=$days")
        val raw = parseUdf(body)
        return normalizeScale(raw.map { it.copy(price = it.price * 10.0) }, asset.price)
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "TraderBot/MoameleYar-1.6")
            .header("Accept", "application/json")
            .build()
        try {
            Http.client.newCall(req).execute().use { resp ->
                val b = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("Nobitex HTTP ${resp.code}")
                b
            }
        } catch (e: Exception) {
            lastError = e.message ?: e.toString()
            throw e
        }
    }

    companion object {
        const val STATS_URL = "https://apiv2.nobitex.ir/market/stats?srcCurrency=usdt,paxg&dstCurrency=rls"

        val DEFS = listOf(
            Def("nbx:usdt", "usdt", "USDTIRT", "دلار (تتر)", "دلار آمریکا — تتر/تومان نوبیتکس (نرخ بازار آزاد)"),
            Def("nbx:paxg", "paxg", "PAXGIRT", "طلا (PAXG)", "طلای توکنی پکس‌گلد — هر واحد یک اونس طلا (نوبیتکس/تومان)")
        )

        private fun num(o: JsonObject, key: String): Double? {
            val e = o.get(key) ?: return null
            if (e.isJsonNull) return null
            return try {
                (if (e.isJsonPrimitive && e.asJsonPrimitive.isString) e.asString.toDoubleOrNull() else e.asDouble)
                    ?.takeIf { it.isFinite() }
            } catch (_: Exception) {
                null
            }
        }

        fun parseStats(body: String): Map<String, Stat> {
            val root = try {
                JsonParser.parseString(body).asJsonObject
            } catch (_: Exception) {
                return emptyMap()
            }
            val stats = root.getAsJsonObject("stats") ?: return emptyMap()
            val out = HashMap<String, Stat>()
            for ((k, v) in stats.entrySet()) {
                if (!v.isJsonObject) continue
                val o = v.asJsonObject
                val latest = num(o, "latest") ?: continue
                val closed = o.get("isClosed")?.let { if (it.isJsonNull) false else try { it.asBoolean } catch (_: Exception) { false } } ?: false
                out[k] = Stat(latest, num(o, "bestBuy"), num(o, "bestSell"), num(o, "dayChange"), num(o, "volumeDst"), closed)
            }
            return out
        }

        fun parseUdf(body: String): List<PricePoint> {
            val root = try {
                JsonParser.parseString(body).asJsonObject
            } catch (_: Exception) {
                return emptyList()
            }
            if (root.get("s")?.asString != "ok") return emptyList()
            val t = root.getAsJsonArray("t") ?: return emptyList()
            val c = root.getAsJsonArray("c") ?: return emptyList()
            val v = root.getAsJsonArray("v")
            return (0 until minOf(t.size(), c.size())).mapNotNull { i ->
                val price = try { c[i].asDouble } catch (_: Exception) { return@mapNotNull null }
                val vol = if (v != null && i < v.size()) (try { v[i].asDouble } catch (_: Exception) { 0.0 }) else 0.0
                if (price.isFinite() && price > 0) PricePoint(t[i].asLong * 1000, price, if (vol.isFinite()) vol else 0.0) else null
            }.sortedBy { it.t }
        }

        /**
         * محافظ واحد: اگر آخرین بسته تاریخچه با قیمت زنده حدود ۱۰ برابر اختلاف داشت (ریال/تومان)، اصلاح می‌شود.
         */
        fun normalizeScale(points: List<PricePoint>, current: Double): List<PricePoint> {
            if (points.isEmpty() || !(current > 0)) return points
            val last = points.last().price
            if (!(last > 0)) return points
            val r = current / last
            val f = when {
                r in 7.0..13.0 -> 10.0
                r in (1 / 13.0)..(1 / 7.0) -> 0.1
                else -> 1.0
            }
            return if (f == 1.0) points else points.map { it.copy(price = it.price * f) }
        }
    }
}
