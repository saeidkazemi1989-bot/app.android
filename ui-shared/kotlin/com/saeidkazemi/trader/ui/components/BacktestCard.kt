package com.saeidkazemi.trader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saeidkazemi.trader.analysis.Backtest
import com.saeidkazemi.trader.ui.UiState
import com.saeidkazemi.trader.ui.theme.AccentGold
import com.saeidkazemi.trader.ui.theme.LossRed
import com.saeidkazemi.trader.ui.theme.ProfitGreen
import com.saeidkazemi.trader.util.Format

private fun statLine(title: String, s: Backtest.Stats?): String {
    if (s == null || s.trades == 0) return "$title: معامله‌ای نبود"
    return title + ": " + s.trades + " معامله • برد " + Format.num(s.winRate, 0) + "٪" +
        " • میانگین سود " + Format.trim(s.avgWinPct, 1) + "٪ / زیان " + Format.trim(s.avgLossPct, 1) + "٪" +
        " • ریسک به ریوارد ۱:" + Format.trim(s.rr, 2) +
        " • امید هر معامله " + Format.pct(s.expectancyPct) +
        " • ضریب سود " + Format.trim(s.profitFactor, 2)
}

/**
 * «بک‌تست و ریسک به ریوارد»: نتیجه آزمون استراتژی روی تاریخچه واقعی، پارامترهای انتخاب‌شده با هدف
 * نرخ برد ۶۰٪، و اینکه روی معاملات واقعی اعمال شده یا نه.
 */
@Composable
fun BacktestCard(
    state: UiState,
    modifier: Modifier = Modifier,
    onRun: (() -> Unit)? = null,
    onToggle: ((Boolean) -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Text("بک‌تست و ریسک به ریوارد", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(
            "ربات روزی یک بار همان امتیازدهی خودش را روی تاریخچه واقعی (تا ۲ سال) روز به روز اجرا می‌کند و " +
                Backtest.grid(com.saeidkazemi.trader.data.model.MarketKind.CRYPTO).size + " ترکیب «آستانه خرید، حد سود، حد ضرر، حد ضرر متحرک، مهلت نگهداری، فیلتر روند و همراهی بازار» را با کارمزد و اسپرد واقعی امتحان می‌کند. " +
                "هدف: نرخ برد حداقل " + Backtest.TARGET_WIN_RATE.toInt() + "٪ همراه با سود (نه فقط برد زیاد با زیان‌های بزرگ). " +
                "انتخاب روی ۷۰٪ اول داده و آزمون روی ۳۰٪ آخر انجام می‌شود؛ فقط اگر در آزمون هم سودده باشد اعمال می‌شود.",
            fontSize = 11.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (onToggle != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text("استفاده از پارامترهای بک‌تست برای خریدهای جدید", fontSize = 12.sp, modifier = Modifier.weight(1f))
                Switch(checked = state.settings.useBacktestParams, onCheckedChange = onToggle)
            }
        }
        val r = state.backtest
        when {
            state.backtestRunning -> Text(
                "⏳ " + (state.backtestProgress ?: "در حال اجرا…"),
                fontSize = 12.sp, color = AccentGold, modifier = Modifier.padding(top = 8.dp)
            )
            r == null -> Text(
                "هنوز بک‌تستی انجام نشده؛ بعد از اولین دور بررسی بازار خودکار شروع می‌شود.",
                fontSize = 12.sp, color = AccentGold, modifier = Modifier.padding(top = 8.dp)
            )
            else -> Text(
                "آخرین اجرا: " + Format.dateTime(r.createdAt),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
            )
        }
        r?.results?.forEach { m ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                val color = when {
                    m.applied && m.reachedTarget -> ProfitGreen
                    m.applied -> AccentGold
                    else -> LossRed
                }
                Text(
                    m.market.faTitle + " — " + m.assets + " دارایی، " + m.days + " روز (" +
                        Format.date(m.fromT) + " تا " + Format.date(m.toT) + ")",
                    fontWeight = FontWeight.Bold, fontSize = 12.sp
                )
                Text(m.verdict, fontSize = 11.sp, lineHeight = 17.sp, color = color, modifier = Modifier.padding(top = 3.dp))
                Text("تنظیمات قبلی: " + m.current.label(), fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp))
                Text(statLine("  درون نمونه", m.currentIn), fontSize = 10.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(statLine("  آزمون", m.currentOut), fontSize = 10.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val b = m.best
                if (b != null) {
                    Text(
                        (if (m.applied) "✓ پارامتر جدید (اعمال شده): " else "پارامتر پیشنهادی (اعمال نشده): ") + b.label(),
                        fontSize = 11.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold,
                        color = if (m.applied) ProfitGreen else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Text(statLine("  درون نمونه", m.bestIn), fontSize = 10.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(statLine("  آزمون (داده دیده‌نشده)", m.bestOut), fontSize = 10.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    m.bestOut?.takeIf { it.trades > 0 }?.let { o ->
                        Text(
                            "برای سربه‌سر شدن با ریسک به ریوارد ۱:" + Format.trim(o.rr, 2) + " حداقل " +
                                Format.num(o.breakEvenWinRate, 0) + "٪ برد لازم است؛ نتیجه آزمون " + Format.num(o.winRate, 0) + "٪.",
                            fontSize = 10.sp, lineHeight = 16.sp, color = if (o.winRate > o.breakEvenWinRate) ProfitGreen else LossRed
                        )
                    }
                    val mix = m.exitMix.orEmpty()
                    if (mix.isNotEmpty()) {
                        Text(
                            "نحوه خروج: " + mix.entries.sortedByDescending { it.value }.joinToString(" • ") { it.key + " " + it.value },
                            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        r?.notes?.forEach {
            Text("• $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        Text(
            "توجه: بک‌تست فقط تحلیل تکنیکال را می‌سنجد (اخبار و داده حقیقی/حقوقی تاریخچه ندارند) و با قیمت پایانی روزانه است. " +
                "نتیجه گذشته تضمین آینده نیست؛ برای همین هر روز دوباره اجرا و در صورت افت، پارامتر کنار گذاشته می‌شود.",
            fontSize = 10.sp, lineHeight = 16.sp, color = Color(0xFF8A8F98), modifier = Modifier.padding(top = 8.dp)
        )
        if (onRun != null) {
            OutlinedButton(onClick = onRun, enabled = !state.backtestRunning, modifier = Modifier.padding(top = 8.dp)) {
                Text(if (state.backtestRunning) "در حال اجرا…" else "اجرای بک‌تست الان", fontSize = 12.sp)
            }
        }
    }
}
