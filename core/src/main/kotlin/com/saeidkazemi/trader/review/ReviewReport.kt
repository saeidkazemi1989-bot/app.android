package com.saeidkazemi.trader.review

import com.google.gson.GsonBuilder
import com.saeidkazemi.trader.analysis.Performance
import com.saeidkazemi.trader.core.AppContainer
import com.saeidkazemi.trader.core.AppVersion
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.review.SelfReview.f
import com.saeidkazemi.trader.review.SelfReview.pc
import com.saeidkazemi.trader.review.SelfReview.sp
import com.saeidkazemi.trader.util.Format
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * گزارش کامل خودارزیابی برای تحلیل و رفع ایراد: «کجا درست فکر کردم، کجا اشتباه»، دقت پیش‌بینی‌ها،
 * اثر هر عامل، کالبدشکافی معاملات، مشکلات فنی و داده‌ها، تنظیمات و یک بخش JSON قابل‌پردازش.
 * همیشه روی دستگاهی ساخته می‌شود که معامله می‌کند (دستگاه اصلی). توکن نوبیتکس هرگز داخل گزارش نیست.
 */
object ReviewReport {

    private val iso = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Tehran") }
    private fun t(ts: Long?): String = if (ts == null || ts <= 0) "—" else iso.format(Date(ts))

    /** یافته‌های اضافه از وضعیت فعلی ربات (بازار بدون معامله، اختلاف با بک‌تست). */
    fun extraFindings(c: AppContainer): List<Finding> {
        val out = ArrayList<Finding>()
        val settings = c.store.loadSettings()
        val now = System.currentTimeMillis()
        for (a in c.tradeEngine.lastActivity) {
            if ((settings.allocations[a.market.name] ?: 0.0) <= 0) continue
            val last = a.lastTradeAt ?: 0L
            val observed = now - c.review.since() > 3 * ReviewLog.H24
            if (a.positions == 0 && observed && (last == 0L || now - last > 3 * ReviewLog.H24)) {
                out.add(Finding(false, 1, "بازار " + a.market.faTitle + " چند روز است معامله نکرده",
                    a.status + (a.bestSymbol?.let { " (بهترین: $it با امتیاز " + a.bestScore + "، آستانه " + a.threshold + ")" } ?: ""),
                    "آستانه/فیلتر این بازار بررسی شود؛ اگر بازار نزولی است این رفتار درست است."))
            }
        }
        val bt = c.tradeEngine.backtest
        val journal = c.tradeEngine.journal.all().filter { !it.isOpen && !it.backfilled && it.auto }
        for (r in bt?.results.orEmpty()) {
            val live = journal.filter { it.market == r.market && it.openedAt >= (bt?.createdAt ?: 0L) }
            if (live.size < 8) continue
            val wr = live.count { it.isWin }.toDouble() / live.size * 100
            val btWr = (if (r.applied) r.bestOut else null)?.winRate ?: r.currentOut.winRate
            if (btWr - wr > 15) out.add(Finding(false, 1, "نتیجه واقعی " + r.market.faTitle + " از بک‌تست بدتر است",
                "نرخ برد واقعی " + f(wr, 0) + "٪ (" + live.size + " معامله) در برابر " + f(btWr, 0) + "٪ در آزمون خارج از نمونه.",
                "احتمال بیش‌برازش پارامترها؛ بک‌تست با داده تازه‌تر."))
        }
        return out
    }

    private val cache = java.util.WeakHashMap<AppContainer, ReviewSummary>()

    /** خلاصه با حافظه موقت (محاسبه دوباره حداکثر هر [maxAgeMs]). */
    fun cachedSummary(c: AppContainer, maxAgeMs: Long = 30 * 60_000L, force: Boolean = false): ReviewSummary {
        synchronized(cache) {
            val old = cache[c]
            if (!force && old != null && System.currentTimeMillis() - old.createdAt < maxAgeMs) return old
        }
        val s = summary(c)
        synchronized(cache) { cache[c] = s }
        return s
    }

    fun summary(c: AppContainer): ReviewSummary {
        val log = c.review
        return SelfReview.summary(log.predictions(), c.tradeEngine.journal.all(), log.issues(), log.cycles(), log.since(), extraFindings(c))
    }

    fun build(c: AppContainer, device: String, maxPredRows: Int = 500): String {
        c.review.maybeSave(force = true)
        val log = c.review
        val preds = log.predictions()
        val issues = log.issues()
        val cycles = log.cycles()
        val journal = c.tradeEngine.journal.all()
        val settings = c.store.loadSettings()
        val acc = c.broker.account()
        val sum = SelfReview.summary(preds, journal, issues, cycles, log.since(), extraFindings(c))
        val e = SelfReview.evaluate(preds)
        val sb = StringBuilder()
        fun line(s: String = "") { sb.append(s).append('\n') }
        fun h(s: String) { line(); line("## " + s) }

        line("# گزارش خودارزیابی معامله‌یار (برای تحلیل و رفع ایراد)")
        line("نسخه " + AppVersion.NAME + " (کد " + AppVersion.CODE + ") • دستگاه: " + device + " • زمان: " + t(System.currentTimeMillis()) + " تهران")
        line("بازه ثبت: از " + t(log.since()) + " • پیش‌بینی ثبت‌شده: " + preds.size + " (ارزیابی ۲۴ساعته: " + sum.evaluated1 + "، ۷۲ساعته: " + sum.evaluated3 + ")")
        line("حالت: " + (if (settings.realTrading) "واقعی (نوبیتکس)" else "دمو") + " • معامله خودکار: " + (if (settings.autoTrade) "روشن" else "خاموش"))

        // ---- حساب
        h("حساب")
        val prices = HashMap<String, Double>()
        val assets = c.marketDataService.cachedAssets().associateBy { it.id }
        for (p in acc.positions) {
            val a = assets[p.assetId] ?: continue
            val v = c.tradeEngine.usdPriceFor(a, settings, acc.positions)
            if (v.isFinite() && v > 0) prices[p.assetId] = v
        }
        val posValue = acc.positions.sumOf { (prices[it.assetId] ?: it.avgBuyUsd) * it.qty }
        val equity = acc.cashUsd + posValue
        line("سرمایه اولیه $" + f(acc.initialCapitalUsd) + " • ارزش فعلی $" + f(equity) + " (" + sp(if (acc.initialCapitalUsd > 0) (equity / acc.initialCapitalUsd - 1) * 100 else null) + ") • نقد $" + f(acc.cashUsd) + " • سود تحقق‌یافته $" + f(acc.realizedPnlUsd))
        for (m in MarketKind.TRADED) {
            val cap = acc.capitalByMarket[m.name] ?: 0.0
            if (cap <= 0) continue
            val pv = acc.positions.filter { it.market == m }.sumOf { (prices[it.assetId] ?: it.avgBuyUsd) * it.qty }
            val eq = (acc.cashByMarket[m.name] ?: 0.0) + pv
            line("- " + m.faTitle + ": سرمایه $" + f(cap) + " → $" + f(eq) + " (" + sp((eq / cap - 1) * 100) + ")، خرید باز " + acc.positions.count { it.market == m })
        }
        val perf = Performance.report(journal, settings)
        line("معاملات بسته: " + perf.all.closed + " • نرخ برد " + pc(perf.all.winRate) + " • ضریب سود " + f(perf.all.profitFactor) + " • جمع $" + f(perf.all.totalPnlUsd) +
            " • میانگین سود " + sp(perf.all.avgWinPct) + " / زیان " + sp(perf.all.avgLossPct))

        // ---- یافته‌ها
        h("کجا درست فکر کردم")
        val fl = sum.findings.orEmpty()
        fl.filter { it.good == true }.ifEmpty { null }?.forEach { line("✔ " + it.title + " — " + it.detail) } ?: line("(هنوز موردی با داده کافی نیست)")
        h("کجا اشتباه کردم (و پیشنهاد اصلاح)")
        fl.filter { it.good == false }.ifEmpty { null }?.forEach {
            line("✘ " + (if (it.severity >= 2) "[مهم] " else "") + it.title + " — " + it.detail + (it.fix?.let { x -> " ⇐ اصلاح: $x" } ?: ""))
        } ?: line("(هنوز موردی با داده کافی نیست)")
        fl.filter { it.good == null }.forEach { line("• " + it.title + " — " + it.detail) }

        // ---- دقت پیش‌بینی
        h("دقت پیش‌بینی‌ها (همه دارایی‌های بررسی‌شده، نه فقط خریدها)")
        line("سیگنال خرید (امتیاز ≥ آستانه): ۲۴ساعت n=" + sum.buyN1 + " بالا رفت " + pc(sum.buyHit1) + " میانگین " + sp(sum.buyAvg1) + " نسبی " + sp(sum.buyExcess1) +
            " | ۷۲ساعت n=" + sum.buyN3 + " بالا رفت " + pc(sum.buyHit3) + " میانگین " + sp(sum.buyAvg3) + " نسبی " + sp(sum.buyExcess3))
        line("سیگنال فروش (امتیاز ≤ آستانه فروش): ۲۴ساعت n=" + sum.sellN1 + " پایین رفت " + pc(sum.sellHit1))
        line("نرخ پایه (همه): ۲۴ساعت بالا رفت " + pc(sum.baseUp1) + " میانگین " + sp(sum.baseAvg1))
        line("«نسبی» = بازده منهای میانگین همان بازار در همان ۶ ساعت (اثر حرکت کل بازار حذف شده).")
        for (m in MarketKind.values()) {
            val mp = preds.filter { it.market == m.name }
            if (mp.none { it.ret1 != null }) continue
            val em = SelfReview.Eval(mp, e.ex1, e.ex3)
            line()
            line("### " + m.faTitle + " — بازده بر حسب امتیاز (بازه امتیاز | n | میانگین ۲۴س | نسبی ۲۴س | بالا رفت ۲۴س || n | میانگین ۷۲س | نسبی ۷۲س)")
            val b1 = SelfReview.scoreBuckets(em, false)
            val b3 = SelfReview.scoreBuckets(em, true)
            for (i in b1.indices) {
                val a = b1[i]; val b = b3[i]
                if (a.n == 0 && b.n == 0) continue
                line(a.label + " | " + a.n + " | " + sp(a.avg) + " | " + sp(a.excess) + " | " + pc(a.upRate) + " || " + b.n + " | " + sp(b.avg) + " | " + sp(b.excess))
            }
        }

        // ---- عوامل
        h("اثر هر عامل امتیاز (بازده نسبی ۲۴ساعته وقتی عامل مثبت بود در برابر منفی)")
        line("عامل | مثبت: n / نسبی / بالا رفت | منفی: n / نسبی / بالا رفت | اختلاف | نتیجه")
        for (fs in SelfReview.factors(e)) {
            if (fs.pos.n == 0 && fs.neg.n == 0) continue
            line(fs.name + " | " + fs.pos.n + " / " + sp(fs.pos.excess) + " / " + pc(fs.pos.upRate) + " | " + fs.neg.n + " / " + sp(fs.neg.excess) + " / " + pc(fs.neg.upRate) +
                " | " + sp(fs.diff) + " | " + fs.verdict)
        }

        // ---- کالبدشکافی معاملات
        h("کالبدشکافی معاملات بسته (جدیدترین اول)")
        val verdicts = SelfReview.verdicts(journal, preds)
        sum.verdictCounts.orEmpty().entries.sortedByDescending { it.value }.forEach { line("- " + it.key + ": " + it.value) }
        line()
        line("تاریخ خرید | نماد | بازار | امتیاز (تکنیکال/خبر/تخصصی/آستانه) | علت فروش | نتیجه٪ | بیشترین سود/زیان شناور | مدت | بعد از فروش | داوری")
        for ((je, tv) in verdicts.take(120)) {
            line(
                t(je.openedAt) + " | " + je.symbol + " | " + je.market.faTitle + " | " + (je.score ?: "—") + " (" + (je.technicalScore ?: "—") + "/" + je.newsAdj + "/" + je.proAdj + "/" + (je.threshold ?: "—") + ")" +
                    " | " + Performance.exitCategory(je.exitReason) + " | " + sp(je.pnlPct) + " ($" + f(je.pnlUsd) + ")" +
                    " | " + sp(je.maxGainPct, 1) + " / " + sp(je.maxDrawPct, 1) + " | " + com.saeidkazemi.trader.journal.JournalExport.duration(je.holdMs) +
                    " | " + sp(tv.afterExitPct, 1) + " | " + tv.verdict
            )
            val top = je.proFactors.orEmpty().filter { it.impact != 0 }.sortedByDescending { kotlin.math.abs(it.impact) }.take(4)
            if (top.isNotEmpty()) line("    عوامل: " + top.joinToString("، ") { it.title + " " + (if (it.impact > 0) "+" else "") + it.impact })
        }

        // ---- خریدهای باز
        h("خریدهای باز")
        for (p in acc.positions) {
            val cur = prices[p.assetId]
            val je = journal.firstOrNull { it.assetId == p.assetId && it.isOpen }
            line(
                p.symbol + " (" + p.market.faTitle + ") خرید " + t(p.openedAt) + " @ $" + Format.price(p.avgBuyUsd) + " • الان " + (cur?.let { "$" + Format.price(it) } ?: "—") +
                    " (" + sp(cur?.let { (it / p.avgBuyUsd - 1) * 100 }) + ") • حد ضرر $" + Format.price(p.stopLossUsd) + " • حد سود $" + Format.price(p.takeProfitUsd) +
                    (if (p.profitLockedPct > 0) " • قفل سود " + f(p.profitLockedPct, 0) + "٪" else "") + (je?.score?.let { " • امتیاز ورود $it" } ?: "") +
                    (if (p.peakUsd > 0 && p.avgBuyUsd > 0) " • قله " + sp((p.peakUsd / p.avgBuyUsd - 1) * 100) + (if (p.peakAt > 0) " (" + t(p.peakAt) + ")" else "") else "") +
                    (if (p.topUps > 0) " • افزوده‌شده " + p.topUps + " بار" else "") +
                    (if (p.stopHitAt > 0) " • زیر حد ضرر از " + t(p.stopHitAt) + (if (p.stopHitOpen) " (بازار باز)" else " (بازار بسته)") else "")
            )
        }
        if (acc.positions.isEmpty()) line("(ندارد)")

        // ---- فعالیت ربات
        h("وضعیت ربات در هر بازار (آخرین دور)")
        for (a in c.tradeEngine.lastActivity) {
            line("- " + a.market.faTitle + ": " + a.status + " • آستانه " + a.threshold + " • سیگنال خرید " + a.buySignals + " • بهترین " + (a.bestSymbol ?: "—") + " " + (a.bestScore ?: "") +
                " • آخرین معامله " + t(a.lastTradeAt))
            a.details.take(6).forEach { line("    " + it) }
        }

        // ---- مشکلات فنی
        h("مشکلات فنی و داده‌ای (تکرارشونده)")
        if (issues.isEmpty()) line("(موردی ثبت نشده)")
        val (act, old) = issues.partition { SelfReview.isActive(it) }
        if (act.isNotEmpty()) line("فعال (در ۲ ساعت اخیر هم تکرار شده):")
        for (i in act.take(30)) line("- [" + i.area + "] ×" + i.count + " (اولین " + t(i.firstAt) + "، آخرین " + t(i.lastAt) + "): " + i.message)
        if (old.isNotEmpty()) line("برطرف‌شده / دیگر تکرار نشده:")
        for (i in old.take(20)) line("- [" + i.area + "] ×" + i.count + " (اولین " + t(i.firstAt) + "، آخرین " + t(i.lastAt) + "): " + i.message)
        c.store.lastWriteError?.let { line("- [ذخیره‌سازی] آخرین خطای ذخیره: $it") }
        val recent = cycles.takeLast(100)
        if (recent.isNotEmpty()) {
            line()
            line("دورهای اخیر: " + recent.size + " دور • میانگین مدت " + f(recent.map { it.ms }.average() / 1000, 0) + " ثانیه • میانگین دارایی " + f(recent.map { it.assets }.average(), 0) +
                " • خرید " + recent.sumOf { it.buys } + " • فروش " + recent.sumOf { it.sells } + " • آخرین دور " + t(recent.last().ts))
            for (m in MarketKind.values()) {
                val r = recent.map { it.real?.get(m.name) ?: 0 }.average()
                val s = recent.map { it.sim?.get(m.name) ?: 0 }.average()
                if (r > 0 || s > 0) line("- " + m.faTitle + ": میانگین داده واقعی " + f(r, 0) + "، شبیه‌سازی " + f(s, 0))
            }
        }

        // ---- بک‌تست و تنظیمات
        h("پارامترهای بک‌تست در حال استفاده")
        val bt = c.tradeEngine.backtest
        if (bt == null) line("(بک‌تست انجام نشده)")
        bt?.results?.forEach { r ->
            val p = if (r.applied) r.best else r.current
            line("- " + r.market.faTitle + (if (r.applied) " (بهینه اعمال شده)" else " (پیش‌فرض)") + ": " + (p?.let { x ->
                "آستانه " + x.threshold + " حد سود " + f(x.tpPct * 100, 1) + "٪ حد ضرر " + f(x.minStopPct * 100, 1) + "-" + f(x.maxStopPct * 100, 1) + "٪ متحرک " + f(x.trailPct * 100, 1) + "٪ مهلت " + x.maxHoldDays + " روز فیلتر " + x.filter + " نوع " + x.mode
            } ?: "—") + " • خارج از نمونه: n=" + ((if (r.applied) r.bestOut else r.currentOut)?.trades ?: 0) + " WR " + f((if (r.applied) r.bestOut else r.currentOut)?.winRate, 1) + "%")
        }
        h("آزمون ورود/خروج پله‌ای روی همان داده")
        line("سرمایه مشترک: " + (if (settings.sharedCapital) "روشن (سقف هر بازار " + f(settings.maxMarketSharePct, 0) + "٪)" else "خاموش") +
            " • دقت سیگنال خرید هر بازار (بازده نسبی ۲۴س): " + com.saeidkazemi.trader.data.model.MarketKind.TRADED.joinToString("، ") {
                it.faTitle + " " + f(c.tradeEngine.marketEdge(it), 2) + "٪"
            })
        line("بی‌ضرر کردن بعد از سود: " + (if (settings.breakEvenAfterPct <= 0) "خاموش" else f(settings.breakEvenAfterPct, 0) + "٪" +
            (if (settings.breakEvenSmart) " (فقط جایی که آزمون نشان ندهد سود را کم می‌کند)" else " (همه بازارها)")))
        for (m in com.saeidkazemi.trader.data.model.MarketKind.TRADED) {
            val w = bt?.guardWhy?.get(m.name) ?: continue
            line("- بی‌ضرر کردن " + m.faTitle + ": " + w)
        }
        line("تعداد و حجم خرید هر بازار: " + com.saeidkazemi.trader.data.model.MarketKind.TRADED.joinToString("، ") { m ->
            val pl = c.tradeEngine.planFor(settings, m)
            val manual = settings.maxPositionsByMarket?.containsKey(m.name) == true || settings.positionPctByMarket?.containsKey(m.name) == true
            m.faTitle + " " + pl.maxPositions + " خرید × " + f(pl.positionPct * 100, 0) + "٪" + (if (manual) " (دستی)" else "")
        } + " • موقعیت اضافه با نقد آزاد: " + (if (settings.extraPositions) "روشن" else "خاموش"))
        line("افزودن به خرید قبلی: " + (if (!settings.topUpWinners) "خاموش" else if (settings.topUpSmart) "روشن (جز جایی که آزمون نشان دهد ضرر دارد)" else "روشن (همه بازارها)"))
        for (m in com.saeidkazemi.trader.data.model.MarketKind.TRADED) {
            val w = bt?.topUpWhy?.get(m.name) ?: continue
            line("- افزودن " + m.faTitle + ": " + w)
        }
        line("انتخاب خودکار روش: " + (if (settings.autoScaleStyle) "روشن" else "خاموش (کلید دستی خرید پله‌ای: " + settings.scaledEntryMarkets.orEmpty().joinToString(",") + ")"))
        bt?.scaleChoice?.forEach { (k, v) ->
            val m = com.saeidkazemi.trader.data.model.MarketKind.values().firstOrNull { it.name == k }
            line("- روش انتخاب‌شده " + (m?.faTitle ?: k) + ": " + v + ") " + (bt?.scaleWhy?.get(k) ?: ""))
        }
        val sc = bt?.scale.orEmpty()
        if (sc.isEmpty()) line("(هنوز اجرا نشده؛ با بک‌تست بعدی انجام می‌شود)")
        com.saeidkazemi.trader.analysis.ScaleStudy.lines(sc).forEach { line(it) }
        h("تنظیمات")
        val gson = GsonBuilder().serializeSpecialFloatingPointValues().create()
        line(gson.toJson(settings.copy(nobitexToken = if (settings.nobitexToken.isBlank()) "" else "***")))

        // ---- JSON
        h("داده خام (JSON برای پردازش)")
        val evaluated = preds.filter { it.done1 || it.done3 }.takeLast(maxPredRows)
        val raw = linkedMapOf<String, Any?>(
            "v" to 1,
            "app" to AppVersion.NAME,
            "createdAt" to System.currentTimeMillis(),
            "predCols" to listOf("ts", "sym", "mkt", "score", "tech", "news", "pro", "th", "ret1", "ret3", "maxUp3", "maxDn3", "fcExp", "fcUp", "held", "factors"),
            "preds" to evaluated.map { p ->
                listOf(p.ts, p.symbol, p.market, p.score, p.tech, p.news, p.pro, p.th, r2(p.ret1), r2(p.ret3), r2(p.maxUp3), r2(p.maxDown3), r2(p.fcExp), r2(p.fcUp), p.held, p.factors)
            },
            "trades" to verdicts.take(150).map { (je, tv) ->
                linkedMapOf(
                    "sym" to je.symbol, "mkt" to je.market.name, "open" to je.openedAt, "close" to je.closedAt, "score" to je.score, "tech" to je.technicalScore,
                    "news" to je.newsAdj, "pro" to je.proAdj, "th" to je.threshold, "exit" to je.exitReason, "pnlPct" to r2(je.pnlPct), "pnlUsd" to r2(je.pnlUsd),
                    "mfe" to r2(je.maxGainPct), "mae" to r2(je.maxDrawPct), "fees" to r2(je.buyFeeUsd + (je.sellFeeUsd ?: 0.0)), "after" to r2(tv.afterExitPct),
                    "verdict" to tv.verdict, "fcExp" to r2(je.forecastExpPct), "fcUp" to r2(je.forecastProbUp),
                    "factors" to je.proFactors.orEmpty().filter { it.impact != 0 }.associate { it.title to it.impact }
                )
            },
            "issues" to issues.take(60),
            "cycles" to cycles.takeLast(40)
        )
        line(gson.toJson(raw))
        return sb.toString()
    }

    private fun r2(v: Double?): Double? = if (v == null || !v.isFinite()) null else Math.round(v * 100) / 100.0

    /** خلاصه کوتاه (بدون JSON) برای کپی. */
    fun shortText(full: String): String = full.substringBefore("\n## داده خام")
}
