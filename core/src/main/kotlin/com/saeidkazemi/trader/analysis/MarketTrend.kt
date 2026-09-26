package com.saeidkazemi.trader.analysis

import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.data.model.PricePoint

/** روند کلی یک بازار (شاخص هم‌وزن دارایی‌ها + پهنای بازار + پیش‌بینی). */
data class MarketTrendReport(
    val market: MarketKind,
    val title: String,
    /** شاخص با پایه ۱۰۰ در ابتدای بازه. */
    val index: List<PricePoint>,
    val constituents: Int,
    val simulated: Boolean,
    val change1d: Double?,
    val change7d: Double?,
    val change30d: Double?,
    /** ۲- (نزولی قوی) تا ۲+ (صعودی قوی). */
    val trendScore: Int,
    val trendLabel: String,
    val aboveSma20: Boolean?,
    val aboveSma50: Boolean?,
    /** سهم دارایی‌هایی که بالای میانگین ۲۰ روزه خودشان هستند (۰ تا ۱). */
    val breadthAboveSma20: Double?,
    val advancers: Int,
    val decliners: Int,
    /** نوسان روزانه شاخص، درصد. */
    val volatilityPct: Double?,
    val forecast: Forecast.Result?,
    /** اطلاعات مخصوص هر بازار (ترس و طمع، ورود پول حقیقی، قدرت دلار، …). */
    val facts: List<String>,
    /** بهترین و بدترین‌های ۷ روز اخیر: (نماد، درصد تغییر). */
    val leaders: List<Pair<String, Double>>,
    val laggards: List<Pair<String, Double>>
)

object MarketTrend {

    private const val DAY = 86_400_000L

    /**
     * شاخص هم‌وزن زنجیره‌ای: هر روز میانگین بازده روزانه دارایی‌هایی که در آن روز قیمت دارند
     * (بازده هر دارایی به ±۵۰٪ محدود می‌شود تا داده خراب شاخص را به هم نریزد).
     * روزهایی که کمتر از ۱۵٪ دارایی‌ها قیمت دارند (مثل تعطیلات) رد می‌شوند.
     */
    fun indexOf(histories: List<List<PricePoint>>, days: Int = 120): List<PricePoint> {
        val series = histories.map { h ->
            val m = HashMap<Long, Double>()
            for (p in h) if (p.price > 0 && p.price.isFinite()) m[Math.floorDiv(p.t, DAY)] = p.price
            m
        }.filter { it.isNotEmpty() }
        if (series.isEmpty()) return emptyList()
        val allDays = series.flatMap { it.keys }.toSortedSet().toList().takeLast(days)
        val minContrib = maxOf(1, (series.size * 0.15).toInt())
        val last = HashMap<Int, Double>()
        // قیمت مبنا: آخرین قیمت هر دارایی قبل از شروع بازه
        val first = allDays.first()
        series.forEachIndexed { i, s ->
            s.keys.filter { it < first }.maxOrNull()?.let { k -> last[i] = s[k]!! }
        }
        var index = 100.0
        val out = ArrayList<PricePoint>()
        for (d in allDays) {
            val rets = ArrayList<Double>()
            val seen = ArrayList<Pair<Int, Double>>()
            series.forEachIndexed { i, s ->
                val p = s[d] ?: return@forEachIndexed
                seen.add(i to p)
                val prev = last[i]
                if (prev != null && prev > 0) rets.add((p / prev - 1).coerceIn(-0.5, 0.5))
            }
            if (out.isEmpty()) {
                // نقطه شروع
                seen.forEach { (i, p) -> last[i] = p }
                if (rets.isNotEmpty()) index *= 1 + rets.average()
                out.add(PricePoint(d * DAY + DAY / 2, index))
                continue
            }
            if (rets.size < minContrib) {
                // دارایی‌هایی که قیمت مبنا نداشتند وارد می‌شوند، ولی روز کم‌داده امتیاز نمی‌گیرد
                seen.forEach { (i, p) -> if (last[i] == null) last[i] = p }
                continue
            }
            seen.forEach { (i, p) -> last[i] = p }
            index *= 1 + rets.average()
            out.add(PricePoint(d * DAY + DAY / 2, index))
        }
        return out
    }

    fun changeOver(points: List<PricePoint>, days: Int): Double? {
        if (points.size < 2) return null
        val lastP = points.last()
        val target = lastP.t - days * DAY
        val base = points.lastOrNull { it.t <= target + DAY / 4 } ?: return null
        if (base === lastP || base.price <= 0) return null
        return (lastP.price / base.price - 1) * 100
    }

    private fun sma(v: List<Double>, n: Int): Double? = if (v.size >= n) v.takeLast(n).average() else null

    fun build(
        market: MarketKind,
        assets: List<Asset>,
        histories: Map<String, List<PricePoint>>,
        facts: List<String> = emptyList(),
        now: Long = System.currentTimeMillis()
    ): MarketTrendReport? {
        val used = assets.filter { (histories[it.id]?.size ?: 0) >= 20 }
        if (used.size < 1) return null
        val index = indexOf(used.map { histories[it.id]!! })
        if (index.size < 10) return null
        val closes = index.map { it.price }
        val lastV = closes.last()
        val s20 = sma(closes, 20)
        val s50 = sma(closes, 50)
        val c1 = changeOver(index, 1)
        val c7 = changeOver(index, 7)
        val c30 = changeOver(index, 30)

        var score = 0
        if (s20 != null) score += if (lastV > s20) 1 else -1
        if (s20 != null && s50 != null) score += if (s20 > s50) 1 else -1
        else if (c7 != null) score += if (c7 > 0) 1 else -1
        val label = when {
            score >= 2 && (c30 ?: 0.0) > 10 -> "صعودی قوی"
            score >= 2 -> "صعودی"
            score <= -2 && (c30 ?: 0.0) < -10 -> "نزولی قوی"
            score <= -2 -> "نزولی"
            else -> "خنثی / نوسانی"
        }
        val finalScore = when (label) {
            "صعودی قوی" -> 2
            "صعودی" -> 1
            "نزولی قوی" -> -2
            "نزولی" -> -1
            else -> 0
        }

        // پهنای بازار
        var above = 0
        var counted = 0
        val weekly = ArrayList<Pair<String, Double>>()
        for (a in used) {
            val h = histories[a.id]!!.filter { it.price > 0 }.sortedBy { it.t }
            val pc = h.map { it.price }
            sma(pc, 20)?.let { m ->
                counted++
                if (pc.last() > m) above++
            }
            changeOver(h, 7)?.let { weekly.add(a.symbol to it) }
        }
        val rets = closes.zipWithNext { a, b -> if (a > 0) b / a - 1 else 0.0 }.takeLast(30)
        val vol = if (rets.size >= 5) {
            val m = rets.average()
            Math.sqrt(rets.sumOf { (it - m) * (it - m) } / (rets.size - 1)) * 100
        } else null
        val sorted = weekly.sortedByDescending { it.second }
        val title = when (market) {
            MarketKind.CRYPTO -> "شاخص هم‌وزن " + used.size + " ارز دیجیتال"
            MarketKind.IR_STOCK -> "شاخص هم‌وزن " + used.size + " سهم نقدشونده بورس و فرابورس"
            MarketKind.FX -> "میانگین " + used.size + " ارز خارجی در برابر دلار"
            MarketKind.METAL -> "فلزات"
        }
        return MarketTrendReport(
            market = market,
            title = title,
            index = index,
            constituents = used.size,
            simulated = used.any { it.isSimulated },
            change1d = c1,
            change7d = c7,
            change30d = c30,
            trendScore = finalScore,
            trendLabel = label,
            aboveSma20 = s20?.let { lastV > it },
            aboveSma50 = s50?.let { lastV > it },
            breadthAboveSma20 = if (counted > 0) above.toDouble() / counted else null,
            advancers = assets.count { (it.changePct24h ?: 0.0) > 0 },
            decliners = assets.count { (it.changePct24h ?: 0.0) < 0 },
            volatilityPct = vol,
            forecast = Forecast.build(index, lastV, now, simulated = used.any { it.isSimulated }),
            facts = facts,
            leaders = sorted.take(3),
            laggards = sorted.takeLast(3).reversed().filter { it !in sorted.take(3) }
        )
    }
}
