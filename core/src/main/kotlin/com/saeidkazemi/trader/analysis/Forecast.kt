package com.saeidkazemi.trader.analysis

import com.saeidkazemi.trader.data.model.PricePoint
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * پیش‌بینی آماری کوتاه‌مدت قیمت (پیش‌فرض ۷ روز) برای نمایش در نمودار روند.
 *
 * مدل: حرکت لگاریتمی قیمت با «رانش» (جهت) و «نوسان».
 * - نوسان = انحراف معیار بازده‌های ۶۰ دوره اخیر (تبدیل‌شده به روزانه)
 * - رانش = شیب روند ۳۰ دوره اخیر (میرا شده، چون روندها همیشه ادامه پیدا نمی‌کنند) + اثر کوچک امتیاز موتور
 *   و در هر حال حداکثر ±۰٫۳۵ برابر نوسان روزانه (تا پیش‌بینی خوش‌بینانه یا بدبینانه افراطی نشود)
 * - بازه محتمل = ۸۰٪ (۱۰٪ احتمال پایین‌تر و ۱۰٪ احتمال بالاتر)
 *
 * این یک برآورد احتمالاتی است، نه پیش‌گویی. خبر، صف، اتفاقات سیاسی و … می‌توانند آن را کاملاً بی‌اعتبار کنند.
 */
object Forecast {

    const val HORIZON_DAYS = 7
    const val MIN_POINTS = 20
    private const val DAY_MS = 86_400_000.0
    private const val Z80 = 1.2816

    data class Point(val t: Long, val mid: Double, val low: Double, val high: Double)

    data class Result(
        val now: Long,
        val current: Double,
        val horizonDays: Int,
        /** رانش لگاریتمی روزانه. */
        val muDay: Double,
        /** نوسان لگاریتمی روزانه. */
        val sigmaDay: Double,
        val points: List<Point>,
        val simulated: Boolean,
        val dataPoints: Int
    ) {
        private val sT: Double get() = sigmaDay * sqrt(horizonDays.toDouble())
        private val mT: Double get() = muDay * horizonDays

        val mid: Double get() = points.last().mid
        val low: Double get() = points.last().low
        val high: Double get() = points.last().high

        /** تغییر مورد انتظار (میانه) تا پایان افق، درصد. */
        val expectedPct: Double get() = (mid / current - 1) * 100
        val lowPct: Double get() = (low / current - 1) * 100
        val highPct: Double get() = (high / current - 1) * 100

        /** احتمال بالاتر بودن قیمت از قیمت فعلی در پایان افق. */
        val probUp: Double get() = probAbove(current)

        /** احتمال اینکه قیمت در پایان افق بالاتر از [level] باشد. */
        fun probAbove(level: Double): Double {
            if (level <= 0) return 1.0
            if (sT <= 1e-12) return if (current * exp(mT) > level) 1.0 else 0.0
            return phi((mT - ln(level / current)) / sT)
        }

        /** احتمال (تقریبی) اینکه قیمت در طول افق حداقل یک بار به [level] برسد (بالا یا پایین). */
        fun probTouch(level: Double): Double {
            if (level <= 0) return 0.0
            val s = sT
            if (s <= 1e-12) return 0.0
            val m = mT
            val sig2 = sigmaDay * sigmaDay
            return if (level >= current) {
                val b = ln(level / current)
                val e = (2 * muDay * b / sig2).coerceIn(-50.0, 50.0)
                (phi((m - b) / s) + exp(e) * phi((-b - m) / s)).coerceIn(0.0, 1.0)
            } else {
                val b = ln(current / level)
                val e = (-2 * muDay * b / sig2).coerceIn(-50.0, 50.0)
                (phi((-m - b) / s) + exp(e) * phi((-b + m) / s)).coerceIn(0.0, 1.0)
            }
        }

        /** برچسب جهت پیش‌بینی. */
        val trendLabel: String
            get() = when {
                expectedPct >= 3 -> "صعودی"
                expectedPct >= 0.8 -> "کمی صعودی"
                expectedPct <= -3 -> "نزولی"
                expectedPct <= -0.8 -> "کمی نزولی"
                else -> "خنثی (بدون جهت مشخص)"
            }

        /** میزان اعتماد (هیچ‌وقت «زیاد» نیست؛ بازار قطعی نیست). */
        val confidence: String
            get() = when {
                simulated -> "خیلی کم (داده شبیه‌سازی‌شده)"
                dataPoints < 40 || sT > 0.15 -> "کم"
                else -> "متوسط"
            }

        /** همه قیمت‌ها در ضریب ضرب می‌شوند (مثلاً تبدیل دلار به ریال برای نمایش). */
        fun scaled(f: Double): Result = copy(
            current = current * f,
            points = points.map { Point(it.t, it.mid * f, it.low * f, it.high * f) }
        )
    }

    /**
     * @param history تاریخچه قیمت (روزانه)، به همان واحد [current]
     * @param score امتیاز موتور ۰ تا ۱۰۰ (اختیاری)
     */
    fun build(
        history: List<PricePoint>,
        current: Double,
        now: Long = System.currentTimeMillis(),
        score: Int? = null,
        simulated: Boolean = false,
        horizonDays: Int = HORIZON_DAYS
    ): Result? {
        if (!current.isFinite() || current <= 0 || horizonDays <= 0) return null
        val pts = history.filter { it.price.isFinite() && it.price > 0 }.sortedBy { it.t }
        if (pts.size < MIN_POINTS) return null

        // نوسان
        val win = pts.takeLast(61)
        val rets = DoubleArray(win.size - 1) { i -> ln(win[i + 1].price / win[i].price) }
        val mean = rets.average()
        var ss = 0.0
        for (r in rets) ss += (r - mean) * (r - mean)
        val sdStep = sqrt(ss / maxOf(1, rets.size - 1))
        // فاصله متوسط نقاط به روز (سهام: تعطیلات آخر هفته را هم در بر می‌گیرد)
        val spanDays = (win.last().t - win.first().t) / DAY_MS
        val stepDays = if (spanDays > 0) spanDays / (win.size - 1) else 1.0
        val sigmaDay = maxOf(sdStep / sqrt(maxOf(stepDays, 0.05)), 0.001)

        // روند: شیب رگرسیون لگاریتم قیمت در ۳۰ دوره اخیر (بر حسب روز)
        val tw = pts.takeLast(30)
        val t0 = tw.first().t
        val xs = DoubleArray(tw.size) { (tw[it].t - t0) / DAY_MS }
        val ys = DoubleArray(tw.size) { ln(tw[it].price) }
        val xm = xs.average()
        val ym = ys.average()
        var sxy = 0.0
        var sxx = 0.0
        for (i in xs.indices) {
            sxy += (xs[i] - xm) * (ys[i] - ym)
            sxx += (xs[i] - xm) * (xs[i] - xm)
        }
        val slope = if (sxx > 1e-12) sxy / sxx else 0.0

        val signalMu = if (score != null) (score.coerceIn(0, 100) - 50) / 50.0 * 0.10 * sigmaDay else 0.0
        val cap = 0.35 * sigmaDay
        val muDay = (0.35 * slope + signalMu).coerceIn(-cap, cap)

        val points = (0..horizonDays).map { k ->
            val m = muDay * k
            val s = sigmaDay * sqrt(k.toDouble())
            Point(
                t = now + (k * DAY_MS).toLong(),
                mid = current * exp(m),
                low = current * exp(m - Z80 * s),
                high = current * exp(m + Z80 * s)
            )
        }
        return Result(now, current, horizonDays, muDay, sigmaDay, points, simulated, pts.size)
    }

    /** تابع توزیع تجمعی نرمال استاندارد. */
    fun phi(x: Double): Double {
        if (x.isNaN()) return 0.5
        // تقریب آبرامویتز-استگان برای erf (دقت ~۱e-۷)
        val z = abs(x) / sqrt(2.0)
        val t = 1.0 / (1.0 + 0.3275911 * z)
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-z * z)
        return if (x >= 0) 0.5 * (1 + y) else 0.5 * (1 - y)
    }
}

/**
 * چشم‌انداز یک موقعیت باز: مسیر قیمت از لحظه خرید، سود/زیان فعلی و پیش‌بینی چند روز آینده.
 * همه قیمت‌ها دلاری‌اند (مثل دفتر حساب).
 */
data class PositionOutlook(
    val assetId: String,
    /** مسیر قیمت از لحظه خرید تا الان (اولین نقطه = خرید، آخرین نقطه = قیمت فعلی). */
    val trend: List<PricePoint>,
    val forecast: Forecast.Result?,
    val avgBuyUsd: Double,
    val currentUsd: Double,
    /** قیمتی که فروش در آن (پس از کارمزد خرید و فروش) سود/زیان صفر می‌دهد. */
    val breakEvenUsd: Double,
    /** سود/زیان فعلی (بدون کارمزد)، درصد. */
    val pnlPct: Double,
    /** سود/زیان خالص اگر همین الان فروخته شود (پس از کارمزدها)، درصد. */
    val netPnlPct: Double,
    /** احتمال اینکه در پایان افق پیش‌بینی (پس از کارمزد) در سود باشید. */
    val probProfit: Double?,
    val probStop: Double?,
    val probTakeProfit: Double?,
    /** سود/زیان (بدون کارمزد) در پایان افق: مورد انتظار و بازه محتمل، درصد. */
    val expectedPnlPct: Double?,
    val lowPnlPct: Double?,
    val highPnlPct: Double?
) {
    companion object {
        fun build(
            assetId: String,
            avgBuyUsd: Double,
            openedAt: Long,
            currentUsd: Double,
            stopUsd: Double,
            takeProfitUsd: Double,
            buyFee: Double,
            sellFee: Double,
            historyUsd: List<PricePoint>,
            track: List<PricePoint>,
            score: Int?,
            simulated: Boolean,
            now: Long = System.currentTimeMillis()
        ): PositionOutlook {
            val trend = mergeTrend(avgBuyUsd, openedAt, currentUsd, historyUsd, track, now)
            val fc = Forecast.build(historyUsd, currentUsd, now, score, simulated)
            val k = (1 - buyFee) * (1 - sellFee)
            val be = if (k > 0) avgBuyUsd / k else avgBuyUsd
            fun pnl(p: Double) = if (avgBuyUsd > 0) (p / avgBuyUsd - 1) * 100 else 0.0
            return PositionOutlook(
                assetId = assetId,
                trend = trend,
                forecast = fc,
                avgBuyUsd = avgBuyUsd,
                currentUsd = currentUsd,
                breakEvenUsd = be,
                pnlPct = pnl(currentUsd),
                netPnlPct = if (avgBuyUsd > 0) (currentUsd * k / avgBuyUsd - 1) * 100 else 0.0,
                probProfit = fc?.probAbove(be),
                probStop = fc?.let { if (stopUsd > 0 && stopUsd < currentUsd) it.probTouch(stopUsd) else null },
                probTakeProfit = fc?.let { if (takeProfitUsd > currentUsd) it.probTouch(takeProfitUsd) else null },
                expectedPnlPct = fc?.let { pnl(it.mid) },
                lowPnlPct = fc?.let { pnl(it.low) },
                highPnlPct = fc?.let { pnl(it.high) }
            )
        }

        /**
         * مسیر قیمت از خرید تا الان: نقطه خرید، قیمت‌های روزانه بعد از خرید (برای موقعیت‌های قدیمی‌تر از ثبت مسیر)،
         * نمونه‌های ثبت‌شده در هر دور موتور، و قیمت فعلی.
         */
        fun mergeTrend(
            avgBuyUsd: Double,
            openedAt: Long,
            currentUsd: Double,
            historyUsd: List<PricePoint>,
            track: List<PricePoint>,
            now: Long
        ): List<PricePoint> {
            val out = ArrayList<PricePoint>()
            out.add(PricePoint(openedAt, avgBuyUsd))
            val sortedTrack = track.filter { it.t > openedAt && it.price > 0 && it.price.isFinite() }.sortedBy { it.t }
            val firstTrack = sortedTrack.firstOrNull()?.t ?: now
            // نقاط روزانه‌ای که بعد از خرید و قبل از شروع ثبت مسیر هستند (روز خرید خودش حذف می‌شود)
            for (p in historyUsd.sortedBy { it.t }) {
                if (p.t > openedAt + 12 * 3_600_000L && p.t < firstTrack && p.price > 0) out.add(PricePoint(p.t, p.price))
            }
            out.addAll(sortedTrack)
            if (currentUsd > 0 && currentUsd.isFinite() && (out.last().t < now || out.size == 1)) {
                out.add(PricePoint(maxOf(now, out.last().t + 1), currentUsd))
            }
            return out
        }
    }
}
