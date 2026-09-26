package com.saeidkazemi.trader.journal

import com.saeidkazemi.trader.analysis.PerfReport
import com.saeidkazemi.trader.analysis.PerfStats
import com.saeidkazemi.trader.data.model.JournalEntry
import com.saeidkazemi.trader.util.Format

/** ساخت متن خوانا و CSV از ژورنال معاملات (برای نمایش، کپی و بایگانی). */
object JournalExport {

    data class Section(val title: String, val lines: List<String>)

    fun duration(ms: Long): String {
        val m = ms / 60_000
        val h = m / 60
        val d = h / 24
        return when {
            d > 0 -> "$d روز و ${h % 24} ساعت"
            h > 0 -> "$h ساعت و ${m % 60} دقیقه"
            else -> "${maxOf(m, 0)} دقیقه"
        }
    }

    private fun usd(v: Double) = "$" + Format.price(v)

    private fun native(e: JournalEntry, v: Double?): String? {
        if (v == null || e.nativeCurrency != "IRR") return null
        return Format.num(v, 0) + " ریال"
    }

    private fun rel(price: Double, base: Double): String =
        if (base > 0) " (" + Format.pct((price / base - 1) * 100) + ")" else ""

    fun sections(e: JournalEntry): List<Section> {
        val out = ArrayList<Section>()
        val entry = ArrayList<String>()
        entry.add("زمان: " + Format.dateTime(e.openedAt) + " • " + (if (e.auto) "خودکار" else "دستی") + " • " + (if (e.mode == "REAL") "واقعی" else "دمو"))
        entry.add("قیمت: " + usd(e.entryUsd) + (native(e, e.entryNative)?.let { " ≈ $it" } ?: "") +
            (if (e.usdIrr > 0 && e.nativeCurrency == "IRR") " (دلار " + Format.num(e.usdIrr, 0) + " ریال)" else ""))
        entry.add("مبلغ: " + "$" + Format.money(e.amountUsd) + " • کارمزد خرید $" + Format.money(e.buyFeeUsd) + " • تعداد " + Format.price(e.qty))
        entry.add("دلیل: " + e.entryReason)
        if (e.backfilled) entry.add("این ردیف از تاریخچه معاملات قبل از ژورنال ساخته شده؛ جزئیات تحلیل لحظه خرید در دسترس نیست.")
        if (e.guardNote != null) entry.add("محافظ نرخ برد: " + e.guardNote)
        out.add(Section("ورود", entry))

        if (e.score != null) {
            val sc = ArrayList<String>()
            sc.add(
                "تکنیکال " + (e.technicalScore ?: e.score) + " " + signed(e.newsAdj) + " (اخبار) " + signed(e.proAdj) +
                    " (تخصصی) = " + e.score + (e.threshold?.let { " — آستانه خرید $it" } ?: "")
            )
            e.reasons.orEmpty().forEach { sc.add("• $it") }
            out.add(Section("امتیاز و دلایل موتور", sc))
        }
        val pf = e.proFactors.orEmpty()
        if (pf.isNotEmpty()) {
            out.add(Section("تحلیل تخصصی", pf.map { f ->
                "• " + f.title + ": " + f.value + " (" + signed(f.impact) + ")" + (if (f.note.isNotBlank()) " — " + f.note else "")
            }))
        }
        e.metrics?.let { m ->
            val parts = ArrayList<String>()
            m.rsi?.let { parts.add("RSI " + Format.num(it, 1)) }
            m.macdHist?.let { parts.add("MACD " + (if (it >= 0) "مثبت" else "منفی")) }
            m.trendPct?.let { parts.add("روند EMA " + Format.pct(it)) }
            m.momentum7?.let { parts.add("بازده ۷ روز " + Format.pct(it)) }
            m.momentum30?.let { parts.add("بازده ۳۰ روز " + Format.pct(it)) }
            m.volatility?.let { parts.add("نوسان روزانه " + Format.num(it, 2) + "٪") }
            if (parts.isNotEmpty()) out.add(Section("اندیکاتورها", listOf(parts.joinToString(" • "))))
        }
        if (e.newsLabel != null || !e.newsHeadlines.isNullOrEmpty()) {
            val n = ArrayList<String>()
            e.newsLabel?.let { n.add(it + " (اثر " + signed(e.newsAdj) + ")") }
            e.newsHeadlines.orEmpty().forEach { n.add("• $it") }
            if (!e.newsSourcesFailed.isNullOrEmpty()) n.add("در دسترس نبود: " + e.newsSourcesFailed.joinToString("، "))
            out.add(Section("اخبار", n))
        }
        if (e.forecastExpPct != null) {
            out.add(Section("پیش‌بینی لحظه خرید", listOf(
                "۷ روز: " + Format.pct(e.forecastExpPct) + (e.forecastProbUp?.let { " • احتمال بالاتر رفتن " + Format.num(it * 100, 0) + "٪" } ?: "")
            )))
        }
        if (e.stopUsd > 0 || e.takeProfitUsd > 0) {
            out.add(Section("مدیریت ریسک", listOf(
                "حد ضرر " + usd(e.stopUsd) + rel(e.stopUsd, e.entryUsd) + " • حد سود " + usd(e.takeProfitUsd) + rel(e.takeProfitUsd, e.entryUsd) +
                    (if (e.trailPct > 0) " • حد ضرر متحرک " + Format.num(e.trailPct * 100, 0) + "٪ زیر قله" else "") +
                    (e.riskLevel?.let { " • ریسک " + riskFa(it) } ?: "")
            )))
        }
        if (!e.dataSources.isNullOrEmpty()) out.add(Section("منابع اطلاعات", e.dataSources.map { "• $it" }))

        if (e.closedAt != null) {
            val x = ArrayList<String>()
            x.add("زمان: " + Format.dateTime(e.closedAt) + " • مدت نگهداری " + duration(e.holdMs))
            e.exitUsd?.let { x.add("قیمت: " + usd(it) + rel(it, e.entryUsd) + (native(e, e.exitNative)?.let { n -> " ≈ $n" } ?: "")) }
            x.add("دلیل: " + (e.exitReason ?: "نامشخص"))
            e.exitScore?.let { x.add("امتیاز موتور در لحظه فروش: $it") }
            e.exitReasons.orEmpty().take(5).forEach { x.add("• $it") }
            if (e.profitLockedPct > 0) x.add("قفل سود فعال بود (حداقل " + Format.num(e.profitLockedPct, 0) + "٪)")
            out.add(Section("خروج", x))
            val r = ArrayList<String>()
            e.pnlUsd?.let { r.add("سود/زیان واقعی (با هر دو کارمزد): " + (if (it >= 0) "+" else "") + "$" + Format.money(it) + " (" + Format.pct(e.pnlPct) + ")") }
            val mg = e.maxGainPct
            val md = e.maxDrawPct
            if (mg != null || md != null) {
                r.add("بیشترین سود شناور " + Format.pct(mg) + " • بیشترین افت شناور " + Format.pct(md))
            }
            if (r.isNotEmpty()) out.add(Section("نتیجه", r))
        } else {
            out.add(Section("وضعیت", listOf("هنوز باز است • " + duration(e.holdMs) + " از خرید گذشته")))
        }
        return out
    }

    private fun signed(v: Int) = if (v > 0) "+$v" else if (v < 0) "$v" else "±0"

    private fun riskFa(r: String) = when (r) {
        "LOW" -> "کم"
        "HIGH" -> "زیاد"
        else -> "متوسط"
    }

    fun statsLines(s: PerfStats): List<String> {
        if (s.closed == 0) return listOf("هنوز معامله بسته‌شده‌ای نیست.")
        val l = ArrayList<String>()
        l.add("معاملات بسته‌شده: " + s.closed + " (برد " + s.wins + " • باخت " + s.losses + ")")
        l.add("نرخ برد (win rate): " + pct(s.winRate) + (s.breakEvenWinRate?.let { " • نرخ برد لازم برای سربه‌سر: " + pct(it) } ?: ""))
        l.add("سود/زیان کل: " + "$" + Format.money(s.totalPnlUsd) + " • امید ریاضی هر معامله: " + (s.expectancyUsd?.let { "$" + Format.money(it) } ?: "—") +
            " (" + Format.pct(s.expectancyPct) + ")")
        l.add("ضریب سود: " + pf(s.profitFactor) + " • نسبت میانگین سود به زیان: " + (s.payoff?.let { Format.num(it, 2) } ?: "—"))
        l.add("میانگین برد: " + Format.pct(s.avgWinPct) + " • میانگین باخت: " + Format.pct(s.avgLossPct) +
            " • بهترین: " + Format.pct(s.bestPct) + " • بدترین: " + Format.pct(s.worstPct))
        l.add("بیشترین باخت پیاپی: " + s.maxConsecLosses + " • بیشترین افت سود تحقق‌یافته: $" + Format.money(s.maxDrawdownUsd) +
            (s.avgHoldMs?.let { " • میانگین نگهداری: " + duration(it) } ?: ""))
        return l
    }

    fun pct(v: Double?) = if (v == null) "—" else Format.num(v * 100, 1) + "٪"

    fun pf(v: Double?) = when {
        v == null -> "—"
        v.isInfinite() -> "∞ (بدون باخت)"
        else -> Format.num(v, 2)
    }

    /** متن کامل ژورنال (پنل سودآوری + همه معاملات). */
    fun text(entries: List<JournalEntry>, perf: PerfReport): String {
        val sb = StringBuilder()
        sb.appendLine("ژورنال معاملات معامله‌یار — " + Format.dateTime(System.currentTimeMillis()))
        sb.appendLine()
        sb.appendLine("== پنل سودآوری ==")
        statsLines(perf.all).forEach { sb.appendLine(it) }
        perf.byMarket.forEach { (m, s) ->
            if (s.closed > 0) sb.appendLine(m.faTitle + ": " + s.closed + " معامله • نرخ برد " + pct(s.winRate) + " • $" + Format.money(s.totalPnlUsd))
        }
        perf.guards.forEach { sb.appendLine("محافظ " + it.market.faTitle + ": " + it.text) }
        sb.appendLine()
        entries.forEachIndexed { i, e ->
            sb.appendLine("== #" + (entries.size - i) + " " + e.symbol + " (" + e.market.faTitle + ") " +
                (if (e.isOpen) "[باز]" else if (e.isWin) "[برد]" else "[باخت]") + " ==")
            sections(e).forEach { sec ->
                sb.appendLine("[" + sec.title + "]")
                sec.lines.forEach { sb.appendLine("  $it") }
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun csvCell(s: String?): String {
        val v = s ?: ""
        return if (v.contains(',') || v.contains('"') || v.contains('\n')) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }

    /** CSV برای اکسل/گوگل‌شیت. */
    fun csv(entries: List<JournalEntry>): String {
        val sb = StringBuilder()
        sb.appendLine(
            "id,symbol,market,auto,mode,opened,entry_usd,entry_native,amount_usd,score,technical,news_adj,pro_adj,threshold," +
                "entry_reason,stop_usd,take_profit_usd,trail_pct,forecast_7d_pct,closed,exit_usd,exit_reason,pnl_usd,pnl_pct," +
                "max_gain_pct,max_drawdown_pct,hold_hours"
        )
        for (e in entries.sortedBy { it.openedAt }) {
            val row = listOf(
                e.id, e.symbol, e.market.name, e.auto.toString(), e.mode, Format.dateTime(e.openedAt),
                Format.raw(e.entryUsd, 8), Format.raw(e.entryNative, 4), Format.raw(e.amountUsd, 2),
                e.score?.toString(), e.technicalScore?.toString(), e.newsAdj.toString(), e.proAdj.toString(), e.threshold?.toString(),
                e.entryReason, Format.raw(e.stopUsd, 8), Format.raw(e.takeProfitUsd, 8), Format.raw(e.trailPct * 100, 1),
                e.forecastExpPct?.let { Format.raw(it, 2) },
                e.closedAt?.let { Format.dateTime(it) }, e.exitUsd?.let { Format.raw(it, 8) }, e.exitReason,
                e.pnlUsd?.let { Format.raw(it, 2) }, e.pnlPct?.let { Format.raw(it, 2) },
                e.maxGainPct?.let { Format.raw(it, 2) }, e.maxDrawPct?.let { Format.raw(it, 2) },
                Format.raw(e.holdMs / 3_600_000.0, 1)
            )
            sb.appendLine(row.joinToString(",") { csvCell(it) })
        }
        return sb.toString()
    }
}
