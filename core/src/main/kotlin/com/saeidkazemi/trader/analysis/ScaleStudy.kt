package com.saeidkazemi.trader.analysis

import com.saeidkazemi.trader.data.model.AppSettings
import com.saeidkazemi.trader.data.model.MarketKind

/**
 * مطالعه «ورود و خروج پله‌ای» روی همان داده و همان سیگنال‌های بک‌تست.
 *
 * همه حالت‌ها با یک «سهم سرمایه» ثابت برای هر معامله مقایسه می‌شوند: اگر در ورود پله‌ای پله دوم
 * خریده نشود، نیمه خرج‌نشده بازده صفر دارد (منصفانه در برابر خرید کامل).
 * واحد «R» فاصله حد ضرر اولیه است (مثلاً حد ضرر ۸٪ ⇒ 1R = ۸٪ سود).
 */
object ScaleStudy {

    data class Variant(
        val id: String,
        val title: String,
        /** قفل سود ۱۰٪/۱۰٪ فعلی برنامه. */
        val lock: Boolean = false,
        /** انتقال حد ضرر به سربه‌سر (با کارمزد) وقتی سود به این مضرب R رسید (۰ = خاموش). */
        val beR: Double = 0.0,
        /** فروش پله‌ای: (سود بر حسب R، کسری از مقدار باقی‌مانده). */
        val partials: List<Pair<Double, Double>> = emptyList(),
        /** بعد از اولین فروش پله‌ای، حد ضرر باقی‌مانده به سربه‌سر برود. */
        val beAfterPartial: Boolean = false,
        /** ورود: ۰ یک‌جا، ۱ نصف + نصف در افت (میانگین کم کردن)، ۲ نصف + نصف بعد از تأیید (بالا رفتن). */
        val entry: Int = 0,
        val entryR: Double = 0.5,
        val entryDays: Int = 5,
        /** رسیدن به حد سود: ۰ فروش، ۱ نفروش و آن نقطه را خرید جدید فرض کن (حد ضرر/سود از نو)، ۲ همان با حد ضرر نصف فاصله. */
        val roll: Int = 0,
        /** فاصله خرید دوباره همان دارایی بعد از خروج با حد ضرر (روز): ۰ همان لحظه، ۱ روز بعد (پیش‌فرض بک‌تست). */
        val reGap: Int = 1
    )

    data class Row(
        val market: MarketKind = MarketKind.CRYPTO,
        val id: String = "",
        val title: String = "",
        val all: Backtest.Stats = Backtest.Stats(),
        val oos: Backtest.Stats = Backtest.Stats(),
        /** معاملاتی که حداقل 1R سود شناور داشتند. */
        val reached1R: Int = 0,
        /** …و با این حال بی‌سود یا با زیان بسته شدند (سود پس داده شد). */
        val giveback1R: Int = 0,
        /** معاملاتی که حداقل ۸٪ سود شناور داشتند و بی‌سود/با زیان بسته شدند (مشاهده کاربر). */
        val giveback8: Int = 0,
        val reached8: Int = 0
    )

    val VARIANTS: List<Variant> = listOf(
        Variant("A", "فعلی (خروج یک‌جا، قفل سود خاموش)"),
        Variant("B", "فعلی + قفل سود ۱۰٪/۱۰٪", lock = true),
        Variant("C", "حد ضرر سربه‌سر بعد از 1R سود", beR = 1.0),
        Variant("D", "حد ضرر سربه‌سر بعد از 0.5R سود", beR = 0.5),
        Variant("E", "فروش نصف در 1R + سربه‌سر برای بقیه", partials = listOf(1.0 to 0.5), beAfterPartial = true),
        Variant("F", "فروش نصف در 0.5R + سربه‌سر برای بقیه", partials = listOf(0.5 to 0.5), beAfterPartial = true),
        Variant("G", "سه پله: ⅓ در 1R، ⅓ در 2R، بقیه با حد ضرر متحرک", partials = listOf(1.0 to 1.0 / 3, 2.0 to 0.5), beAfterPartial = true),
        Variant("H", "ورود پله‌ای: نصف + نصف در افت 0.5R (۵ روز)", entry = 1),
        Variant("I", "ورود پله‌ای: نصف + نصف بعد از 0.5R رشد (۵ روز)", entry = 2),
        Variant("J", "ترکیبی: ورود تأییدی (I) + فروش نصف در 1R (E)", entry = 2, partials = listOf(1.0 to 0.5), beAfterPartial = true),
        Variant("K", "در حد سود نفروش؛ آن نقطه خرید جدید (حد ضرر و سود از نو)", roll = 1),
        Variant("L", "مثل K ولی حد ضرر جدید نصف فاصله (نزدیک‌تر)", roll = 2),
        Variant("M", "خرید دوباره همان لحظه بعد از حد ضرر", reGap = 0),
        Variant("N", "بعد از حد ضرر ۳ روز همان دارایی خریده نشود", reGap = 3),
        Variant("O", "ترکیبی: ورود تأییدی (I) + نفروختن در حد سود (K)", entry = 2, roll = 1)
    )

    fun run(m: MarketKind, series: List<Backtest.Series>, p: Backtest.Params, settings: AppSettings): List<Row> {
        if (series.isEmpty()) return emptyList()
        val allT = series.flatMap { listOf(it.t.first(), it.t.last()) }
        val fromT = allT.minOrNull() ?: return emptyList()
        val toT = allT.maxOrNull() ?: return emptyList()
        val split = fromT + ((toT - fromT) * Backtest.IN_SAMPLE_FRACTION).toLong()
        return VARIANTS.map { v ->
            val res = series.flatMap { simulate(it, p, settings, v) }
            val trades = res.map { it.trade }
            Row(
                market = m, id = v.id, title = v.title,
                all = Backtest.stats(trades),
                oos = Backtest.stats(trades.filter { it.entryT >= split }),
                reached1R = res.count { it.peakR >= 1.0 },
                giveback1R = res.count { it.peakR >= 1.0 && it.trade.netPct <= 0 },
                reached8 = res.count { it.peakPct >= 8.0 },
                giveback8 = res.count { it.peakPct >= 8.0 && it.trade.netPct <= 0 }
            )
        }
    }

    class Sim(val trade: Backtest.Trade, val peakR: Double, val peakPct: Double)

    fun simulate(s: Backtest.Series, p: Backtest.Params, settings: AppSettings, v: Variant): List<Sim> {
        val out = ArrayList<Sim>()
        val c = s.costs
        val bf = c.buyFee
        val sf = c.sellFee
        val hs = c.halfSpread
        val lockTrig = if (v.lock) settings.profitLockTriggerPct else 0.0
        val lockKeep = if (v.lock) minOf(settings.profitLockKeepPct, settings.profitLockTriggerPct) else 0.0
        val n = s.close.size
        var i = 0
        while (i < n - 1) {
            val sc = s.score[i]
            val enter = if (p.mode == 1) sc >= 0 && s.rsi[i].isFinite() && s.rsi[i] < p.rsiMax else sc >= p.threshold
            if (!enter) { i++; continue }
            if (p.filter != 0 && !Backtest.filterOk(p.filter, s.above100[i], s.breadth[i].takeIf { it.isFinite() })) { i++; continue }

            val e0 = s.close[i]
            val stopPct = p.stopFor(s.vol[i].takeIf { it.isFinite() })
            val r = stopPct
            var stop = e0 * (1 - stopPct)
            var tp = e0 * (1 + p.tpPct)
            var peak = e0
            // حساب بر حسب یک واحد سرمایه (۱ دلار) برای هر معامله
            var units = 0.0
            var bought = 0.0
            var spent = 0.0
            var proceeds = 0.0
            fun buy(amount: Double, px: Double) {
                val u = amount * (1 - bf) / (px * (1 + hs))
                units += u
                bought += u
                spent += amount
            }
            fun sell(frac: Double, px: Double) {
                val u = units * frac
                proceeds += u * px * (1 - hs) * (1 - sf)
                units -= u
            }
            // سربه‌سر = قیمت خرید (میانگین) به‌علاوه کارمزدها؛ بقیه موقعیت دیگر زیر قیمت خرید فروخته نمی‌شود
            fun breakEven(): Double = if (bought > 0) spent / bought / ((1 - hs) * (1 - sf)) else 0.0
            buy(if (v.entry == 0) 1.0 else 0.5, e0)
            var addDone = v.entry == 0
            var partialIdx = 0
            var exitIdx = -1
            var reason = ""
            var j = i + 1
            while (j < n) {
                val px = s.close[j]
                if (px > peak) peak = px
                // پله دوم ورود
                if (!addDone && (j - i) <= v.entryDays) {
                    if (v.entry == 1 && px <= e0 * (1 - v.entryR * r) && px > stop) { buy(0.5, px); addDone = true }
                    else if (v.entry == 2 && px >= e0 * (1 + v.entryR * r)) { buy(0.5, px); addDone = true }
                }
                if (p.trailPct > 0) stop = maxOf(stop, peak * (1 - p.trailPct))
                if (lockTrig > 0) {
                    RiskManager.ProfitLock.stopFor(e0, peak, bf + hs, sf + hs, lockTrig, lockKeep)?.let { stop = maxOf(stop, it) }
                }
                if (v.beR > 0 && peak >= e0 * (1 + v.beR * r)) stop = maxOf(stop, breakEven() * 1.001)
                // فروش پله‌ای (قبل از بررسی حد سود کامل)
                while (partialIdx < v.partials.size && px >= e0 * (1 + v.partials[partialIdx].first * r) && px < tp) {
                    sell(v.partials[partialIdx].second, px)
                    partialIdx++
                    if (v.beAfterPartial) stop = maxOf(stop, breakEven() * 1.001)
                }
                // «حد سود = نقطه خرید جدید»: نمی‌فروشد، حد ضرر و حد سود را از این قیمت از نو می‌گذارد
                if (v.roll > 0 && px >= tp && px > stop) {
                    stop = maxOf(stop, px * (1 - (if (v.roll == 2) 0.5 else 1.0) * stopPct))
                    tp = px * (1 + p.tpPct)
                }
                reason = when {
                    px <= stop -> if (stop > e0) "حد ضرر متحرک/سربه‌سر" else "حد ضرر"
                    px >= tp -> "حد سود"
                    p.mode == 0 && s.score[j] in 0..settings.sellThreshold -> "ضعیف شدن سیگنال"
                    p.maxHoldDays > 0 && (s.t[j] - s.t[i]) >= p.maxHoldDays * 86_400_000L -> "پایان مهلت نگهداری"
                    else -> ""
                }
                if (reason.isNotEmpty()) { exitIdx = j; break }
                j++
            }
            if (exitIdx < 0) break
            sell(1.0, s.close[exitIdx])
            val net = (proceeds - spent) * 100 // بر حسب کل سهم سرمایه این معامله
            out.add(
                Sim(
                    Backtest.Trade(
                        s.assetId, s.symbol, s.t[i], s.t[exitIdx], net, reason,
                        ((s.t[exitIdx] - s.t[i]) / 86_400_000L).toInt(), p.tpPct / stopPct
                    ),
                    peakR = (peak / e0 - 1) / r,
                    peakPct = (peak / e0 - 1) * 100
                )
            )
            i = if (reason.startsWith("حد ضرر")) exitIdx + v.reGap else exitIdx + 1
        }
        return out
    }

    /** خلاصه فارسی برای گزارش. */
    fun lines(rows: List<Row>): List<String> {
        val out = ArrayList<String>()
        fun f(x: Double, d: Int = 2) = String.format(java.util.Locale.US, "%." + d + "f", x)
        for ((m, rs) in rows.groupBy { it.market }) {
            val a = rs.firstOrNull { it.id == "A" }
            out.add(m.faTitle + (if (a != null) " — از " + a.reached1R + " معامله‌ای که به 1R سود رسید، " + a.giveback1R +
                " مورد بی‌سود/با زیان بسته شد؛ از " + a.reached8 + " معامله با ≥۸٪ سود شناور، " + a.giveback8 + " مورد بی‌سود/زیان‌ده" else ""))
            for (r in rs) {
                out.add("  " + r.id + ") " + r.title + ": n=" + r.all.trades + " برد " + f(r.all.winRate, 1) + "٪ • میانگین هر معامله " +
                    f(r.all.expectancyPct) + "٪ • PF " + f(r.all.profitFactor) + " • افت " + f(r.all.maxDrawdownPct, 1) +
                    "٪ | خارج از نمونه: n=" + r.oos.trades + " برد " + f(r.oos.winRate, 1) + "٪ میانگین " + f(r.oos.expectancyPct) + "٪ PF " + f(r.oos.profitFactor))
            }
        }
        return out
    }
}
