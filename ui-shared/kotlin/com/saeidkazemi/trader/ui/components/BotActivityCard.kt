package com.saeidkazemi.trader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saeidkazemi.trader.ui.UiState
import com.saeidkazemi.trader.ui.theme.AccentGold
import com.saeidkazemi.trader.ui.theme.LossRed
import com.saeidkazemi.trader.ui.theme.ProfitGreen
import com.saeidkazemi.trader.util.Format

private fun ago(ms: Long): String {
    val s = maxOf(0L, ms / 1000)
    return when {
        s < 60 -> "چند ثانیه پیش"
        s < 3600 -> (s / 60).toString() + " دقیقه پیش"
        s < 86400 -> (s / 3600).toString() + " ساعت پیش"
        else -> (s / 86400).toString() + " روز پیش"
    }
}

/**
 * «ربات الان چه می‌کند؟» — نشان می‌دهد موتور معامله واقعاً در حال اجراست (آخرین بررسی)،
 * و در هر بازار چرا خرید/فروش کرد یا نکرد و هر موقعیت باز در چه شرایطی فروخته می‌شود.
 */
@Composable
fun BotActivityCard(state: UiState, modifier: Modifier = Modifier, onBatteryFix: (() -> Unit)? = null) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Text("ربات الان چه می‌کند؟", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        val last = state.lastCycle
        val now = System.currentTimeMillis()
        when {
            !state.settings.autoTrade -> Text(
                "معامله خودکار خاموش است؛ ربات فقط بازار را پایش می‌کند و خرید/فروش نمی‌کند.",
                fontSize = 12.sp, color = LossRed, modifier = Modifier.padding(top = 6.dp)
            )
            last == null -> Text(
                "هنوز اولین دور بررسی تمام نشده…",
                fontSize = 12.sp, color = AccentGold, modifier = Modifier.padding(top = 6.dp)
            )
            now - last.ts > 10 * 60_000L -> {
                Text(
                    "⚠ آخرین بررسی " + ago(now - last.ts) + " بوده. احتمالاً سیستم گوشی برنامه را در پس‌زمینه بسته است. " +
                        "برای کار مداوم، «بهینه‌سازی باتری» را برای معامله‌یار خاموش کنید و در تنظیمات گوشی اجازه «اجرای خودکار / بدون محدودیت» بدهید.",
                    fontSize = 12.sp, lineHeight = 18.sp, color = LossRed, modifier = Modifier.padding(top = 6.dp)
                )
                if (onBatteryFix != null) {
                    OutlinedButton(onClick = onBatteryFix, modifier = Modifier.padding(top = 6.dp)) {
                        Text("خاموش کردن بهینه‌سازی باتری", fontSize = 12.sp)
                    }
                }
            }
            else -> Text(
                "✓ فعال — آخرین بررسی " + ago(now - last.ts) + " (" + Format.dateTime(last.ts) + "). " +
                    "هر ۱ دقیقه همه بازارها بررسی می‌شوند و هر وقت شرایط خرید یا فروش برقرار شود، بدون تأیید شما معامله انجام می‌شود.",
                fontSize = 12.sp, lineHeight = 18.sp, color = ProfitGreen, modifier = Modifier.padding(top = 6.dp)
            )
        }
        if (state.activity.isEmpty() && state.settings.autoTrade) {
            Text(
                "وضعیت بازارها پس از اولین دور کامل نمایش داده می‌شود.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)
            )
        }
        state.activity.forEach { a ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                Text(
                    a.market.faTitle + " — " + a.positions + " از " + a.maxPositions + " موقعیت",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    a.status,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = when {
                        a.boughtNow > 0 -> ProfitGreen
                        else -> AccentGold
                    },
                    modifier = Modifier.padding(top = 3.dp)
                )
                a.details.forEach { d ->
                    Text(
                        "• $d",
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                a.lastTradeAt?.let {
                    Text(
                        "آخرین معامله این بازار: " + Format.dateTime(it) + " (" + ago(now - it) + ")",
                        fontSize = 10.sp, color = Color(0xFF8A93A6), modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
        }
    }
}
