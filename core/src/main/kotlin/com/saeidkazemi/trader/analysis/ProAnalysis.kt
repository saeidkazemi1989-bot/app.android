package com.saeidkazemi.trader.analysis

import com.saeidkazemi.trader.data.model.PricePoint
import com.saeidkazemi.trader.data.model.ProFactor
import com.saeidkazemi.trader.util.Format
import kotlin.math.abs

/**
 * تحلیل تخصصی — عواملی که در نمودار شمعی دیده نمی‌شوند و معامله‌گران حرفه‌ای به آن نگاه می‌کنند.
 *
 * **بورس تهران**
 * 1. *قدرت خریدار حقیقی* (سرانه خرید هر کد حقیقی ÷ سرانه فروش): بالای ۱٫۵ یعنی خریداران «پول درشت»اند
 *    و فروشندگان خرده؛ زیر ۰٫۷ یعنی پول درشت در حال خروج است.
 * 2. *ورود/خروج پول حقیقی امروز* (از حقوقی به حقیقی یا برعکس).
 * 3. *جریان پول هوشمند چندروزه*: جمع ورود/خروج پول حقیقی و میانگین قدرت خریدار ۵ روز اخیر؛
 *    خروج مستمر ≥۴ روز از ۵ روز همراه با قدرت فروشنده امروز → **توقف خرید**.
 * 4. *حجم مشکوک*: حجم امروز ≥۲ برابر میانگین ۲۰ روز؛ جهت قیمت تعیین می‌کند مثبت است یا منفی.
 * 5. *ارزش‌گذاری*: P/E سهم در برابر میانه P/E هم‌گروه‌ها؛ شرکت زیان‌ده جریمه می‌شود.
 * 6. *وضعیت کل بازار*: درصد نمادهای مثبت و ورود/خروج پول حقیقی کل بازار (در بازار ریزشی، اکثر سهم‌ها
 *    با هم می‌ریزند — خرید متوقف می‌شود).
 *
 * **ارز دیجیتال**
 * 1. *شاخص ترس و طمع* به‌صورت خلاف جهت جمعیت.
 * 2. *فیلتر بیت‌کوین*: آلت‌کوین‌ها با بیت‌کوین حرکت می‌کنند؛ وقتی بیت‌کوین زیر میانگین ۵۰ روزه و در
 *    ریزش است، خرید آلت‌کوین متوقف می‌شود.
 * 3. *قدرت نسبی در برابر بیت‌کوین* (بازده ۳۰ روزه منهای بازده بیت‌کوین).
 * 4. *حجم غیرعادی* معاملات روزانه.
 * 5. *عدم تعادل دفتر سفارش* (فشار خرید/فروش نزدیک قیمت).
 *
 * خروجی: تعدیل امتیاز محدود به ±۲۰، دلایل فارسی و در صورت لزوم توقف خرید.
 */
object ProAnalysis {

    const val MAX_ADJ = 20

    /** یک روز معاملات حقیقی (حجم/ارزش/تعداد کد) — مستقل از منبع داده. */
    data class FlowDay(
        val date: Int,
        val buyIVol: Double,
        val sellIVol: Double,
        val buyNVol: Double,
        val buyICount: Double,
        val sellICount: Double,
        val netRealValueIrr: Double
    ) {
        val buyerPower: Double?
            get() = if (buyICount > 0 && sellICount > 0 && buyIVol > 0 && sellIVol > 0)
                (buyIVol / buyICount) / (sellIVol / sellICount) else null
        val realNetShare: Double?
            get() {
                val total = buyIVol + buyNVol
                return if (total > 0) (buyIVol - sellIVol) / total else null
            }
    }

    data class IranInputs(
        val today: FlowDay? = null,
        /** تاریخچه جریان پول (جدیدترین اول؛ ممکن است امروز را هم داشته باشد). */
        val history: List<FlowDay> = emptyList(),
        val pe: Double? = null,
        val eps: Double? = null,
        val sectorPe: Double? = null,
        val breadth: Double? = null,
        val marketRealNetIrr: Double? = null,
        val marketValueIrr: Double? = null,
        val usdIrr: Double = 900_000.0
    )

    data class CryptoInputs(
        val isBtc: Boolean = false,
        val fearGreed: Int? = null,
        val fearGreedLabel: String? = null,
        val btcHistory: List<PricePoint> = emptyList(),
        val bidShare: Double? = null
    )

    data class Result(
        val adj: Int,
        val factors: List<ProFactor>,
        val blockBuy: Boolean,
        val blockReason: String?
    ) {
        val reasons: List<String>
            get() = factors.filter { it.impact != 0 || it.note.isNotEmpty() }
                .map { f -> f.title + ": " + f.value + (if (f.note.isNotEmpty()) " — " + f.note else "") }

        companion object {
            val NONE = Result(0, emptyList(), false, null)
        }
    }

    private fun result(factors: List<ProFactor>, block: String?): Result {
        val raw = factors.sumOf { it.impact }
        return Result(raw.coerceIn(-MAX_ADJ, MAX_ADJ), factors, block != null, block)
    }

    // ------------------------------------------------------------------ بورس تهران

    fun iran(history: List<PricePoint>, inp: IranInputs): Result {
        val f = mutableListOf<ProFactor>()
        var block: String? = null

        // ۱ و ۲) قدرت خریدار و ورود پول حقیقی امروز
        val today = inp.today
        val todayActive = today != null && (today.buyICount + today.sellICount) >= 20
        if (today != null && todayActive) {
            val bp = today.buyerPower
            if (bp != null) {
                val impact = when {
                    bp >= 2.0 -> 6
                    bp >= 1.4 -> 4
                    bp >= 1.1 -> 1
                    bp <= 0.5 -> -6
                    bp <= 0.75 -> -3
                    else -> 0
                }
                f += ProFactor(
                    "قدرت خریدار حقیقی", Format.num(bp, 2), impact,
                    when {
                        bp >= 1.4 -> "سرانه خرید حقیقی‌ها از سرانه فروش بیشتر است (ورود پول درشت)"
                        bp <= 0.75 -> "فروشندگان حقیقی درشت‌ترند (خروج پول درشت)"
                        else -> ""
                    }
                )
            }
            val share = today.realNetShare
            if (share != null) {
                val usd = today.netRealValueIrr / inp.usdIrr
                val impact = when {
                    share >= 0.20 -> 4
                    share >= 0.08 -> 2
                    share <= -0.20 -> -4
                    share <= -0.08 -> -2
                    else -> 0
                }
                f += ProFactor(
                    if (share >= 0) "ورود پول حقیقی امروز" else "خروج پول حقیقی امروز",
                    Format.pct(share * 100) + " حجم (~" + Format.num(abs(usd), 0) + " دلار)",
                    impact,
                    if (share >= 0.08) "حقوقی‌ها به حقیقی‌ها فروخته‌اند" else if (share <= -0.08) "حقیقی‌ها به حقوقی‌ها فروخته‌اند" else ""
                )
            }
        }

        // ۳) پول هوشمند چندروزه (۵ روز اخیر بدون امروز تکراری)
        // روز جاری اگر جداگانه حساب شده، در تاریخچه تکرار نشود (بعد از بسته شدن بازار، «امروز» همان آخرین روز معاملاتی است).
        val days = inp.history.filter { d ->
            today == null || !todayActive ||
                (d.date != today.date && !(d.buyIVol == today.buyIVol && d.sellIVol == today.sellIVol))
        }.take(5)
        if (days.size >= 3) {
            val inflowDays = days.count { (it.realNetShare ?: 0.0) > 0.02 }
            val outflowDays = days.count { (it.realNetShare ?: 0.0) < -0.02 }
            val net = days.sumOf { it.netRealValueIrr }
            val powers = days.mapNotNull { it.buyerPower }
            val avgPower = if (powers.isNotEmpty()) powers.average() else null
            val usd = net / inp.usdIrr
            val impact = when {
                inflowDays >= 4 && (avgPower ?: 1.0) >= 1.0 -> 5
                inflowDays >= 3 && net > 0 -> 3
                outflowDays >= 4 && (avgPower ?: 1.0) < 1.0 -> -5
                outflowDays >= 3 && net < 0 -> -3
                else -> 0
            }
            f += ProFactor(
                "جریان پول حقیقی " + days.size + " روز اخیر",
                (if (net >= 0) "ورود " else "خروج ") + Format.num(abs(usd), 0) + " دلار؛ " + inflowDays + " روز ورود، " + outflowDays + " روز خروج",
                impact,
                if (avgPower != null) "میانگین قدرت خریدار " + Format.num(avgPower, 2) else ""
            )
            val todayWeak = today?.buyerPower?.let { it < 0.8 } ?: true
            if (outflowDays >= 4 && todayWeak && net < 0) {
                block = "خروج مستمر پول حقیقی (" + outflowDays + " روز از " + days.size + " روز)؛ خرید متوقف شد"
            }
        }

        // ۴) حجم مشکوک
        volumeFactor(history)?.let { f += it }

        // ۵) ارزش‌گذاری
        val pe = inp.pe
        val sectorPe = inp.sectorPe
        val eps = inp.eps
        if (eps != null && eps < 0) {
            f += ProFactor("سودآوری", "EPS منفی", -3, "شرکت زیان‌ده است")
        } else if (pe != null && pe > 0 && sectorPe != null && sectorPe > 0) {
            val r = pe / sectorPe
            val impact = when {
                r <= 0.6 -> 3
                r <= 0.85 -> 1
                r >= 2.0 -> -3
                r >= 1.4 -> -1
                else -> 0
            }
            f += ProFactor(
                "P/E در برابر گروه", Format.num(pe, 1) + " / " + Format.num(sectorPe, 1), impact,
                if (r <= 0.85) "ارزان‌تر از هم‌گروه‌ها" else if (r >= 1.4) "گران‌تر از هم‌گروه‌ها" else ""
            )
        }

        // ۶) وضعیت کل بازار
        val breadth = inp.breadth
        if (breadth != null) {
            val mNet = inp.marketRealNetIrr
            val mVal = inp.marketValueIrr
            val netShare = if (mNet != null && mVal != null && mVal > 0) mNet / mVal else null
            val impact = when {
                breadth < 0.25 && (netShare ?: -1.0) < 0 -> -4
                breadth < 0.35 -> -2
                breadth > 0.65 && (netShare ?: 1.0) > 0 -> 2
                else -> 0
            }
            f += ProFactor(
                "وضعیت کل بازار",
                Format.num(breadth * 100, 0) + "٪ نمادها مثبت" +
                    (if (mNet != null) "؛ " + (if (mNet >= 0) "ورود " else "خروج ") + "پول حقیقی " + Format.num(abs(mNet / inp.usdIrr) / 1e6, 1) + " میلیون دلار" else ""),
                impact,
                if (impact <= -4) "بازار در فاز ریزش و خروج پول است" else ""
            )
            if (breadth < 0.15 && (netShare ?: -1.0) < 0 && block == null) {
                block = "ریزش فراگیر بازار (فقط " + Format.num(breadth * 100, 0) + "٪ نمادها مثبت)؛ خرید جدید سهام متوقف شد"
            }
        }
        return result(f, block)
    }

    // ------------------------------------------------------------------ ارز دیجیتال

    fun crypto(history: List<PricePoint>, inp: CryptoInputs): Result {
        val f = mutableListOf<ProFactor>()
        var block: String? = null

        // ۱) ترس و طمع (خلاف جهت جمعیت)
        val fg = inp.fearGreed
        if (fg != null) {
            val impact = when {
                fg <= 20 -> 4
                fg <= 35 -> 2
                fg >= 85 -> -5
                fg >= 72 -> -2
                else -> 0
            }
            f += ProFactor(
                "شاخص ترس و طمع", fg.toString() + (inp.fearGreedLabel?.let { " ($it)" } ?: ""), impact,
                when {
                    fg <= 35 -> "ترس بازار؛ معمولاً زمان خرید تدریجی"
                    fg >= 72 -> "طمع بازار؛ ریسک اصلاح بالاست"
                    else -> ""
                }
            )
        }

        // ۲ و ۳) فیلتر روند بیت‌کوین و قدرت نسبی
        val btc = inp.btcHistory.map { it.price }.toDoubleArray()
        if (btc.size >= 50) {
            val last = btc[btc.size - 1]
            val sma50 = Indicators.sma(btc, 50)
            val btcMom30 = Indicators.momentumPct(btc, 30)
            val below = sma50 != null && last < sma50
            if (!inp.isBtc) {
                val impact = when {
                    below && (btcMom30 ?: 0.0) < -8 -> -6
                    below -> -2
                    (btcMom30 ?: 0.0) > 5 -> 2
                    else -> 0
                }
                f += ProFactor(
                    "روند بیت‌کوین",
                    (if (below) "زیر" else "بالای") + " میانگین ۵۰روزه؛ بازده ۳۰روزه " + Format.pct(btcMom30 ?: 0.0),
                    impact,
                    if (impact <= -6) "آلت‌کوین‌ها معمولاً با بیت‌کوین می‌ریزند" else ""
                )
                if (below && (btcMom30 ?: 0.0) < -12) {
                    block = "بیت‌کوین در روند نزولی شدید است؛ خرید آلت‌کوین متوقف شد"
                }
                val own = history.map { it.price }.toDoubleArray()
                val ownMom = Indicators.momentumPct(own, 30)
                if (ownMom != null && btcMom30 != null) {
                    val rs = ownMom - btcMom30
                    val rsImpact = when {
                        rs >= 15 -> 4
                        rs >= 6 -> 2
                        rs <= -20 -> -3
                        rs <= -10 -> -1
                        else -> 0
                    }
                    f += ProFactor(
                        "قدرت نسبی در برابر بیت‌کوین", Format.pct(rs), rsImpact,
                        if (rs >= 6) "قوی‌تر از بازار" else if (rs <= -10) "ضعیف‌تر از بازار" else ""
                    )
                }
            }
        }

        // ۴) حجم غیرعادی
        volumeFactor(history)?.let { f += it }

        // ۵) دفتر سفارش
        val bs = inp.bidShare
        if (bs != null) {
            val impact = when {
                bs >= 0.68 -> 3
                bs >= 0.58 -> 1
                bs <= 0.32 -> -3
                bs <= 0.42 -> -1
                else -> 0
            }
            f += ProFactor(
                "فشار دفتر سفارش", Format.num(bs * 100, 0) + "٪ خرید", impact,
                if (bs >= 0.58) "سفارش خرید نزدیک قیمت بیشتر است" else if (bs <= 0.42) "سفارش فروش سنگین‌تر است" else ""
            )
        }
        return result(f, block)
    }

    /**
     * حجم غیرعادی: حجم آخرین روز (یا روز قبل اگر امروز هنوز کامل نشده) در برابر میانگین ۲۰ روز قبل.
     * حجم بالا + قیمت مثبت = ورود پول (مثبت)؛ حجم بالا + قیمت منفی = تخلیه (منفی).
     */
    fun volumeFactor(history: List<PricePoint>): ProFactor? {
        if (history.size < 25) return null
        val vols = history.map { it.volume }
        if (vols.takeLast(22).count { it > 0 } < 15) return null
        val n = history.size
        val base = vols.subList(n - 22, n - 2).filter { it > 0 }
        if (base.size < 10) return null
        val avg = base.average()
        if (avg <= 0) return null
        // اگر امروز هنوز کامل نشده، روز قبل را هم بررسی کن و بیشترین نسبت را بگیر.
        val rToday = vols[n - 1] / avg
        val rPrev = vols[n - 2] / avg
        val useToday = rToday >= rPrev
        val ratio = if (useToday) rToday else rPrev
        val i = if (useToday) n - 1 else n - 2
        val chg = (history[i].price / history[i - 1].price - 1) * 100
        if (ratio < 1.8) {
            return if (ratio < 0.4 && history.last().price > 0) ProFactor("حجم معاملات", Format.num(ratio, 1) + "× میانگین", 0, "حجم خیلی کم؛ نقدشوندگی ضعیف") else null
        }
        val impact = when {
            chg >= 1.0 -> if (ratio >= 3) 5 else 3
            chg <= -1.0 -> if (ratio >= 3) -5 else -3
            else -> 0
        }
        return ProFactor(
            "حجم غیرعادی", Format.num(ratio, 1) + "× میانگین ۲۰روزه (قیمت " + Format.pct(chg) + ")", impact,
            when {
                impact > 0 -> "ورود پول با حجم بالا"
                impact < 0 -> "فروش سنگین با حجم بالا"
                else -> ""
            }
        )
    }

    /** برای نمایش: مجموع اثر به صورت «+۵». */
    fun signed(v: Int): String = (if (v > 0) "+" else "") + v
}
