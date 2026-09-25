package com.saeidkazemi.trader.data.remote

import com.google.gson.JsonElement
import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.data.model.PricePoint
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Request
import java.util.Random
import java.util.concurrent.TimeUnit

/**
 * منبع داده بورس تهران.
 *
 * تلاش می‌شود قیمت زنده نمادها از سامانه TSETMC گرفته شود؛ چون این درگاه‌ها رسمی نیستند و ممکن است
 * از خارج از ایران یا بدون فیلترشکن در دسترس نباشند، در صورت شکست، داده شبیه‌سازی‌شده (با برچسب مشخص)
 * تولید می‌شود تا اپ همیشه کار کند. برای داده رسمی و معامله واقعی باید به درگاه کارگزاری
 * (مثل ایزی‌تریدر مفید) متصل شوید.
 */
class IranStockSource {

    class StockDef(
        val tag: String,
        val symbol: String,
        val name: String,
        val basePriceIrr: Double,
        val isin: String?
    )

    private val defs = listOf(
        StockDef("folad", "فولاد", "فولاد مبارکه اصفهان", 9_200.0, "IRO1FOLZ0001"),
        StockDef("fameli", "فملی", "ملی صنایع مس ایران", 7_800.0, "IRO1FMLZ0001"),
        StockDef("khodro", "خودرو", "ایران خودرو", 3_900.0, "IRO1IKCO0001"),
        StockDef("webmelat", "وبملت", "بانک ملت", 7_200.0, "IRO1BMLT0001"),
        StockDef("shapna", "شپنا", "پالایش نفت اصفهان", 6_700.0, null),
        StockDef("shabandar", "شبندر", "پالایش نفت بندرعباس", 11_400.0, null),
        StockDef("websader", "وبصادر", "بانک صادرات ایران", 3_400.0, null),
        StockDef("shasta", "شستا", "سرمایه‌گذاری تأمین اجتماعی", 12_300.0, null),
        StockDef("kogol", "کگل", "گهر زمین", 18_900.0, null),
        StockDef("fars", "فارس", "هلدینگ صنایع پتروشیمی خلیج فارس", 15_600.0, null)
    )

    private val quickClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    suspend fun assets(): List<Asset> = coroutineScope {
        val now = System.currentTimeMillis()
        defs.mapIndexed { idx, def ->
            async {
                val live = if (def.isin != null) fetchLive(def.isin) else null
                if (live != null) {
                    Asset(
                        id = "ir:" + def.tag,
                        symbol = def.symbol,
                        name = def.name,
                        market = MarketKind.IR_STOCK,
                        baseCurrency = "IRR",
                        price = live,
                        changePct24h = null,
                        updatedAt = now,
                        isSimulated = false,
                        rank = idx + 1
                    )
                } else {
                    val (price, change) = simulateToday(def, now)
                    Asset(
                        id = "ir:" + def.tag,
                        symbol = def.symbol,
                        name = def.name,
                        market = MarketKind.IR_STOCK,
                        baseCurrency = "IRR",
                        price = price,
                        changePct24h = change,
                        updatedAt = now,
                        isSimulated = true,
                        rank = idx + 1
                    )
                }
            }
        }.awaitAll()
    }

    /** تاریخچه ۱۲۰ روزه برای تحلیل؛ در حالت شبیه‌سازی، یک گام تصادفی قطعی هم‌بسته به قیمت روز. */
    fun history(asset: Asset): List<PricePoint> {
        val dayIndex = (asset.updatedAt / 86_400_000L).toInt()
        val rng = Random(asset.id.hashCode().toLong() * 131L + dayIndex * 17L)
        val n = 120
        val raw = DoubleArray(n)
        raw[0] = asset.price
        for (i in 1 until n) {
            val drift = rng.nextGaussian() * 0.011 + 0.0009
            raw[i] = raw[i - 1] * (1.0 + drift)
        }
        val scale = asset.price / raw[n - 1]
        val now = asset.updatedAt
        return (0 until n).map { i ->
            PricePoint(now - (n - 1 - i).toLong() * 86_400_000L, raw[i] * scale)
        }
    }

    private fun simulateToday(def: StockDef, now: Long): Pair<Double, Double> {
        val dayIndex = (now / 86_400_000L).toInt()
        val rng = Random(def.symbol.hashCode().toLong() * 31L + dayIndex)
        val drift = (rng.nextDouble() - 0.48) * 0.035
        val price = def.basePriceIrr * (1.0 + drift)
        val change = drift * 100.0
        return Pair(price, change)
    }

    /** تلاش برای گرفتن قیمت زنده از درگاه غیررسمی؛ در صورت هر خطا مقدار تهی برمی‌گرداند. */
    private fun fetchLive(isin: String): Double? {
        return try {
            val req = Request.Builder()
                .url("https://cdn.tsetmc.com/api/Instrument/GetInstrumentShare/$isin")
                .header("User-Agent", "Mozilla/5.0 (Android)")
                .header("Referer", "https://www.tsetmc.com/")
                .build()
            val resp = quickClient.newCall(req).execute()
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val json = Http.gson.fromJson(body, JsonElement::class.java) ?: return null
            findPositiveNumber(json, setOf("pDrCotVal", "last", "lastPrice"))
        } catch (e: Exception) {
            null
        }
    }

    private fun findPositiveNumber(el: JsonElement, keys: Set<String>): Double? {
        if (el.isJsonObject) {
            val obj = el.asJsonObject
            for (k in keys) {
                val v = obj.get(k)
                if (v != null && v.isJsonPrimitive) {
                    val d = try {
                        v.asDouble
                    } catch (e: Exception) {
                        null
                    }
                    if (d != null && d.isFinite() && d > 0) return d
                }
            }
            for (e in obj.entrySet()) {
                val r = findPositiveNumber(e.value, keys)
                if (r != null) return r
            }
        } else if (el.isJsonArray) {
            for (e in el.asJsonArray) {
                val r = findPositiveNumber(e, keys)
                if (r != null) return r
            }
        }
        return null
    }
}
