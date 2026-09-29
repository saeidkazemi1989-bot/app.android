package com.saeidkazemi.trader.review

import com.saeidkazemi.trader.analysis.Performance
import com.saeidkazemi.trader.data.model.JournalEntry
import com.saeidkazemi.trader.data.model.MarketKind
import java.util.Locale
import kotlin.math.abs

/** یک یافته خودارزیابی: کجا درست فکر کردم (good=true)، کجا اشتباه (false) یا فقط اطلاع (null). */
data class Finding(
    val good: Boolean? = null,
    /** ۲ مهم، ۱ متوسط، ۰ اطلاع. */
    val severity: Int = 0,
    val title: String = "",
    val detail: String = "",
    /** پیشنهاد اصلاح (برای نسخه بعد). */
    val fix: String? = null
)

/** آمار یک گروه از پیش‌بینی‌ها یا معاملات. */
data class GroupStat(
    val label: String = "",
    val n: Int = 0,
    /** میانگین بازده (درصد). */
    val avg: Double = 0.0,
    /** میانگین بازده نسبت به کل همان بازار در همان بازه (درصد). */
    val excess: Double = 0.0,
    /** سهم موارد بالا رفته (۰ تا ۱). */
    val upRate: Double = 0.0
)

/** نتیجه یک معامله بسته در کالبدشکافی. */
data class TradeVerdict(
    val id: String = "",
    val verdict: String = "",
    val right: Boolean = false,
    /** بازده قیمت ۲۴ تا ۷۲ ساعت بعد از فروش نسبت به قیمت فروش (درصد)؛ null یعنی نامعلوم. */
    val afterExitPct: Double? = null
)

/** خلاصه خودارزیابی برای نمایش در برنامه (و ارسال به آینه). */
data class ReviewSummary(
    val createdAt: Long = 0L,
    val since: Long = 0L,
    val predictions: Int = 0,
    val evaluated1: Int = 0,
    val evaluated3: Int = 0,
    /** دقت سیگنال‌های خرید: درصد مواردی که ۲۴ ساعت بعد بالاتر بود. */
    val buyN1: Int = 0,
    val buyHit1: Double? = null,
    val buyAvg1: Double? = null,
    val buyExcess1: Double? = null,
    val buyN3: Int = 0,
    val buyHit3: Double? = null,
    val buyAvg3: Double? = null,
    val buyExcess3: Double? = null,
    val sellN1: Int = 0,
    val sellHit1: Double? = null,
    /** نرخ پایه: درصد همه دارایی‌های بررسی‌شده که ۲۴ ساعت بعد بالاتر بودند. */
    val baseUp1: Double? = null,
    val baseAvg1: Double? = null,
    val closedTrades: Int = 0,
    val rightTrades: Int = 0,
    val verdictCounts: Map<String, Int>? = null,
    val findings: List<Finding>? = null,
    val issues: Int = 0
)

/**
 * موتور خودارزیابی: پیش‌بینی‌های ثبت‌شده و معاملات بسته را با آنچه واقعاً اتفاق افتاد مقایسه می‌کند.
 * «بازده نسبی» یعنی بازده یک دارایی منهای میانگین همه دارایی‌های همان بازار در همان ۶ ساعت؛ این‌طوری
 * اثر بالا/پایین رفتن کل بازار حذف می‌شود و فقط قدرت انتخاب موتور سنجیده می‌شود.
 */
object SelfReview {

    const val MIN_GROUP = 12

    class Eval(
        val preds: List<Prediction>,
        val ex1: Map<Prediction, Double>,
        val ex3: Map<Prediction, Double>
    )

    fun f(v: Double?, d: Int = 2): String = if (v == null || !v.isFinite()) "—" else String.format(Locale.US, "%." + d + "f", v)
    fun sp(v: Double?, d: Int = 2): String = if (v == null || !v.isFinite()) "—" else (if (v > 0) "+" else "") + f(v, d) + "%"
    fun pc(v: Double?): String = if (v == null || !v.isFinite()) "—" else String.format(Locale.US, "%.0f", v * 100) + "%"

    private fun excess(preds: List<Prediction>, ret: (Prediction) -> Double?): Map<Prediction, Double> {
        val groups = preds.filter { ret(it) != null }.groupBy { it.market + ":" + (it.ts / ReviewLog.PREDICTION_GAP_MS) }
        val out = HashMap<Prediction, Double>()
        for ((_, g) in groups) {
            val mean = g.mapNotNull(ret).average()
            for (p in g) out[p] = ret(p)!! - mean
        }
        return out
    }

    fun evaluate(preds: List<Prediction>): Eval = Eval(preds, excess(preds) { it.ret1 }, excess(preds) { it.ret3 })

    fun group(label: String, list: List<Prediction>, ret: (Prediction) -> Double?, ex: Map<Prediction, Double>): GroupStat {
        val r = list.mapNotNull { p -> ret(p)?.let { p to it } }
        if (r.isEmpty()) return GroupStat(label, 0)
        return GroupStat(
            label = label,
            n = r.size,
            avg = r.map { it.second }.average(),
            excess = r.mapNotNull { ex[it.first] }.let { if (it.isEmpty()) 0.0 else it.average() },
            upRate = r.count { it.second > 0 }.toDouble() / r.size
        )
    }

    fun scoreBuckets(e: Eval, h3: Boolean): List<GroupStat> {
        val ret: (Prediction) -> Double? = if (h3) Prediction::ret3 else Prediction::ret1
        val ex = if (h3) e.ex3 else e.ex1
        val b = listOf(0 to 40, 40 to 50, 50 to 60, 60 to 70, 70 to 101)
        return b.map { (lo, hi) ->
            group((if (hi > 100) "$lo+" else "$lo-$hi"), e.preds.filter { it.score in lo until hi }, ret, ex)
        }
    }

    /** اثر هر جزء امتیاز: وقتی مثبت بود در برابر وقتی منفی بود. */
    data class FactorStat(val name: String, val pos: GroupStat, val neg: GroupStat) {
        val diff: Double get() = pos.excess - neg.excess
        val verdict: String get() = when {
            pos.n < MIN_GROUP || neg.n < MIN_GROUP -> "داده کم"
            diff > 0.3 -> "درست"
            diff < -0.3 -> "غلط"
            else -> "بی‌اثر"
        }
    }

    fun factors(e: Eval): List<FactorStat> {
        val ret: (Prediction) -> Double? = { it.ret1 }
        val out = ArrayList<FactorStat>()
        fun add(name: String, pos: (Prediction) -> Boolean, neg: (Prediction) -> Boolean) {
            out.add(FactorStat(name, group("+", e.preds.filter(pos), ret, e.ex1), group("-", e.preds.filter(neg), ret, e.ex1)))
        }
        add("امتیاز تکنیکال (≥۶۰ در برابر <۵۰)", { it.tech >= 60 }, { it.tech < 50 })
        add("اثر اخبار", { it.news > 0 }, { it.news < 0 })
        add("اثر تحلیل تخصصی (جمع)", { it.pro > 0 }, { it.pro < 0 })
        add("پیش‌بینی آماری ۷روزه", { (it.fcExp ?: 0.0) > 0 }, { (it.fcExp ?: 0.0) < 0 })
        val titles = e.preds.flatMap { it.factors.orEmpty().keys }.groupingBy { it }.eachCount()
            .filter { it.value >= MIN_GROUP }.keys.sorted()
        for (t in titles) add("عامل تخصصی: $t", { (it.factors?.get(t) ?: 0) > 0 }, { (it.factors?.get(t) ?: 0) < 0 })
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // کالبدشکافی معاملات

    fun verdicts(journal: List<JournalEntry>, preds: List<Prediction>): List<Pair<JournalEntry, TradeVerdict>> {
        val byAsset = preds.groupBy { it.assetId }
        return journal.filter { !it.isOpen && !it.backfilled }.sortedByDescending { it.closedAt }.map { e ->
            val pnl = e.pnlPct ?: 0.0
            val mfe = e.maxGainPct ?: maxOf(pnl, 0.0)
            val verdict: String
            val right: Boolean
            // طلا و دلار نوسان کمی دارند؛ ۱٪ حرکت مثبت برایشان معنادار است (برای بقیه ۳٪)
            val lowVol = e.market == MarketKind.METAL || e.market == MarketKind.FX
            val good = if (lowVol) 1.0 else 3.0
            val tiny = if (lowVol) 0.4 else 1.0
            val manualExit = (e.exitReason ?: "").let { r -> "خبر" in r || "ضعیف شدن سیگنال" in r || "تخصصی" in r }
            if (e.isWin) {
                right = true
                verdict = if (mfe >= good * 5 / 3 && pnl < mfe * 0.4) "برد، ولی بیشتر سود پس داده شد" else "درست (سود)"
            } else {
                right = false
                verdict = when {
                    mfe >= good && manualExit -> "ورود درست، خروج زودهنگام (در سود بود؛ با «" + (e.exitReason ?: "").take(30) + "» بی‌سود بسته شد)"
                    mfe >= good -> "ورود درست، خروج بد (سود داشت، با زیان بسته شد)"
                    mfe < tiny -> "ورود غلط (از همان اول خلاف جهت رفت)"
                    else -> "ورود ضعیف (حرکت مثبت کافی نداشت)"
                }
            }
            // بعد از فروش قیمت کجا رفت؟ (از روی پیش‌بینی‌های ثبت‌شده همان دارایی بعد از فروش)
            var after: Double? = null
            val closedAt = e.closedAt ?: 0L
            val exitN = e.exitNative
            if (exitN != null && exitN > 0) {
                val p = byAsset[e.assetId].orEmpty()
                    .filter { it.ts >= closedAt && it.ts - closedAt <= 8 * ReviewLog.H1 && abs(it.price / exitN - 1) < 0.3 }
                    .minByOrNull { it.ts }
                val later = p?.p3 ?: p?.p1
                if (later != null) after = (later / exitN - 1) * 100
            }
            e to TradeVerdict(e.id, verdict, right, after)
        }
    }

    // ---------------------------------------------------------------------------------------------

    fun summary(
        preds: List<Prediction>,
        journal: List<JournalEntry>,
        issues: List<DiagIssue>,
        cycles: List<CycleDiag>,
        since: Long,
        extra: List<Finding> = emptyList()
    ): ReviewSummary {
        val e = evaluate(preds)
        val ev1 = preds.filter { it.ret1 != null }
        val ev3 = preds.filter { it.ret3 != null }
        val buys1 = ev1.filter { it.dir > 0 }
        val buys3 = ev3.filter { it.dir > 0 }
        val sells1 = ev1.filter { it.dir < 0 }
        val gb1 = group("buy1", buys1, { it.ret1 }, e.ex1)
        val gb3 = group("buy3", buys3, { it.ret3 }, e.ex3)
        val gs1 = group("sell1", sells1, { it.ret1 }, e.ex1)
        val base1 = group("all1", ev1, { it.ret1 }, e.ex1)
        val v = verdicts(journal, preds)
        val fl = findings(e, journal, v, issues, cycles) + extra
        return ReviewSummary(
            createdAt = System.currentTimeMillis(),
            since = since,
            predictions = preds.size,
            evaluated1 = ev1.size,
            evaluated3 = ev3.size,
            buyN1 = gb1.n,
            buyHit1 = if (gb1.n > 0) gb1.upRate else null,
            buyAvg1 = if (gb1.n > 0) gb1.avg else null,
            buyExcess1 = if (gb1.n > 0) gb1.excess else null,
            buyN3 = gb3.n,
            buyHit3 = if (gb3.n > 0) gb3.upRate else null,
            buyAvg3 = if (gb3.n > 0) gb3.avg else null,
            buyExcess3 = if (gb3.n > 0) gb3.excess else null,
            sellN1 = gs1.n,
            sellHit1 = if (gs1.n > 0) 1 - gs1.upRate else null,
            baseUp1 = if (base1.n > 0) base1.upRate else null,
            baseAvg1 = if (base1.n > 0) base1.avg else null,
            closedTrades = v.size,
            rightTrades = v.count { it.second.right },
            verdictCounts = v.groupingBy { it.second.verdict }.eachCount(),
            findings = fl.sortedWith(compareByDescending<Finding> { it.severity }.thenBy { if (it.good == false) 0 else if (it.good == null) 1 else 2 }),
            issues = issues.size
        )
    }

    /** قواعد خودارزیابی: هر قاعده اگر داده کافی داشت یک یافته می‌سازد. */
    fun findings(
        e: Eval,
        journal: List<JournalEntry>,
        v: List<Pair<JournalEntry, TradeVerdict>>,
        issues: List<DiagIssue>,
        cycles: List<CycleDiag>
    ): List<Finding> {
        val out = ArrayList<Finding>()
        val ev1 = e.preds.filter { it.ret1 != null }

        // ۱) آیا امتیاز خرید واقعاً دارایی بهتر را انتخاب می‌کند؟ (به تفکیک بازار)
        for (m in MarketKind.values()) {
            val list = ev1.filter { it.market == m.name }
            val buys = list.filter { it.dir > 0 }
            if (buys.size < MIN_GROUP) continue
            val g = group("", buys, { it.ret1 }, e.ex1)
            val g3 = group("", e.preds.filter { it.market == m.name && it.dir > 0 }, { it.ret3 }, e.ex3)
            val base = list.count { (it.ret1 ?: 0.0) > 0 }.toDouble() / list.size
            val txt = "سیگنال‌های خرید " + m.faTitle + ": " + g.n + " مورد، " + pc(g.upRate) + " بعد از ۲۴ ساعت بالاتر بودند (کل بازار " + pc(base) +
                ")؛ بازده نسبی " + sp(g.excess) + " در ۲۴ ساعت" + (if (g3.n >= MIN_GROUP) " و " + sp(g3.excess) + " در ۷۲ ساعت" else "") + "."
            when {
                g.excess > 0.3 && (g3.n < MIN_GROUP || g3.excess > 0) ->
                    out.add(Finding(true, 1, "انتخاب خرید در " + m.faTitle + " درست بود", txt))
                g.excess < -0.3 || (g3.n >= MIN_GROUP && g3.excess < -0.5) ->
                    out.add(Finding(false, 2, "انتخاب خرید در " + m.faTitle + " بدتر از میانگین بازار بود", txt,
                        "وزن عوامل «غلط» (جدول عوامل) کم شود یا آستانه خرید این بازار بالاتر برود."))
                else -> out.add(Finding(null, 1, "امتیاز خرید در " + m.faTitle + " فعلاً برتری روشنی ندارد", txt))
            }
        }

        // ۲) عوامل امتیاز: کدام درست کار کرده، کدام غلط
        for (fs in factors(e)) {
            when (fs.verdict) {
                "درست" -> out.add(Finding(true, 0, fs.name + " درست کار کرد",
                    "وقتی مثبت بود بازده نسبی " + sp(fs.pos.excess) + " (" + fs.pos.n + " مورد)، وقتی منفی بود " + sp(fs.neg.excess) + " (" + fs.neg.n + " مورد)."))
                "غلط" -> out.add(Finding(false, if (abs(fs.diff) > 1) 2 else 1, fs.name + " برعکس عمل کرد",
                    "وقتی مثبت بود بازده نسبی " + sp(fs.pos.excess) + " (" + fs.pos.n + " مورد)، وقتی منفی بود " + sp(fs.neg.excess) + " (" + fs.neg.n + " مورد).",
                    "وزن این عامل کم یا جهتش بازبینی شود."))
            }
        }

        // ۳) پیش‌بینی آماری: احتمال بالا رفتن در برابر واقعیت
        val fc = e.preds.filter { it.fcUp != null && it.ret3 != null }
        val hiConf = fc.filter { (it.fcUp ?: 0.0) >= 0.6 }
        if (hiConf.size >= MIN_GROUP) {
            val up = hiConf.count { (it.ret3 ?: 0.0) > 0 }.toDouble() / hiConf.size
            if (up < 0.5) out.add(Finding(false, 1, "پیش‌بینی روند بیش از حد خوش‌بین بود",
                "در " + hiConf.size + " موردی که احتمال بالا رفتن ≥۶۰٪ اعلام شد، فقط " + pc(up) + " در ۷۲ ساعت بالا رفتند.",
                "کالیبره کردن پیش‌بینی (کوچک‌تر کردن اثر امتیاز روی روند پیش‌بینی)."))
            else out.add(Finding(true, 0, "پیش‌بینی روند (احتمال بالا) قابل اعتماد بود",
                "در " + hiConf.size + " مورد با احتمال ≥۶۰٪، " + pc(up) + " در ۷۲ ساعت بالا رفتند."))
        }

        // ۴) معاملات بسته
        val losses = v.filter { !it.second.right }
        if (v.size >= 4) {
            val wrongEntry = losses.count { it.second.verdict.startsWith("ورود غلط") }
            val badExit = losses.count { it.second.verdict.startsWith("ورود درست") }
            val gaveBack = v.count { it.second.verdict.startsWith("برد، ولی") }
            if (losses.size >= 3 && wrongEntry >= losses.size / 2.0) out.add(Finding(false, 2, "بیشتر زیان‌ها از ورود غلط بود",
                wrongEntry.toString() + " از " + losses.size + " معامله زیان‌ده از همان ابتدا خلاف جهت رفتند (هیچ‌وقت بیش از ۱٪ سود نداشتند).",
                "فیلتر ورود سخت‌تر شود (مثلاً تأیید روند بازار یا حجم) یا آستانه خرید بالاتر برود."))
            if (losses.size >= 3 && badExit >= losses.size / 3.0) out.add(Finding(false, 2, "سود به‌دست‌آمده دوباره از دست رفت",
                badExit.toString() + " معامله حداقل ۳٪ سود داشتند ولی با زیان بسته شدند.",
                "حد ضرر متحرک/قفل سود زودتر فعال شود (مثلاً انتقال حد ضرر به نقطه سربه‌سر بعد از ۳٪ سود)."))
            if (gaveBack >= 2) out.add(Finding(false, 1, "در معاملات سودده، بخش زیادی از سود پس داده شد",
                gaveBack.toString() + " معامله سودده کمتر از ۴۰٪ از بیشترین سود شناورشان را نگه داشتند.",
                "فاصله حد ضرر متحرک کمتر شود."))
            val stopExits = v.filter { Performance.exitCategory(it.first.exitReason) == "حد ضرر" && it.second.afterExitPct != null }
            if (stopExits.size >= 3) {
                val recovered = stopExits.count { (it.second.afterExitPct ?: 0.0) >= 3 }
                if (recovered >= stopExits.size / 2.0) out.add(Finding(false, 1, "حد ضرر زود فعال شد",
                    "بعد از " + recovered + " از " + stopExits.size + " فروش با حد ضرر، قیمت دوباره حداقل ۳٪ بالا رفت.",
                    "حد ضرر کمی بازتر (بر اساس نوسان) یا ورود در قیمت بهتر."))
                else out.add(Finding(true, 0, "حد ضرر به‌جا بود",
                    "بعد از بیشتر فروش‌های با حد ضرر (" + (stopExits.size - recovered) + " از " + stopExits.size + ") قیمت برنگشت."))
            }
            val closed = v.map { it.first }
            val fees = closed.sumOf { it.buyFeeUsd + (it.sellFeeUsd ?: 0.0) }
            val gross = closed.sumOf { maxOf(it.pnlUsd ?: 0.0, 0.0) }
            if (gross > 0 && fees > gross * 0.5) out.add(Finding(false, 1, "کارمزد بخش بزرگی از سود را خورد",
                "کارمزد کل $" + f(fees) + " در برابر جمع سودها $" + f(gross) + ".",
                "معامله کمتر ولی با هدف سود بزرگ‌تر."))
            for (m in MarketKind.values()) {
                val mm = v.filter { it.first.market == m }
                if (mm.size < 5) continue
                val wr = mm.count { it.second.right }.toDouble() / mm.size
                val pnl = mm.sumOf { it.first.pnlUsd ?: 0.0 }
                if (wr < 0.4 && pnl < 0) out.add(Finding(false, 2, "بازار " + m.faTitle + " زیان‌ده بوده",
                    mm.size.toString() + " معامله، نرخ برد " + pc(wr) + "، جمع $" + f(pnl) + ".",
                    "بازبینی پارامترهای این بازار (بک‌تست تازه) یا کاهش سهم سرمایه آن."))
                else if (pnl > 0) out.add(Finding(true, 0, "بازار " + m.faTitle + " سودده بوده",
                    mm.size.toString() + " معامله، نرخ برد " + pc(wr) + "، جمع $" + f(pnl) + "."))
            }
        }

        // ۵) کیفیت داده
        val recent = cycles.takeLast(100)
        if (recent.size >= 10) {
            for (m in MarketKind.values()) {
                val withSim = recent.count { (it.sim?.get(m.name) ?: 0) > 0 && (it.real?.get(m.name) ?: 0) == 0 }
                if (withSim >= recent.size / 5) out.add(Finding(false, 2, "داده واقعی " + m.faTitle + " اغلب نرسید",
                    "در " + withSim + " از " + recent.size + " دور اخیر، این بازار فقط داده شبیه‌سازی‌شده داشت (معامله نمی‌شود).",
                    "بررسی منبع داده این بازار."))
            }
            val avgMs = recent.map { it.ms }.average()
            if (avgMs > 90_000) out.add(Finding(false, 1, "هر دور بررسی کند است",
                "میانگین " + f(avgMs / 1000, 0) + " ثانیه در " + recent.size + " دور اخیر.", "کاهش درخواست‌های شبکه در هر دور."))
        }
        for (i in issues.filter { it.count >= 20 }.take(5)) {
            out.add(Finding(false, 1, "مشکل تکراری (" + i.area + ")", i.message + " — " + i.count + " بار."))
        }
        // ۶) کمبود داده برای قضاوت
        if (ev1.size < MIN_GROUP) out.add(Finding(null, 0, "هنوز داده کافی برای قضاوت نیست",
            "پیش‌بینی‌ها بعد از ۲۴ و ۷۲ ساعت ارزیابی می‌شوند؛ تا الان " + ev1.size + " مورد ارزیابی شده. چند روز بعد گزارش دقیق‌تر می‌شود."))
        return out
    }
}
