package com.saeidkazemi.trader.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saeidkazemi.trader.analysis.Forecast
import com.saeidkazemi.trader.analysis.PositionOutlook
import com.saeidkazemi.trader.data.model.PricePoint
import com.saeidkazemi.trader.ui.theme.AccentGold
import com.saeidkazemi.trader.ui.theme.LossRed
import com.saeidkazemi.trader.ui.theme.ProfitGreen
import com.saeidkazemi.trader.util.Format
import com.saeidkazemi.trader.util.Jalali

val BuyBlue = Color(0xFF64B5F6)
val ForecastPurple = Color(0xFFB388FF)
private val GridColor = Color(0x1FFFFFFF)
private val PastGrey = Color(0xFF9AA7C0)

/**
 * نمودار روند: مسیر قیمت، نقطه و خط خرید، ناحیه سود (سبز) یا زیان (قرمز) نسبت به قیمت خرید،
 * خطوط حد ضرر/حد سود و مخروط پیش‌بینی چند روز آینده (بنفش؛ خط‌چین = مسیر محتمل، ناحیه = بازه ۸۰٪).
 *
 * همه قیمت‌ها باید به یک واحد نمایشی باشند (پیش‌بینی را با [Forecast.Result.scaled] هم‌واحد کنید).
 * بخش گذشته متناسب با زمان رسم می‌شود و بخش آینده همیشه حدود یک‌چهارم عرض را می‌گیرد.
 */
@Composable
fun TrendChart(
    past: List<PricePoint>,
    forecast: Forecast.Result?,
    fmt: (Double) -> String,
    modifier: Modifier = Modifier,
    buyPrice: Double? = null,
    buyTime: Long? = null,
    stopPrice: Double? = null,
    stopLabel: String = "حد ضرر",
    takeProfit: Double? = null,
    height: Dp = 230.dp,
    compact: Boolean = false
) {
    val pts = past.filter { it.price.isFinite() && it.price > 0 }.sortedBy { it.t }
    if (pts.size < 2 && forecast == null) {
        EmptyBox("داده کافی برای نمودار وجود ندارد")
        return
    }
    val measurer = rememberTextMeasurer()
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        drawTrend(measurer, pts, forecast, fmt, buyPrice, buyTime, stopPrice, stopLabel, takeProfit, compact)
    }
}

private fun DrawScope.drawTrend(
    tm: TextMeasurer,
    pts: List<PricePoint>,
    fc: Forecast.Result?,
    fmt: (Double) -> String,
    buyPrice: Double?,
    buyTime: Long?,
    stopPrice: Double?,
    stopLabel: String,
    takeProfit: Double?,
    compact: Boolean
) {
    val labelSize = if (compact) 9.sp else 10.sp
    val left = 4f
    val right = size.width - 4f
    val top = if (compact) 6f else 16f
    val bottom = size.height - if (compact) 6f else 22f
    val chartW = right - left
    val chartH = bottom - top
    val pastFrac = if (fc != null) 0.74f else 1f
    val pastW = chartW * pastFrac
    val futureW = chartW - pastW

    val tNow = pts.lastOrNull()?.t ?: (fc?.now ?: 0L)
    val tMin0 = pts.firstOrNull()?.t ?: tNow
    val tMin = if (tNow - tMin0 < 1) tNow - 1 else tMin0

    // محدوده عمودی
    val values = ArrayList<Double>()
    pts.forEach { values.add(it.price) }
    fc?.points?.forEach { values.add(it.low); values.add(it.high) }
    buyPrice?.let { values.add(it) }
    stopPrice?.let { if (it > 0) values.add(it) }
    var min = values.minOrNull() ?: 0.0
    var max = values.maxOrNull() ?: 1.0
    val baseRange = maxOf(max - min, max * 0.002, 1e-12)
    val tpVisible = takeProfit != null && takeProfit > 0 && takeProfit <= max + baseRange * 0.35
    if (tpVisible) max = maxOf(max, takeProfit!!)
    val range0 = maxOf(max - min, max * 0.002, 1e-12)
    min -= range0 * 0.06
    max += range0 * 0.08
    val range = max - min

    fun y(v: Double): Float = (top + chartH * (1.0 - (v - min) / range)).toFloat()
    fun xPast(t: Long): Float = left + pastW * ((t - tMin).toDouble() / (tNow - tMin).toDouble()).coerceIn(0.0, 1.0).toFloat()
    fun xFuture(i: Int, n: Int): Float = left + pastW + futureW * (i.toFloat() / maxOf(1, n - 1))

    // خطوط راهنما
    for (i in 0..3) {
        val gy = top + chartH * i / 3f
        drawLine(GridColor, Offset(left, gy), Offset(right, gy), strokeWidth = 1f)
    }

    // بخش آینده
    if (fc != null) {
        drawRect(Color(0x0DB388FF), topLeft = Offset(left + pastW, top), size = Size(futureW, chartH))
        drawLine(
            Color(0x66FFFFFF), Offset(left + pastW, top), Offset(left + pastW, bottom),
            strokeWidth = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
        )
    }

    val lastPrice = pts.lastOrNull()?.price ?: fc?.current ?: 0.0
    val profitNow = buyPrice == null || lastPrice >= buyPrice
    val postColor = if (buyPrice != null) {
        if (profitNow) ProfitGreen else LossRed
    } else {
        if ((pts.lastOrNull()?.price ?: 0.0) >= (pts.firstOrNull()?.price ?: 0.0)) ProfitGreen else LossRed
    }

    // ناحیه سود/زیان نسبت به قیمت خرید
    val bt = buyTime
    if (buyPrice != null && pts.size >= 2) {
        val yb = y(buyPrice)
        val startT = bt ?: tMin
        val post = pts.filter { it.t >= startT }
        if (post.isNotEmpty()) {
            val area = Path()
            val x0 = xPast(maxOf(startT, tMin))
            area.moveTo(x0, yb)
            post.forEach { area.lineTo(xPast(it.t), y(it.price)) }
            area.lineTo(xPast(post.last().t), yb)
            area.close()
            clipRect(left = 0f, top = 0f, right = size.width, bottom = yb) {
                drawPath(area, ProfitGreen.copy(alpha = 0.22f))
            }
            clipRect(left = 0f, top = yb, right = size.width, bottom = size.height) {
                drawPath(area, LossRed.copy(alpha = 0.22f))
            }
        }
    }

    // مخروط پیش‌بینی
    if (fc != null && fc.points.size >= 2) {
        val n = fc.points.size
        val y0 = y(lastPrice)
        val cone = Path()
        fc.points.forEachIndexed { i, p ->
            val px = xFuture(i, n)
            val py = if (i == 0) y0 else y(p.high)
            if (i == 0) cone.moveTo(px, py) else cone.lineTo(px, py)
        }
        for (i in n - 1 downTo 1) cone.lineTo(xFuture(i, n), y(fc.points[i].low))
        cone.lineTo(xFuture(0, n), y0)
        cone.close()
        drawPath(cone, ForecastPurple.copy(alpha = 0.20f))
        val midPath = Path()
        fc.points.forEachIndexed { i, p ->
            val px = xFuture(i, n)
            val py = if (i == 0) y0 else y(p.mid)
            if (i == 0) midPath.moveTo(px, py) else midPath.lineTo(px, py)
        }
        drawPath(
            midPath, ForecastPurple,
            style = Stroke(width = 2.5f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f)))
        )
        drawCircle(ForecastPurple, radius = 4f, center = Offset(xFuture(n - 1, n), y(fc.mid)))
    }

    // خطوط افقی: حد سود، حد ضرر، خرید
    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
    if (tpVisible) {
        val ty = y(takeProfit!!)
        drawLine(ProfitGreen.copy(alpha = 0.55f), Offset(left, ty), Offset(right, ty), strokeWidth = 1.5f, pathEffect = dash)
        if (!compact) label(tm, "حد سود " + fmt(takeProfit), Offset(left + 4f, ty), ProfitGreen, labelSize, above = true)
    }
    if (stopPrice != null && stopPrice > 0) {
        val sy = y(stopPrice)
        drawLine(AccentGold.copy(alpha = 0.8f), Offset(left, sy), Offset(right, sy), strokeWidth = 1.5f, pathEffect = dash)
        if (!compact) label(tm, stopLabel + " " + fmt(stopPrice), Offset(left + 4f, sy), AccentGold, labelSize, above = false)
    }
    if (buyPrice != null) {
        val by = y(buyPrice)
        drawLine(BuyBlue.copy(alpha = 0.9f), Offset(left, by), Offset(right, by), strokeWidth = 2f, pathEffect = dash)
    }

    // خط قیمت: قبل از خرید خاکستری، بعد از خرید سبز/قرمز
    if (pts.size >= 2) {
        val split = bt ?: Long.MIN_VALUE
        val pre = Path()
        val post = Path()
        var preStarted = false
        var postStarted = false
        var prev: PricePoint? = null
        for (p in pts) {
            val px = xPast(p.t)
            val py = y(p.price)
            if (p.t < split) {
                if (!preStarted) { pre.moveTo(px, py); preStarted = true } else pre.lineTo(px, py)
            } else {
                if (!postStarted) {
                    val pv = prev
                    if (pv != null) post.moveTo(xPast(pv.t), y(pv.price)) else post.moveTo(px, py)
                    if (pv != null) post.lineTo(px, py)
                    postStarted = true
                } else post.lineTo(px, py)
            }
            prev = p
        }
        val stroke = Stroke(width = if (compact) 2.5f else 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        if (preStarted) drawPath(pre, PastGrey.copy(alpha = 0.8f), style = stroke)
        if (postStarted) drawPath(post, postColor, style = stroke)
    }

    // نقطه خرید
    if (buyPrice != null) {
        val bx = xPast(maxOf(bt ?: tMin, tMin))
        val by = y(buyPrice)
        drawLine(BuyBlue.copy(alpha = 0.35f), Offset(bx, top), Offset(bx, bottom), strokeWidth = 1f)
        drawCircle(Color.White, radius = if (compact) 6f else 8f, center = Offset(bx, by))
        drawCircle(BuyBlue, radius = if (compact) 4.5f else 6f, center = Offset(bx, by))
        val txt = if (compact) "خرید" else "خرید " + fmt(buyPrice)
        label(tm, txt, Offset(bx + 6f, by), BuyBlue, labelSize, above = !profitNow || lastPrice == buyPrice, bold = true)
    }

    // نقطه فعلی
    if (pts.isNotEmpty()) {
        val cx = xPast(tNow)
        val cy = y(lastPrice)
        drawCircle(postColor.copy(alpha = 0.35f), radius = 11f, center = Offset(cx, cy))
        drawCircle(postColor, radius = 5.5f, center = Offset(cx, cy))
        val pctTxt = if (buyPrice != null && buyPrice > 0) Format.pct((lastPrice / buyPrice - 1) * 100) else null
        val txt = if (compact) ("الان " + (pctTxt ?: "")) else ("الان " + fmt(lastPrice) + (if (pctTxt != null) " ($pctTxt)" else ""))
        label(tm, txt, Offset(cx, cy), postColor, labelSize, above = profitNow, bold = true, alignEnd = true)
    }

    // برچسب پیش‌بینی
    if (fc != null && !compact) {
        val t = "پیش‌بینی " + fc.horizonDays + " روز: " + Format.pct(fc.expectedPct)
        label(tm, t, Offset(right - 2f, top - 14f), ForecastPurple, labelSize, above = false, alignEnd = true, bold = true, background = false)
    }

    // محور زمان
    if (!compact && pts.size >= 2) {
        val intraday = tNow - tMin < 2 * 86_400_000L
        fun tl(t: Long): String = if (intraday) java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(t))
        else Jalali.format(t).substring(5)
        val ty = bottom + 4f
        drawSmall(tm, tl(tMin), Offset(left, ty), labelSize, PastGrey, alignEnd = false)
        if (fc != null) {
            drawSmall(tm, "امروز", Offset(left + pastW, ty), labelSize, PastGrey, center = true)
            drawSmall(tm, "+" + fc.horizonDays + " روز", Offset(right, ty), labelSize, ForecastPurple, alignEnd = true)
        } else {
            drawSmall(tm, "امروز", Offset(right, ty), labelSize, PastGrey, alignEnd = true)
        }
    }
}

/** برچسب کوچک با پس‌زمینه تیره، بالا یا پایین یک نقطه؛ داخل کادر نگه داشته می‌شود. */
private fun DrawScope.label(
    tm: TextMeasurer,
    text: String,
    at: Offset,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    above: Boolean,
    bold: Boolean = false,
    alignEnd: Boolean = false,
    background: Boolean = true
) {
    val layout = tm.measure(
        text,
        TextStyle(color = color, fontSize = fontSize, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    )
    val w = layout.size.width.toFloat()
    val h = layout.size.height.toFloat()
    var x = if (alignEnd) at.x - w - 8f else at.x
    var yy = if (above) at.y - h - 4f else at.y + 4f
    x = x.coerceIn(0f, maxOf(0f, size.width - w - 4f))
    yy = yy.coerceIn(0f, maxOf(0f, size.height - h))
    if (background) {
        drawRoundRect(
            Color(0xCC0B1120),
            topLeft = Offset(x - 3f, yy - 1f),
            size = Size(w + 6f, h + 2f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
        )
    }
    drawText(layout, topLeft = Offset(x, yy))
}

private fun DrawScope.drawSmall(
    tm: TextMeasurer,
    text: String,
    at: Offset,
    fontSize: androidx.compose.ui.unit.TextUnit,
    color: Color,
    alignEnd: Boolean = false,
    center: Boolean = false
) {
    val layout = tm.measure(text, TextStyle(color = color, fontSize = fontSize))
    val w = layout.size.width.toFloat()
    val x = when {
        center -> at.x - w / 2
        alignEnd -> at.x - w
        else -> at.x
    }.coerceIn(0f, maxOf(0f, size.width - w))
    drawText(layout, topLeft = Offset(x, at.y))
}

/** راهنمای رنگ‌های نمودار روند. */
@Composable
fun TrendLegend(showPosition: Boolean, showForecast: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showPosition) {
            LegendDot(BuyBlue, "خرید شما")
            LegendDot(ProfitGreen, "سود")
            LegendDot(LossRed, "زیان")
            LegendDot(AccentGold, "حد ضرر")
        }
        if (showForecast) LegendDot(ForecastPurple, "پیش‌بینی")
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, RoundedCornerShape(4.dp))
        )
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun pctOf(p: Double) = Format.num(p * 100, 0) + "٪"

/** خلاصه متنی وضعیت موقعیت (سود/زیان الان) و پیش‌بینی آن. */
@Composable
fun OutlookSummary(o: PositionOutlook, compact: Boolean = false) {
    val fc = o.forecast
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            (if (o.pnlPct >= 0) "الان در سود: " else "الان در زیان: ") + Format.pct(o.pnlPct) +
                " • اگر همین الان بفروشد (پس از کارمزد): " + Format.pct(o.netPnlPct),
            fontSize = if (compact) 11.sp else 12.sp,
            fontWeight = FontWeight.Bold,
            color = pnlColor(o.netPnlPct)
        )
        if (fc == null) {
            Text(
                "برای پیش‌بینی، تاریخچه قیمت کافی (حداقل ۲۰ روز) در دسترس نیست.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp)
            )
            return@Column
        }
        Text(
            "پیش‌بینی " + fc.horizonDays + " روز آینده: " + fc.trendLabel + " — سود/زیان محتمل " +
                Format.pct(o.expectedPnlPct) + " (بازه " + Format.pct(o.lowPnlPct) + " تا " + Format.pct(o.highPnlPct) + ")",
            fontSize = 11.sp,
            color = ForecastPurple,
            modifier = Modifier.padding(top = 3.dp)
        )
        val parts = ArrayList<String>()
        o.probProfit?.let { parts.add("احتمال سودده بودن پس از " + fc.horizonDays + " روز: " + pctOf(it)) }
        if (!compact) {
            o.probTakeProfit?.let { parts.add("رسیدن به حد سود: " + pctOf(it)) }
            o.probStop?.let { parts.add("خوردن حد ضرر: " + pctOf(it)) }
        }
        if (parts.isNotEmpty()) {
            Text(
                parts.joinToString(" • "),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (!compact) {
            Text(
                "اعتماد پیش‌بینی: " + fc.confidence + ". بر اساس روند و نوسان اخیر و امتیاز موتور محاسبه شده و قطعی نیست؛ " +
                    "خبر یا اتفاق ناگهانی می‌تواند آن را کاملاً تغییر دهد.",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/** خلاصه پیش‌بینی برای دارایی‌ای که در پرتفوی نیست. */
@Composable
fun ForecastSummary(fc: Forecast.Result) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "پیش‌بینی " + fc.horizonDays + " روز آینده: " + fc.trendLabel + " — تغییر محتمل " + Format.pct(fc.expectedPct) +
                " (بازه " + Format.pct(fc.lowPct) + " تا " + Format.pct(fc.highPct) + ")",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = ForecastPurple
        )
        Text(
            "احتمال بالاتر بودن قیمت از الان: " + pctOf(fc.probUp) + " • اعتماد: " + fc.confidence,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
