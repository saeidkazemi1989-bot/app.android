package com.saeidkazemi.trader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saeidkazemi.trader.analysis.MarketTrendReport
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.ui.UiState
import com.saeidkazemi.trader.ui.theme.AccentGold
import com.saeidkazemi.trader.ui.theme.LossRed
import com.saeidkazemi.trader.ui.theme.ProfitGreen
import com.saeidkazemi.trader.util.Format

private fun trendColor(score: Int): Color = when {
    score > 0 -> ProfitGreen
    score < 0 -> LossRed
    else -> AccentGold
}

private fun arrow(score: Int): String = when (score) {
    2 -> "⬆⬆"
    1 -> "⬆"
    -1 -> "⬇"
    -2 -> "⬇⬇"
    else -> "↔"
}

/**
 * روند کلی بازارها: خلاصه هر بازار (جهت، تغییر ۷ روزه) و نمودار کامل بازار انتخاب‌شده
 * با پیش‌بینی ۷ روز، پهنای بازار، بهترین/بدترین‌ها و اطلاعات مخصوص همان بازار.
 *
 * @param only اگر داده شود فقط همان بازار نمایش داده می‌شود (مثلاً در تب بازارها).
 */
@Composable
fun MarketTrendsCard(state: UiState, modifier: Modifier = Modifier, only: MarketKind? = null) {
    val reports = state.marketTrends.filter { only == null || it.market == only }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Text(
            if (only == null) "روند کلی بازارها" else "روند کلی " + only.faTitle,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
        if (reports.isEmpty()) {
            Text(
                "هنوز تاریخچه کافی برای ساخت روند دریافت نشده؛ پس از چند دور بررسی نمایش داده می‌شود.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            return@Column
        }
        var selected by remember { mutableStateOf(reports.first().market) }
        val current = reports.firstOrNull { it.market == selected } ?: reports.first()

        if (reports.size > 1) {
            // خلاصه همه بازارها؛ با لمس، نمودار همان بازار نمایش داده می‌شود
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                reports.forEach { r ->
                    val sel = r.market == current.market
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (sel) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { selected = r.market }
                            .padding(8.dp)
                    ) {
                        Text(r.market.faTitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            arrow(r.trendScore) + " " + r.trendLabel,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = trendColor(r.trendScore)
                        )
                        Text(
                            "۷ روز " + Format.pct(r.change7d),
                            fontSize = 11.sp,
                            color = pnlColor(r.change7d ?: 0.0)
                        )
                    }
                }
            }
        }
        MarketTrendDetail(current)
    }
}

@Composable
private fun MarketTrendDetail(r: MarketTrendReport) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(r.title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(
                arrow(r.trendScore) + " " + r.trendLabel,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = trendColor(r.trendScore)
            )
        }
        if (r.simulated) {
            Text("⚠ بخشی از داده این بازار شبیه‌سازی‌شده است؛ روند فقط نمایشی است.", fontSize = 10.sp, color = AccentGold)
        }
        val cutoff = System.currentTimeMillis() - 90L * 86_400_000L
        TrendChart(
            past = r.index.filter { it.t >= cutoff },
            forecast = r.forecast,
            fmt = { Format.num(it, 1) },
            height = 200.dp,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            "۱ روز " + Format.pct(r.change1d) + " • ۷ روز " + Format.pct(r.change7d) + " • ۳۰ روز " + Format.pct(r.change30d),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = pnlColor(r.change7d ?: 0.0),
            modifier = Modifier.padding(top = 6.dp)
        )
        val parts = ArrayList<String>()
        r.aboveSma20?.let { parts.add((if (it) "بالای" else "زیر") + " میانگین ۲۰ روزه") }
        r.aboveSma50?.let { parts.add((if (it) "بالای" else "زیر") + " میانگین ۵۰ روزه") }
        r.volatilityPct?.let { parts.add("نوسان روزانه " + Format.num(it, 1) + "٪") }
        if (parts.isNotEmpty()) Line(parts.joinToString(" • "))
        val breadth = ArrayList<String>()
        r.breadthAboveSma20?.let { breadth.add(Format.num(it * 100, 0) + "٪ دارایی‌ها بالای میانگین ۲۰ روزه خودشان") }
        if (r.advancers + r.decliners > 0) breadth.add("۲۴ ساعت: " + r.advancers + " مثبت، " + r.decliners + " منفی")
        if (breadth.isNotEmpty()) Line("پهنای بازار: " + breadth.joinToString(" • "))
        if (r.leaders.isNotEmpty()) {
            Line("بهترین‌های ۷ روز: " + r.leaders.joinToString("، ") { it.first + " " + Format.pct(it.second) }, ProfitGreen)
        }
        if (r.laggards.isNotEmpty()) {
            Line("ضعیف‌ترین‌های ۷ روز: " + r.laggards.joinToString("، ") { it.first + " " + Format.pct(it.second) }, LossRed)
        }
        r.facts.forEach { Line("• $it") }
        r.forecast?.let { fc ->
            Text(
                "پیش‌بینی " + fc.horizonDays + " روز: " + fc.trendLabel + " — " + Format.pct(fc.expectedPct) +
                    " (بازه " + Format.pct(fc.lowPct) + " تا " + Format.pct(fc.highPct) + ") • اعتماد: " + fc.confidence,
                fontSize = 11.sp,
                color = ForecastPurple,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun Line(text: String, color: Color? = null) {
    Text(
        text,
        fontSize = 11.sp,
        lineHeight = 17.sp,
        color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 3.dp)
    )
}
