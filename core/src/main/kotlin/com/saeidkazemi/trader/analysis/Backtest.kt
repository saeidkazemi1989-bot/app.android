package com.saeidkazemi.trader.analysis

import com.saeidkazemi.trader.data.model.AppSettings
import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.data.model.PricePoint
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * بک‌تست و بهینه‌سازی «ریسک به ریوارد».
 *
 * روی تاریخچه روزانه واقعی (تا حدود ۲ سال) همان امتیازدهی تکنیکال ربات را روز به روز اجرا می‌کند
 * (فقط با داده‌ای که آن روز در دسترس بوده) و خرید/فروش را با کارمزد و اسپرد واقعی شبیه‌سازی می‌کند.
 * سپس ترکیب‌های مختلف «آستانه خرید، حد سود، حد ضرر، حد ضرر متحرک و مهلت نگهداری» را امتحان می‌کند.
 *
 * برای جلوگیری از «بیش‌برازش» (پارامتری که فقط روی گذشته خوب است) داده به دو بخش تقسیم می‌شود:
 * ۷۰٪ اول برای انتخاب پارامتر (درون نمونه) و ۳۰٪ آخر فقط برای آزمون (خارج از نمونه). پارامتر فقط وقتی
 * روی معاملات واقعی اعمال می‌شود که در بخش آزمون هم سودده باشد.
 *
 * محدودیت: اخبار و تحلیل تخصصی (حقیقی/حقوقی، ترس و طمع…) تاریخچه ندارند و در بک‌تست حساب نمی‌شوند؛
 * قیمت‌ها پایانی روزانه‌اند (حرکت داخل روز دیده نمی‌شود).
 */
object Backtest {

    const val TARGET_WIN_RATE = 60.0
    const val IN_SAMPLE_FRACTION = 0.7

    /** پارامترهای یک استراتژی خرید/فروش. */
    data class Params(
        val threshold: Int,
        val tpPct: Double,
        val stopMult: Double,
        val minStopPct: Double,
        val maxStopPct: Double,
        val trailPct: Double,
        /** مهلت نگهداری به روز (۰ = بدون مهلت). */
        val maxHoldDays: Int
    ) {
        fun stopFor(volPct: Double?): Double {
            if (volPct == null || !volPct.isFinite() || volPct <= 0) return (minStopPct + maxStopPct) / 2
            return (volPct / 100.0 * stopMult).coerceIn(minStopPct, maxStopPct)
        }

        fun label(): String =
            "آستانه " + threshold + " • حد سود " + trim(tpPct * 100) + "٪ • حد ضرر " + trim(minStopPct * 100) + "–" + trim(maxStopPct * 100) +
                "٪ (" + trim(stopMult) + "× نوسان)" + (if (trailPct > 0) " • متحرک " + trim(trailPct * 100) + "٪" else "") +
                (if (maxHoldDays > 0) " • حداکثر " + maxHoldDays + " روز" else "")
    }

    /** هزینه واقعی یک طرف معامله (کسر). */
    data class Costs(val buyFee: Double, val sellFee: Double, val halfSpread: Double)

    /** سری قیمت روزانه یک دارایی با امتیاز و نوسان پیش‌محاسبه‌شده. */
    class Series(
        val assetId: String,
        val symbol: String,
        val t: LongArray,
        val close: DoubleArray,
        val score: IntArray,
        val vol: DoubleArray,
        val costs: Costs
    )

    data class Trade(
        val assetId: String,
        val symbol: String,
        val entryT: Long,
        val exitT: Long,
        val netPct: Double,
        val reason: String,
        val days: Int,
        /** ریسک به ریوارد برنامه‌ریزی‌شده (حد سود ÷ حد ضرر). */
        val plannedRR: Double
    )

    data class Stats(
        val trades: Int = 0,
        val wins: Int = 0,
        val winRate: Double = 0.0,
        val avgWinPct: Double = 0.0,
        val avgLossPct: Double = 0.0,
        /** ریسک به ریوارد واقعی = میانگین سود ÷ میانگین زیان. */
        val rr: Double = 0.0,
        val plannedRR: Double = 0.0,
        val expectancyPct: Double = 0.0,
        val profitFactor: Double = 0.0,
        val sumPct: Double = 0.0,
        val maxDrawdownPct: Double = 0.0,
        val avgDays: Double = 0.0
    ) {
        /** حداقل نرخ برد لازم برای سربه‌سر با این ریسک به ریوارد. */
        val breakEvenWinRate: Double get() = if (rr > 0) 100.0 / (1 + rr) else 100.0
    }

    data class MarketResult(
        val market: MarketKind,
        val assets: Int,
        val days: Int,
        val fromT: Long,
        val toT: Long,
        val current: Params,
        val currentIn: Stats,
        val currentOut: Stats,
        val best: Params?,
        val bestIn: Stats?,
        val bestOut: Stats?,
        val reachedTarget: Boolean,
        /** پارامتر جدید روی معاملات واقعی اعمال شد (در آزمون خارج از نمونه هم سودده بود). */
        val applied: Boolean,
        val verdict: String,
        val tested: Int,
        val exitMix: Map<String, Int> = emptyMap()
    )

    data class Report(
        val createdAt: Long = 0L,
        val results: List<MarketResult> = emptyList(),
        val notes: List<String> = emptyList()
    ) {
        fun appliedFor(m: MarketKind): Params? = results.firstOrNull { it.market == m && it.applied }?.best
    }

    // ---------------------------------------------------------------------------------------------

    private fun trim(v: Double): String {
        val r = Math.round(v * 10) / 10.0
        return if (r == Math.floor(r)) r.toLong().toString() else r.toString()
    }

    /** پیش‌محاسبه امتیاز روزانه (فقط با داده تا همان روز؛ پنجره ۱۵۰ روزه مثل ربات زنده). */
    fun prepare(asset: Asset, history: List<PricePoint>, settings: AppSettings, costs: Costs, window: Int = 150): Series? {
        val h = history.filter { it.price.isFinite() && it.price > 0 }.sortedBy { it.t }
        if (h.size < StrategyEngine.MIN_HISTORY + 20) return null
        val engine = StrategyEngine()
        val plain = settings.copy(newsEnabled = false, proAnalysis = false)
        val n = h.size
        val score = IntArray(n) { -1 }
        val vol = DoubleArray(n) { Double.NaN }
        for (i in StrategyEngine.MIN_HISTORY - 1 until n) {
            val from = maxOf(0, i + 1 - window)
            val sig = engine.analyze(asset, h.subList(from, i + 1), plain, null, null, 101) ?: continue
            score[i] = sig.technicalScore
            vol[i] = sig.metrics.volatility ?: Double.NaN
        }
        return Series(
            asset.id, asset.symbol,
            LongArray(n) { h[it].t }, DoubleArray(n) { h[it].price }, score, vol, costs
        )
    }

    /** شبیه‌سازی معاملات هر دارایی (مستقل از هم) با پارامترهای داده‌شده. */
    fun simulate(series: List<Series>, p: Params, settings: AppSettings): List<Trade> {
        val out = ArrayList<Trade>()
        val lockTrig = if (settings.profitLock) settings.profitLockTriggerPct else 0.0
        val lockKeep = if (settings.profitLock) minOf(settings.profitLockKeepPct, settings.profitLockTriggerPct) else 0.0
        for (s in series) {
            val c = s.costs
            var i = 0
            val n = s.close.size
            while (i < n - 1) {
                val sc = s.score[i]
                if (sc < p.threshold) { i++; continue }
                // خرید در پایانی همان روز به قیمت فروشنده (نصف اسپرد) + کارمزد
                val entry = s.close[i]
                val stopPct = p.stopFor(s.vol[i].takeIf { it.isFinite() })
                var stop = entry * (1 - stopPct)
                val tp = entry * (1 + p.tpPct)
                var peak = entry
                var exitIdx = -1
                var reason = ""
                var j = i + 1
                while (j < n) {
                    val px = s.close[j]
                    if (px > peak) peak = px
                    if (p.trailPct > 0) stop = maxOf(stop, peak * (1 - p.trailPct))
                    if (lockTrig > 0) {
                        val sf = c.sellFee + c.halfSpread
                        val bf = c.buyFee + c.halfSpread
                        RiskManager.ProfitLock.stopFor(entry, peak, bf, sf, lockTrig, lockKeep)?.let { stop = maxOf(stop, it) }
                    }
                    reason = when {
                        px <= stop -> if (stop > entry) "حد ضرر متحرک/قفل سود" else "حد ضرر"
                        px >= tp -> "حد سود"
                        s.score[j] in 0..settings.sellThreshold -> "ضعیف شدن سیگنال"
                        p.maxHoldDays > 0 && (s.t[j] - s.t[i]) >= p.maxHoldDays * 86_400_000L -> "پایان مهلت نگهداری"
                        else -> ""
                    }
                    if (reason.isNotEmpty()) { exitIdx = j; break }
                    j++
                }
                if (exitIdx < 0) break // معامله باز در انتهای داده حساب نمی‌شود
                val exit = s.close[exitIdx]
                val net = exit * (1 - c.halfSpread) * (1 - c.sellFee) * (1 - c.buyFee) / (entry * (1 + c.halfSpread)) - 1
                out.add(
                    Trade(
                        s.assetId, s.symbol, s.t[i], s.t[exitIdx], net * 100, reason,
                        ((s.t[exitIdx] - s.t[i]) / 86_400_000L).toInt(), p.tpPct / stopPct
                    )
                )
                i = exitIdx + 1
            }
        }
        return out
    }

    fun stats(trades: List<Trade>): Stats {
        if (trades.isEmpty()) return Stats()
        val wins = trades.filter { it.netPct > 0 }
        val losses = trades.filter { it.netPct <= 0 }
        val avgWin = if (wins.isNotEmpty()) wins.sumOf { it.netPct } / wins.size else 0.0
        val avgLoss = if (losses.isNotEmpty()) abs(losses.sumOf { it.netPct } / losses.size) else 0.0
        val gw = wins.sumOf { it.netPct }
        val gl = abs(losses.sumOf { it.netPct })
        // افت سرمایه روی منحنی سود تجمعی (به ترتیب زمان خروج)
        var eq = 0.0
        var peak = 0.0
        var dd = 0.0
        for (t in trades.sortedBy { it.exitT }) {
            eq += t.netPct
            peak = maxOf(peak, eq)
            dd = maxOf(dd, peak - eq)
        }
        return Stats(
            trades = trades.size,
            wins = wins.size,
            winRate = wins.size * 100.0 / trades.size,
            avgWinPct = avgWin,
            avgLossPct = avgLoss,
            rr = if (avgLoss > 0) avgWin / avgLoss else if (avgWin > 0) 99.0 else 0.0,
            plannedRR = trades.sumOf { it.plannedRR } / trades.size,
            expectancyPct = trades.sumOf { it.netPct } / trades.size,
            profitFactor = if (gl > 0) gw / gl else if (gw > 0) 99.0 else 0.0,
            sumPct = trades.sumOf { it.netPct },
            maxDrawdownPct = dd,
            avgDays = trades.sumOf { it.days }.toDouble() / trades.size
        )
    }

    /** محدوده جستجوی پارامتر هر بازار. */
    fun grid(m: MarketKind): List<Params> {
        val th = listOf(60, 65, 70, 75, 80)
        val (tps, stops, trails) = when (m) {
            MarketKind.CRYPTO -> Triple(
                listOf(0.03, 0.05, 0.08, 0.12, 0.20, 0.35, 0.50),
                listOf(0.03 to 0.15),
                listOf(0.0, 0.06, 0.11)
            )
            MarketKind.IR_STOCK -> Triple(
                listOf(0.04, 0.06, 0.10, 0.15, 0.25, 0.45),
                listOf(0.04 to 0.15),
                listOf(0.0, 0.08, 0.12)
            )
            MarketKind.METAL -> Triple(
                listOf(0.015, 0.025, 0.04, 0.06, 0.09, 0.12),
                listOf(0.015 to 0.08),
                listOf(0.0, 0.02, 0.04)
            )
            MarketKind.FX -> Triple(listOf(0.01, 0.02, 0.04), listOf(0.01 to 0.035), listOf(0.0, 0.02))
        }
        val mults = listOf(1.0, 2.0, 3.0, 4.5)
        val holds = listOf(7, 15, 30, 0)
        val out = ArrayList<Params>()
        for (t in th) for (tp in tps) for ((mn, mx) in stops) for (k in mults) for (tr in trails) for (hd in holds) {
            out.add(Params(t, tp, k, mn, mx, tr, hd))
        }
        return out
    }

    fun paramsOf(plan: RiskManager.Plan, threshold: Int): Params =
        Params(
            threshold, plan.tpPct,
            if (plan.volStopMult > 0) plan.volStopMult else 2.5,
            plan.minStopPct, plan.maxStopPct, plan.trailPct, plan.maxHoldDays
        )

    private fun minTrades(m: MarketKind, assets: Int): Int = when (m) {
        MarketKind.CRYPTO -> maxOf(20, assets)
        MarketKind.IR_STOCK -> maxOf(15, assets / 2)
        else -> 6
    }

    /**
     * بهینه‌سازی یک بازار: انتخاب روی ۷۰٪ اول داده، آزمون روی ۳۰٪ آخر.
     * هدف: نرخ برد ≥ ۶۰٪ همراه با امید ریاضی مثبت (و ریسک به ریوارد واقعی حداقل ۰٫۷)؛ بین گزینه‌های مجاز،
     * بیشترین «سود خالص کل» درون نمونه (نه فقط بیشترین نرخ برد که معمولاً با حد سود خیلی کوچک و زیان‌ده است).
     */
    fun optimize(m: MarketKind, series: List<Series>, current: Params, settings: AppSettings): MarketResult? {
        if (series.isEmpty()) return null
        val allT = series.flatMap { listOf(it.t.first(), it.t.last()) }
        val fromT = allT.minOrNull() ?: return null
        val toT = allT.maxOrNull() ?: return null
        val split = fromT + ((toT - fromT) * IN_SAMPLE_FRACTION).toLong()
        fun evalP(p: Params): Pair<Stats, Stats> {
            val tr = simulate(series, p, settings)
            return stats(tr.filter { it.entryT < split }) to stats(tr.filter { it.entryT >= split })
        }
        val (curIn, curOut) = evalP(current)
        val minN = minTrades(m, series.size)
        val candidates = grid(m)
        var best: Params? = null
        var bestIn: Stats? = null
        var bestScore = Double.NEGATIVE_INFINITY
        var fallback: Params? = null
        var fallbackIn: Stats? = null
        for (p in candidates) {
            val tr = simulate(series, p, settings)
            val sIn = stats(tr.filter { it.entryT < split })
            if (sIn.trades < minN || sIn.expectancyPct <= 0 || sIn.profitFactor < 1.1) continue
            if (sIn.winRate >= TARGET_WIN_RATE && sIn.rr >= 0.7) {
                // سود کل با جریمه افت سرمایه و کمی ترجیح برای تعداد معامله بیشتر (اعتبار آماری)
                val sc = sIn.sumPct - 0.5 * sIn.maxDrawdownPct + sqrt(sIn.trades.toDouble())
                if (sc > bestScore) { bestScore = sc; best = p; bestIn = sIn }
            } else if (best == null) {
                val fs = sIn.winRate + sIn.expectancyPct
                val cur = fallbackIn
                if (cur == null || fs > cur.winRate + cur.expectancyPct) { fallback = p; fallbackIn = sIn }
            }
        }
        val reached = best != null
        val chosen = best ?: fallback
        val chosenIn = bestIn ?: fallbackIn
        val chosenOut = chosen?.let { evalP(it).second }
        val oosOk = chosenOut != null && chosenOut.trades >= 3 && chosenOut.expectancyPct > 0 && chosenOut.profitFactor >= 1.0
        val applied = chosen != null && oosOk
        val verdict = when {
            chosen == null -> "هیچ ترکیبی روی این داده سودده نبود؛ تنظیمات فعلی حفظ شد."
            reached && applied -> "به هدف نرخ برد " + TARGET_WIN_RATE.toInt() + "٪ رسید و در آزمون خارج از نمونه هم سودده بود؛ روی معاملات واقعی اعمال شد."
            reached -> "درون نمونه به " + TARGET_WIN_RATE.toInt() + "٪ رسید ولی در آزمون خارج از نمونه تأیید نشد (احتمال بیش‌برازش)؛ اعمال نشد."
            applied -> "با امید ریاضی مثبت به " + TARGET_WIN_RATE.toInt() + "٪ نرسید؛ بهترین نرخ برد سودده (" + Math.round(chosenIn?.winRate ?: 0.0) + "٪) اعمال شد چون در آزمون هم سودده بود."
            else -> "به " + TARGET_WIN_RATE.toInt() + "٪ نرسید و بهترین گزینه در آزمون هم تأیید نشد؛ تنظیمات فعلی حفظ شد."
        }
        val mix = chosen?.let { p -> simulate(series, p, settings).groupingBy { it.reason }.eachCount() } ?: emptyMap()
        return MarketResult(
            market = m,
            assets = series.size,
            days = ((toT - fromT) / 86_400_000L).toInt(),
            fromT = fromT,
            toT = toT,
            current = current,
            currentIn = curIn,
            currentOut = curOut,
            best = chosen,
            bestIn = chosenIn,
            bestOut = chosenOut,
            reachedTarget = reached,
            applied = applied,
            verdict = verdict,
            tested = candidates.size,
            exitMix = mix
        )
    }

    /** اعمال پارامتر بک‌تست روی برنامه ریسک (تعداد و اندازه موقعیت از سطح ریسک کاربر می‌آید). */
    fun applyTo(plan: RiskManager.Plan, p: Params, baseThreshold: Int): RiskManager.Plan =
        plan.copy(
            tpPct = p.tpPct,
            volStopMult = p.stopMult,
            minStopPct = p.minStopPct,
            maxStopPct = p.maxStopPct,
            stopPct = (p.minStopPct + p.maxStopPct) / 2,
            trailPct = p.trailPct,
            maxHoldDays = p.maxHoldDays,
            buyThresholdDelta = p.threshold - baseThreshold,
            tuned = true
        )
}
