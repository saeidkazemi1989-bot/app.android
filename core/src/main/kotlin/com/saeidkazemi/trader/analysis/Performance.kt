package com.saeidkazemi.trader.analysis

import com.saeidkazemi.trader.data.model.AppSettings
import com.saeidkazemi.trader.data.model.JournalEntry
import com.saeidkazemi.trader.data.model.MarketKind

/** آمار عملکرد (پنل سودآوری) بر اساس معاملات بسته‌شده ژورنال. */
data class PerfStats(
    val closed: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    /** نرخ برد (۰ تا ۱)؛ null یعنی هنوز معامله بسته‌شده‌ای نیست. */
    val winRate: Double? = null,
    val totalPnlUsd: Double = 0.0,
    val grossWinUsd: Double = 0.0,
    val grossLossUsd: Double = 0.0,
    val avgWinUsd: Double? = null,
    val avgLossUsd: Double? = null,
    val avgWinPct: Double? = null,
    val avgLossPct: Double? = null,
    /** ضریب سود = جمع سودها ÷ جمع زیان‌ها (بالاتر از ۱ یعنی سودده). */
    val profitFactor: Double? = null,
    /** نسبت میانگین سود به میانگین زیان. */
    val payoff: Double? = null,
    /** نرخ بردی که با این نسبت سود به زیان، سربه‌سر است. */
    val breakEvenWinRate: Double? = null,
    /** امید ریاضی هر معامله (میانگین سود/زیان). */
    val expectancyUsd: Double? = null,
    val expectancyPct: Double? = null,
    val bestPct: Double? = null,
    val worstPct: Double? = null,
    val avgHoldMs: Long? = null,
    val maxConsecLosses: Int = 0,
    /** بیشترین افت از قله منحنی سود تحقق‌یافته. */
    val maxDrawdownUsd: Double = 0.0
)

/** وضعیت محافظ نرخ برد یک بازار. */
data class GuardState(
    val market: MarketKind,
    val active: Boolean,
    val window: Int,
    val sample: Int,
    val winRate: Double?,
    val pnlUsd: Double,
    val text: String
)

data class ReasonStat(val category: String, val count: Int, val wins: Int, val pnlUsd: Double)

/** گزارش کامل پنل سودآوری. */
data class PerfReport(
    val all: PerfStats = PerfStats(),
    val byMarket: Map<MarketKind, PerfStats> = emptyMap(),
    val byExit: List<ReasonStat> = emptyList(),
    val auto: PerfStats = PerfStats(),
    val manual: PerfStats = PerfStats(),
    val last10: PerfStats = PerfStats(),
    val guards: List<GuardState> = emptyList(),
    /** منحنی سود تحقق‌یافته تجمعی (زمان، دلار). */
    val curve: List<Pair<Long, Double>> = emptyList(),
    val openCount: Int = 0
)

object Performance {

    /** افزایش آستانه خرید وقتی محافظ نرخ برد فعال است. */
    const val GUARD_EXTRA_THRESHOLD = 8

    /** ضریب حجم هر خرید وقتی محافظ فعال است. */
    const val GUARD_SIZE_FACTOR = 0.5

    val TRADED_MARKETS = listOf(MarketKind.CRYPTO, MarketKind.IR_STOCK, MarketKind.FX)

    fun stats(entries: List<JournalEntry>): PerfStats {
        val closed = entries.filter { !it.isOpen && it.pnlUsd != null }.sortedBy { it.closedAt }
        if (closed.isEmpty()) return PerfStats()
        val wins = closed.filter { it.pnlUsd!! > 0 }
        val losses = closed.filter { it.pnlUsd!! <= 0 }
        val gw = wins.sumOf { it.pnlUsd!! }
        val gl = -losses.sumOf { it.pnlUsd!! }
        val avgW = if (wins.isNotEmpty()) gw / wins.size else null
        val avgL = if (losses.isNotEmpty()) gl / losses.size else null
        val payoff = if (avgW != null && avgL != null && avgL > 0) avgW / avgL else null
        var streak = 0
        var maxStreak = 0
        var cum = 0.0
        var peak = 0.0
        var dd = 0.0
        for (e in closed) {
            if (e.pnlUsd!! <= 0) {
                streak++
                maxStreak = maxOf(maxStreak, streak)
            } else streak = 0
            cum += e.pnlUsd!!
            peak = maxOf(peak, cum)
            dd = maxOf(dd, peak - cum)
        }
        val pcts = closed.mapNotNull { it.pnlPct }
        return PerfStats(
            closed = closed.size,
            wins = wins.size,
            losses = losses.size,
            winRate = wins.size.toDouble() / closed.size,
            totalPnlUsd = gw - gl,
            grossWinUsd = gw,
            grossLossUsd = gl,
            avgWinUsd = avgW,
            avgLossUsd = avgL,
            avgWinPct = wins.mapNotNull { it.pnlPct }.takeIf { it.isNotEmpty() }?.average(),
            avgLossPct = losses.mapNotNull { it.pnlPct }.takeIf { it.isNotEmpty() }?.average(),
            profitFactor = if (gl > 0) gw / gl else if (gw > 0) Double.POSITIVE_INFINITY else null,
            payoff = payoff,
            breakEvenWinRate = payoff?.let { 1.0 / (1.0 + it) },
            expectancyUsd = (gw - gl) / closed.size,
            expectancyPct = pcts.takeIf { it.isNotEmpty() }?.average(),
            bestPct = pcts.maxOrNull(),
            worstPct = pcts.minOrNull(),
            avgHoldMs = closed.map { it.holdMs }.average().toLong(),
            maxConsecLosses = maxStreak,
            maxDrawdownUsd = dd
        )
    }

    /** دسته‌بندی دلیل خروج برای آمار. */
    fun exitCategory(reason: String?): String {
        val r = reason ?: return "نامشخص"
        return when {
            r.contains("حفظ سود") || r.contains("قفل سود حداقل") -> "قفل سود"
            r.contains("متحرک") -> "حد ضرر متحرک"
            r.contains("حد ضرر") -> "حد ضرر"
            r.contains("حد سود") -> "حد سود"
            r.contains("خبر") -> "خبر منفی"
            r.contains("تخصصی") -> "شرایط تخصصی"
            r.contains("ضعیف") -> "ضعیف شدن سیگنال"
            r.contains("دستی") -> "فروش دستی"
            else -> "سایر"
        }
    }

    /** محافظ نرخ برد برای یک بازار. */
    fun guard(entries: List<JournalEntry>, market: MarketKind, settings: AppSettings): GuardState {
        val window = settings.guardWindow.coerceIn(3, 100)
        val recent = entries
            .filter { it.market == market && !it.isOpen && it.pnlUsd != null }
            .sortedByDescending { it.closedAt }
            .take(window)
        val wr = if (recent.isEmpty()) null else recent.count { it.pnlUsd!! > 0 }.toDouble() / recent.size
        val pnl = recent.sumOf { it.pnlUsd!! }
        val minWr = settings.minWinRatePct / 100.0
        val active = settings.winRateGuard && recent.size >= window && wr != null && wr < minWr && pnl < 0
        val text = when {
            !settings.winRateGuard -> "محافظ خاموش است"
            recent.size < window -> "هنوز " + recent.size + " از " + window + " معامله لازم بسته شده؛ معامله عادی ادامه دارد"
            active -> "محتاط: نرخ برد " + pct(wr) + " کمتر از " + pct(minWr) + " و جمع " + money(pnl) +
                " دلار منفی است ← آستانه خرید +" + GUARD_EXTRA_THRESHOLD + " و حجم هر خرید نصف"
            else -> "عادی: نرخ برد " + pct(wr) + "، جمع " + money(pnl) + " دلار"
        }
        return GuardState(market, active, window, recent.size, wr, pnl, text)
    }

    fun report(entries: List<JournalEntry>, settings: AppSettings): PerfReport {
        val closed = entries.filter { !it.isOpen && it.pnlUsd != null }.sortedBy { it.closedAt }
        var cum = 0.0
        val curve = closed.map { e ->
            cum += e.pnlUsd!!
            (e.closedAt ?: 0L) to cum
        }
        val byExit = closed.groupBy { exitCategory(it.exitReason) }.map { (k, v) ->
            ReasonStat(k, v.size, v.count { it.pnlUsd!! > 0 }, v.sumOf { it.pnlUsd!! })
        }.sortedByDescending { it.count }
        return PerfReport(
            all = stats(closed),
            byMarket = TRADED_MARKETS.associateWith { m -> stats(closed.filter { it.market == m }) },
            byExit = byExit,
            auto = stats(closed.filter { it.auto }),
            manual = stats(closed.filter { !it.auto }),
            last10 = stats(closed.takeLast(10)),
            guards = TRADED_MARKETS.map { guard(entries, it, settings) },
            curve = curve,
            openCount = entries.count { it.isOpen }
        )
    }

    private fun pct(v: Double?): String = if (v == null) "—" else com.saeidkazemi.trader.util.Format.num(v * 100, 0) + "٪"
    private fun money(v: Double): String = com.saeidkazemi.trader.util.Format.money(v)
}
