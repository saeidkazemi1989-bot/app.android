package com.saeidkazemi.trader.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.saeidkazemi.trader.data.model.PricePoint
import com.saeidkazemi.trader.ui.theme.LossRed
import com.saeidkazemi.trader.ui.theme.ProfitGreen

/** نمودار خطی ساده قیمت بدون وابستگی خارجی. */
@Composable
fun PriceChart(
    points: List<PricePoint>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 180.dp
) {
    if (points.size < 2) {
        EmptyBox("داده کافی برای نمودار وجود ندارد")
        return
    }
    val first = points.first().price
    val last = points.last().price
    val lineColor = if (last >= first) ProfitGreen else LossRed
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val min = points.minOf { it.price }
        val max = points.maxOf { it.price }
        val range = if (max - min < 1e-12) 1.0 else max - min
        val pad = 10f
        val w = size.width - pad * 2
        val h = size.height - pad * 2
        val path = Path()
        points.forEachIndexed { i, p ->
            val x = pad + w * i.toFloat() / (points.size - 1)
            val y = pad + h * (1f - ((p.price - min) / range).toFloat())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        // خط پایه
        drawLine(
            color = Color(0x33FFFFFF),
            start = Offset(pad, size.height - pad),
            end = Offset(size.width - pad, size.height - pad),
            strokeWidth = 1f
        )
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        // نقطه پایانی
        val lastX = pad + w
        val lastY = pad + h * (1f - ((last - min) / range).toFloat())
        drawCircle(color = lineColor, radius = 5f, center = Offset(lastX, lastY))
    }
}
