package com.saeidkazemi.trader.data.remote

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.data.model.PricePoint
import com.saeidkazemi.trader.util.IranMarket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * منبع داده بورس تهران و فرابورس — **کل بازار**، نه چند نماد منتخب.
 *
 * ۱) «دیده‌بان بازار» TSETMC (یک درخواست) همه نمادها را با آخرین قیمت، ارزش معاملات، دامنه مجاز و
 *    بهترین سفارش خرید/فروش برمی‌گرداند. از میان آن، همه سهام بورس (IRO1) و فرابورس (IRO3) که
 *    نقدشوندگی کافی دارند وارد تحلیل می‌شوند؛ صف خرید و صف فروش هم تشخیص داده می‌شود.
 * ۲) تاریخچه روزانه هر نماد از TSETMC گرفته و بابت افزایش سرمایه و سود نقدی **تعدیل** می‌شود
 *    (وگرنه افت قیمت پس از مجمع، ریزش کاذب به نظر می‌رسید).
 *
 * اگر TSETMC در دسترس نباشد (مثلاً خارج از ایران)، ۱۰ نماد شاخص با داده شبیه‌سازی‌شده و برچسب مشخص
 * نمایش داده می‌شوند و روی آن‌ها معامله خودکار انجام نمی‌شود.
 */
class IranStockSource {

    /** یک ردیف دیده‌بان بازار (قیمت‌ها به ریال). */
    data class Quote(
        val insCode: String,
        val isin: String,
        val symbol: String,
        val name: String,
        val last: Double,
        val close: Double,
        val yesterday: Double,
        val maxAllowed: Double,
        val minAllowed: Double,
        val valueIrr: Double,
        val volume: Double,
        val trades: Double,
        val eps: Double?,
        val pe: Double?,
        val bidPrice: Double,
        val bidQty: Double,
        val askPrice: Double,
        val askQty: Double,
        /** کد گروه صنعت (مثلاً «۲۷» فلزات اساسی) برای مقایسه P/E با هم‌گروه‌ها. */
        val sector: String = ""
    ) {
        val isStock: Boolean get() = isin.startsWith("IRO1") || isin.startsWith("IRO3")

        /** صف خرید: بهترین تقاضا روی سقف مجاز و هیچ فروشنده‌ای نیست → عملاً نمی‌شود خرید. */
        val buyQueue: Boolean get() = maxAllowed > 0 && bidPrice >= maxAllowed && askQty <= 0

        /** صف فروش: بهترین عرضه روی کف مجاز و هیچ خریداری نیست → عملاً نمی‌شود فروخت. */
        val sellQueue: Boolean get() = minAllowed > 0 && askPrice > 0 && askPrice <= minAllowed && bidQty <= 0

        val price: Double get() = if (last > 0) last else close

        val changePct: Double? get() = if (yesterday > 0 && close > 0) (close / yesterday - 1) * 100 else null
    }

    /** یک روز از تاریخچه رسمی. */
    data class DailyRow(val dEven: Int, val close: Double, val yesterday: Double, val volume: Double = 0.0, val value: Double = 0.0)

    /**
     * معاملات حقیقی/حقوقی یک روز (حجم، ارزش ریالی و تعداد کد). منبع: ClientType در TSETMC.
     * «حقیقی» = اشخاص عادی، «حقوقی» = نهادها و صندوق‌ها.
     */
    data class ClientFlow(
        val date: Int,
        val buyIVol: Double,
        val sellIVol: Double,
        val buyNVol: Double,
        val sellNVol: Double,
        val buyICount: Double,
        val sellICount: Double,
        val buyIValue: Double = 0.0,
        val sellIValue: Double = 0.0
    ) {
        /** قدرت خریدار حقیقی = سرانه خرید هر کد حقیقی ÷ سرانه فروش هر کد حقیقی. */
        val buyerPower: Double?
            get() = if (buyICount > 0 && sellICount > 0 && buyIVol > 0 && sellIVol > 0)
                (buyIVol / buyICount) / (sellIVol / sellICount) else null

        /** سهم «ورود پول حقیقی» از کل حجم (مثبت: حقوقی به حقیقی فروخته؛ منفی: خروج پول حقیقی). */
        val realNetShare: Double?
            get() {
                val total = buyIVol + buyNVol
                return if (total > 0) (buyIVol - sellIVol) / total else null
            }

        /** خالص ورود پول حقیقی به ریال (اگر ارزش در داده نباشد، با قیمت تقریبی حساب می‌شود). */
        fun realNetValue(price: Double): Double =
            if (buyIValue > 0 || sellIValue > 0) buyIValue - sellIValue else (buyIVol - sellIVol) * price
    }

    /** آمار کل بازار سهام برای فیلتر «وضعیت بازار». */
    data class MarketStats(
        val advancers: Int,
        val decliners: Int,
        /** خالص ورود پول حقیقی به کل سهام (ریال). */
        val realNetIrr: Double?,
        val totalValueIrr: Double,
        /** میانه P/E هر گروه صنعت. */
        val sectorPe: Map<String, Double>
    ) {
        val breadth: Double? get() = if (advancers + decliners > 0) advancers.toDouble() / (advancers + decliners) else null
    }

    class ScanResult(
        val assets: List<Asset>,
        val live: Boolean,
        val scanned: Int,
        val stocks: Int,
        val liquid: Int,
        val buyQueues: Int,
        val sellQueues: Int
    )

    companion object {
        /** حداقل ارزش معاملات روزانه برای ورود به تحلیل: ۱۰ میلیارد ریال (۱ میلیارد تومان). */
        const val MIN_VALUE_IRR = 10_000_000_000.0

        private const val API = "https://cdn.tsetmc.com/api/"

        private val MARKET_WATCH_URL = API + "ClosingPrice/GetMarketWatch?market=0&industrialGroup=" +
            (0..8).joinToString("") { "&paperTypes%5B$it%5D=${it + 1}" } +
            "&showTraded=false&withBestLimits=true&hEven=0&RefID=0"

        /** حروف عربی TSETMC به فارسی (برای جستجو و هشتگ کدال ضروری است). */
        fun normalize(s: String): String = s
            .replace('ي', 'ی').replace('ى', 'ی').replace('ك', 'ک')
            .replace('\u200f', ' ').replace('\u200e', ' ')
            .trim().replace(Regex("\\s+"), " ")

        private fun JsonObject.num(key: String): Double {
            val v = get(key) ?: return 0.0
            if (v.isJsonNull || !v.isJsonPrimitive) return 0.0
            return try {
                val d = v.asString.replace(",", "").toDouble()
                if (d.isFinite()) d else 0.0
            } catch (e: Exception) {
                0.0
            }
        }

        private fun JsonObject.str(key: String): String {
            val v = get(key) ?: return ""
            return if (v.isJsonPrimitive) v.asString else ""
        }

        /** تجزیه پاسخ GetMarketWatch. */
        fun parseMarketWatch(json: String): List<Quote> {
            val root = JsonParser.parseString(json)
            val arr = when {
                root.isJsonObject && root.asJsonObject.has("marketwatch") -> root.asJsonObject.getAsJsonArray("marketwatch")
                root.isJsonArray -> root.asJsonArray
                else -> return emptyList()
            }
            return arr.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                val insCode = o.str("insCode").trim()
                val symbol = normalize(o.str("lva"))
                if (insCode.isEmpty() || symbol.isEmpty()) return@mapNotNull null
                val bl: JsonObject? = o.getAsJsonArray("blDs")
                    ?.firstOrNull { it.isJsonObject && it.asJsonObject.num("n").toInt() == 1 }
                    ?.asJsonObject
                    ?: o.getAsJsonArray("blDs")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                Quote(
                    insCode = insCode,
                    isin = o.str("insID").trim(),
                    symbol = symbol,
                    name = normalize(o.str("lvc")),
                    last = o.num("pdv"),
                    close = o.num("pcl"),
                    yesterday = o.num("py"),
                    maxAllowed = o.num("pMax"),
                    minAllowed = o.num("pMin"),
                    valueIrr = o.num("qtc"),
                    volume = o.num("qtj"),
                    trades = o.num("ztt"),
                    eps = o.num("eps").takeIf { it != 0.0 },
                    pe = o.num("pe").takeIf { it != 0.0 },
                    bidPrice = bl?.num("pmd") ?: 0.0,
                    bidQty = bl?.num("qmd") ?: 0.0,
                    askPrice = bl?.num("pmo") ?: 0.0,
                    askQty = bl?.num("qmo") ?: 0.0,
                    sector = o.str("csv").trim()
                )
            }
        }

        /** تجزیه پاسخ GetClosingPriceDailyList. */
        fun parseDaily(json: String): List<DailyRow> {
            val root = JsonParser.parseString(json)
            val arr = if (root.isJsonObject) root.asJsonObject.getAsJsonArray("closingPriceDaily") else null
            return arr?.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                val d = o.num("dEven").toInt()
                val close = o.num("pClosing")
                if (d <= 19000101 || close <= 0) null
                else DailyRow(d, close, o.num("priceYesterday"), o.num("qTotTran5J"), o.num("qTotCap"))
            }.orEmpty().sortedBy { it.dEven }.distinctBy { it.dEven }
        }

        /** تجزیه ClientType/GetClientTypeAll (حقیقی/حقوقی امروز همه نمادها)، کلید: insCode. */
        fun parseClientTypeAll(json: String, date: Int = 0): Map<String, ClientFlow> {
            val root = JsonParser.parseString(json)
            val arr = if (root.isJsonObject) root.asJsonObject.getAsJsonArray("clientTypeAllDto") else null
            val out = HashMap<String, ClientFlow>()
            arr?.forEach { el ->
                if (!el.isJsonObject) return@forEach
                val o = el.asJsonObject
                val code = o.str("insCode").trim()
                if (code.isEmpty()) return@forEach
                out[code] = ClientFlow(
                    date = date,
                    buyIVol = o.num("buy_I_Volume"),
                    sellIVol = o.num("sell_I_Volume"),
                    buyNVol = o.num("buy_N_Volume"),
                    sellNVol = o.num("sell_N_Volume"),
                    buyICount = o.num("buy_CountI"),
                    sellICount = o.num("sell_CountI")
                )
            }
            return out
        }

        /** تجزیه ClientType/GetClientTypeHistory (تاریخچه حقیقی/حقوقی یک نماد)، جدیدترین روز اول. */
        fun parseClientTypeHistory(json: String): List<ClientFlow> {
            val root = JsonParser.parseString(json)
            if (!root.isJsonObject) return emptyList()
            val el = root.asJsonObject.get("clientType") ?: return emptyList()
            val items = when {
                el.isJsonArray -> el.asJsonArray.toList()
                el.isJsonObject -> listOf(el)
                else -> emptyList()
            }
            return items.mapNotNull { e ->
                if (!e.isJsonObject) return@mapNotNull null
                val o = e.asJsonObject
                val d = o.num("recDate").toInt()
                if (d <= 19000101) return@mapNotNull null
                ClientFlow(
                    date = d,
                    buyIVol = o.num("buy_I_Volume"),
                    sellIVol = o.num("sell_I_Volume"),
                    buyNVol = o.num("buy_N_Volume"),
                    sellNVol = o.num("sell_N_Volume"),
                    buyICount = o.num("buy_I_Count"),
                    sellICount = o.num("sell_I_Count"),
                    buyIValue = o.num("buy_I_Value"),
                    sellIValue = o.num("sell_I_Value")
                )
            }.sortedByDescending { it.date }.distinctBy { it.date }
        }

        /** آمار کل بازار از دیده‌بان و حقیقی/حقوقی امروز. */
        fun marketStats(stocks: List<Quote>, flows: Map<String, ClientFlow>): MarketStats {
            var adv = 0
            var dec = 0
            var net = 0.0
            var withFlow = 0
            for (q in stocks) {
                val c = q.changePct ?: continue
                if (q.trades <= 0) continue
                if (c > 0.3) adv++ else if (c < -0.3) dec++
                val f = flows[q.insCode]
                if (f != null) {
                    net += f.realNetValue(q.price)
                    withFlow++
                }
            }
            val sectorPe = stocks
                .filter { (it.pe ?: 0.0) > 0 && (it.pe ?: 0.0) < 300 && it.sector.isNotEmpty() }
                .groupBy { it.sector }
                .filterValues { it.size >= 3 }
                .mapValues { (_, qs) ->
                    val v = qs.mapNotNull { it.pe }.sorted()
                    if (v.size % 2 == 1) v[v.size / 2] else (v[v.size / 2 - 1] + v[v.size / 2]) / 2
                }
            return MarketStats(adv, dec, if (withFlow > 0) net else null, stocks.sumOf { it.valueIrr }, sectorPe)
        }

        /**
         * تعدیل قیمت‌ها: در روز مجمع/افزایش سرمایه، «قیمت دیروز» اعلامی بورس با قیمت پایانی روز قبل فرق دارد.
         * نسبت این دو، ضریب تعدیل همه روزهای قبل است. خروجی: قیمت‌های پیوسته و قابل مقایسه.
         */
        fun adjust(rows: List<DailyRow>): List<Pair<Int, Double>> {
            if (rows.isEmpty()) return emptyList()
            val out = DoubleArray(rows.size)
            var factor = 1.0
            out[rows.size - 1] = rows.last().close
            for (i in rows.size - 1 downTo 1) {
                val y = rows[i].yesterday
                val prev = rows[i - 1].close
                if (y > 0 && prev > 0) {
                    val r = y / prev
                    if (kotlin.math.abs(r - 1) > 0.002 && r > 0.1 && r < 1.5) factor *= r
                }
                out[i - 1] = rows[i - 1].close * factor
            }
            return rows.indices.map { rows[it].dEven to out[it] }
        }

        /** آخرین رویداد تعدیل (روز، ضریب) در ردیف‌ها، اگر وجود داشته باشد. */
        fun lastAdjustment(rows: List<DailyRow>): Pair<Int, Double>? {
            for (i in rows.size - 1 downTo 1) {
                val y = rows[i].yesterday
                val prev = rows[i - 1].close
                if (y > 0 && prev > 0) {
                    val r = y / prev
                    if (kotlin.math.abs(r - 1) > 0.002 && r > 0.1 && r < 1.5) return rows[i].dEven to r
                }
            }
            return null
        }
    }

    // ---------- نمادهای پشتیبان برای حالت آفلاین/شبیه‌سازی ----------

    private class StockDef(val tag: String, val symbol: String, val name: String, val basePriceIrr: Double)

    private val fallbackDefs = listOf(
        StockDef("folad", "فولاد", "فولاد مبارکه اصفهان", 9_200.0),
        StockDef("fameli", "فملی", "ملی صنایع مس ایران", 7_800.0),
        StockDef("khodro", "خودرو", "ایران خودرو", 3_900.0),
        StockDef("webmelat", "وبملت", "بانک ملت", 7_200.0),
        StockDef("shapna", "شپنا", "پالایش نفت اصفهان", 6_700.0),
        StockDef("shabandar", "شبندر", "پالایش نفت بندرعباس", 11_400.0),
        StockDef("websader", "وبصادر", "بانک صادرات ایران", 3_400.0),
        StockDef("shasta", "شستا", "سرمایه‌گذاری تأمین اجتماعی", 12_300.0),
        StockDef("kogol", "کگل", "گهر زمین", 18_900.0),
        StockDef("fars", "فارس", "هلدینگ صنایع پتروشیمی خلیج فارس", 15_600.0)
    )

    private val client by lazy {
        Http.client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var quotes: Map<String, Quote> = emptyMap()

    @Volatile
    private var quotesTs = 0L

    /** آخرین ردیف‌های تاریخچه (خام) هر نماد برای تشخیص افزایش سرمایه. */
    private val dailyRows = ConcurrentHashMap<String, List<DailyRow>>()

    /** حقیقی/حقوقی امروز همه نمادها (کلید: شناسه دارایی). */
    @Volatile
    private var flows: Map<String, ClientFlow> = emptyMap()

    @Volatile
    private var flowsTs = 0L

    /** تاریخچه حقیقی/حقوقی ۲۰ روز اخیر هر نماد (جدیدترین اول). */
    private val flowHistory = ConcurrentHashMap<String, List<ClientFlow>>()

    @Volatile
    var stats: MarketStats? = null
        private set

    @Volatile
    var flowError: String? = null
        private set

    fun flowOf(assetId: String): ClientFlow? = flows[assetId]

    fun flowHistoryOf(assetId: String): List<ClientFlow> = flowHistory[assetId].orEmpty()

    /** حقیقی/حقوقی امروز همه نمادها با همان کش دیده‌بان بازار. */
    private suspend fun clientTypes(): Map<String, ClientFlow> {
        val now = System.currentTimeMillis()
        val ttl = if (IranMarket.isOpen(now)) 2 * 60_000L else 30 * 60_000L
        if (flows.isNotEmpty() && now - flowsTs < ttl) return flows
        val parsed = parseClientTypeAll(get(API + "ClientType/GetClientTypeAll"), IranMarket.todayInt())
        if (parsed.isEmpty()) throw IllegalStateException("حقیقی/حقوقی خالی بود")
        flows = parsed.mapKeys { "ir:" + it.key }
        flowsTs = now
        return flows
    }

    @Volatile
    var lastError: String? = null
        private set

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) MoameleYar/1.1")
            .header("Accept", "application/json")
            .header("Referer", "https://www.tsetmc.com/")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("TSETMC HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
    }

    /**
     * دیده‌بان بازار با کش: در ساعت کار بازار هر ۲ دقیقه، در غیر آن هر ۳۰ دقیقه به‌روز می‌شود
     * (پاسخ کامل حدود ۲٫۵ مگابایت است؛ با فشرده‌سازی خیلی کمتر).
     */
    private suspend fun marketWatch(): Map<String, Quote> {
        val now = System.currentTimeMillis()
        val ttl = if (IranMarket.isOpen(now)) 2 * 60_000L else 30 * 60_000L
        if (quotes.isNotEmpty() && now - quotesTs < ttl) return quotes
        val parsed = parseMarketWatch(get(MARKET_WATCH_URL))
        if (parsed.isEmpty()) throw IllegalStateException("دیده‌بان بازار خالی بود")
        quotes = parsed.associateBy { "ir:" + it.insCode }
        quotesTs = now
        return quotes
    }

    fun quote(assetId: String): Quote? = quotes[assetId]

    /**
     * همه سهام نقدشونده بورس و فرابورس.
     * @param mustInclude شناسه‌هایی که حتماً باید برگردند (مثل سهام داخل پرتفوی، حتی اگر کم‌معامله شده باشند).
     */
    suspend fun scan(mustInclude: Set<String>): ScanResult {
        val now = System.currentTimeMillis()
        val all = try {
            marketWatch().also { lastError = null }
        } catch (e: Exception) {
            lastError = e.message ?: e.toString()
            if (quotes.isNotEmpty()) quotes else return simulated(now)
        }
        val stocks = all.values.filter { it.isStock && it.price > 0 }
        try {
            clientTypes()
            flowError = null
        } catch (e: Exception) {
            flowError = e.message ?: e.toString()
        }
        stats = marketStats(stocks, flows.mapKeys { it.key.removePrefix("ir:") })
        val liquid = stocks.filter { it.valueIrr >= MIN_VALUE_IRR || ("ir:" + it.insCode) in mustInclude }
            .sortedByDescending { it.valueIrr }
        val assets = liquid.mapIndexed { idx, q ->
            Asset(
                id = "ir:" + q.insCode,
                symbol = q.symbol,
                name = q.name,
                market = MarketKind.IR_STOCK,
                baseCurrency = "IRR",
                price = q.price,
                changePct24h = q.changePct,
                updatedAt = now,
                isSimulated = false,
                rank = idx + 1,
                buyQueue = q.buyQueue,
                sellQueue = q.sellQueue,
                tradeValue = q.valueIrr,
                farabourse = q.isin.startsWith("IRO3"),
                bidPrice = q.bidPrice.takeIf { it > 0 },
                askPrice = q.askPrice.takeIf { it > 0 }
            )
        }
        return ScanResult(
            assets = assets,
            live = true,
            scanned = all.size,
            stocks = stocks.size,
            liquid = liquid.size,
            buyQueues = liquid.count { it.buyQueue },
            sellQueues = liquid.count { it.sellQueue }
        )
    }

    private fun simulated(now: Long): ScanResult {
        val assets = fallbackDefs.mapIndexed { idx, def ->
            val dayIndex = (now / 86_400_000L).toInt()
            val rng = Random(def.symbol.hashCode().toLong() * 31L + dayIndex)
            val drift = (rng.nextDouble() - 0.48) * 0.035
            Asset(
                id = "ir:" + def.tag,
                symbol = def.symbol,
                name = def.name,
                market = MarketKind.IR_STOCK,
                baseCurrency = "IRR",
                price = def.basePriceIrr * (1.0 + drift),
                changePct24h = drift * 100.0,
                updatedAt = now,
                isSimulated = true,
                rank = idx + 1
            )
        }
        return ScanResult(assets, live = false, scanned = 0, stocks = 0, liquid = 0, buyQueues = 0, sellQueues = 0)
    }

    /** تاریخچه ۱۲۰ روزه تعدیل‌شده؛ برای نمادهای شبیه‌سازی‌شده یک گام تصادفی قطعی. */
    suspend fun history(asset: Asset): List<PricePoint> {
        if (asset.isSimulated) return simulatedHistory(asset)
        val insCode = asset.id.removePrefix("ir:")
        val rows = parseDaily(get(API + "ClosingPrice/GetClosingPriceDailyList/$insCode/150"))
        if (rows.isEmpty()) return emptyList()
        dailyRows[asset.id] = rows.takeLast(10)
        // تاریخچه ورود/خروج پول حقیقی (۲۰ روز)؛ شکستش مانع تحلیل نمی‌شود.
        try {
            val fh = parseClientTypeHistory(get(API + "ClientType/GetClientTypeHistory/$insCode")).take(20)
            if (fh.isNotEmpty()) flowHistory[asset.id] = fh
        } catch (_: Exception) {
        }
        val adj = adjust(rows)
        val adjusted = adj.mapIndexed { i, (d, p) ->
            PricePoint(IranMarket.dayStartMs(d) + 12 * 3_600_000L, p, rows[i].volume)
        }.toMutableList()
        // اگر امروز معامله شده ولی هنوز در تاریخچه رسمی نیامده، قیمت زنده را به‌عنوان آخرین نقطه اضافه کن.
        val today = IranMarket.todayInt()
        val q = quotes[asset.id]
        val todayStarted = IranMarket.isOpen() ||
            (IranMarket.isTradingDay(java.time.LocalDate.now(IranMarket.ZONE)) &&
                java.time.LocalTime.now(IranMarket.ZONE).isAfter(IranMarket.CLOSE))
        if (q != null && q.price > 0 && q.trades > 0 && rows.last().dEven < today && todayStarted) {
            val last = rows.last().close
            // اگر امروز مجمع/افزایش سرمایه بوده، «قیمت دیروز» جدید با پایانی قبلی فرق دارد → تعدیل کل سری.
            val f = if (q.yesterday > 0 && last > 0) q.yesterday / last else 1.0
            if (kotlin.math.abs(f - 1) > 0.002 && f > 0.1 && f < 1.5) {
                for (i in adjusted.indices) adjusted[i] = adjusted[i].copy(price = adjusted[i].price * f)
            }
            adjusted.add(PricePoint(System.currentTimeMillis(), q.price, q.volume))
        }
        return adjusted
    }

    /**
     * آخرین افزایش سرمایه/تقسیم سود (روز yyyymmdd، ضریب قیمت) برای اصلاح موقعیت‌های باز.
     * ضریب ۰٫۵ یعنی قیمت نصف شده و تعداد سهم باید دو برابر شود.
     */
    fun adjustmentFor(assetId: String): Pair<Int, Double>? {
        val rows = dailyRows[assetId] ?: return null
        val q = quotes[assetId]
        val today = IranMarket.todayInt()
        if (q != null && rows.isNotEmpty() && rows.last().dEven < today && q.trades > 0 && q.yesterday > 0) {
            val f = q.yesterday / rows.last().close
            if (kotlin.math.abs(f - 1) > 0.002 && f > 0.1 && f < 1.5) return today to f
        }
        return lastAdjustment(rows)
    }

    private fun simulatedHistory(asset: Asset): List<PricePoint> {
        val dayIndex = (asset.updatedAt / 86_400_000L).toInt()
        val rng = Random(asset.id.hashCode().toLong() * 131L + dayIndex * 17L)
        val n = 120
        val raw = DoubleArray(n)
        raw[0] = asset.price
        for (i in 1 until n) raw[i] = raw[i - 1] * (1.0 + rng.nextGaussian() * 0.011 + 0.0009)
        val scale = asset.price / raw[n - 1]
        val now = asset.updatedAt
        return (0 until n).map { i -> PricePoint(now - (n - 1 - i).toLong() * 86_400_000L, raw[i] * scale) }
    }
}
