package com.saeidkazemi.trader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saeidkazemi.trader.ui.UiState
import com.saeidkazemi.trader.util.Format

/** کارت «سرمایه هر بازار»: هر بازار صندوق جداگانه با سود/زیان خودش. */
@Composable
fun SleevesCard(state: UiState, detailed: Boolean, modifier: Modifier = Modifier) {
    val sleeves = state.sleeves().filter { it.allocationPct > 0 || it.positions > 0 || it.capitalUsd > 0 }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Text("سرمایه اختصاصی هر بازار", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        if (sleeves.isEmpty()) {
            Text("تقسیم سرمایه تنظیم نشده است.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        sleeves.forEach { s ->
            Column(modifier = Modifier.padding(top = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        s.market.faTitle + " (" + Format.num(s.allocationPct, 0) + "٪)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        "$" + Format.money(s.equityUsd) + "  " + Format.pct(s.returnPct),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = pnlColor(s.returnUsd)
                    )
                }
                val used = if (s.equityUsd > 0) (s.positionsUsd / s.equityUsd).toFloat().coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(
                    progress = { used },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .padding(top = 4.dp),
                    color = Color(0xFFF7B731),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Text(
                    "نقد $" + Format.money(s.cashUsd) + " • " + s.positions + " موقعیت ($" + Format.money(s.positionsUsd) + ")" +
                        (if (detailed) " • سود تحقق‌یافته $" + Format.money(s.realizedUsd) + " • سرمایه اولیه $" + Format.money(s.capitalUsd) +
                            " • ریسک " + riskFa(s.riskLevel) else ""),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}

fun riskFa(level: String): String = when (level) {
    "LOW" -> "کم"
    "HIGH" -> "زیاد"
    else -> "متوسط"
}
