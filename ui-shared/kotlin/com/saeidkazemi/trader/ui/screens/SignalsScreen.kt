package com.saeidkazemi.trader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saeidkazemi.trader.data.model.Action
import com.saeidkazemi.trader.data.model.Signal
import com.saeidkazemi.trader.ui.UiState
import com.saeidkazemi.trader.ui.components.EmptyBox
import com.saeidkazemi.trader.ui.components.MarketChip
import com.saeidkazemi.trader.ui.components.SentimentChip
import com.saeidkazemi.trader.ui.components.SimBadge
import com.saeidkazemi.trader.ui.components.pnlColor
import com.saeidkazemi.trader.ui.planPositionPct
import com.saeidkazemi.trader.util.Format

@Composable
fun SignalsScreen(
    state: UiState,
    onBuy: (String, String) -> Unit,
    onOpenAsset: (String) -> Unit
) {
    val held = state.heldAssetIds()
    val suggested = minOf(
        state.equityUsd * planPositionPct(state.settings.riskLevel),
        state.account.cashUsd * 0.95
    )
    val suggestedStr = Format.raw(maxOf(0.0, suggested), 2)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    "موتور تحلیل، هر دارایی را بر اساس روند، مومنتوم، مکدی و آراس‌آی از ۰ تا ۱۰۰ امتیاز می‌دهد " +
                        "و سپس اثر اخبار و اطلاعیه‌های کدال را (از ۱۲- تا ۱۲+) اضافه می‌کند. " +
                        "در حالت خودکار، فقط سیگنال‌های خرید با امتیاز " + state.settings.buyThreshold + " به بالا معامله می‌شوند " +
                        "و حد ضرر/حد سود روی هر موقعیت اعمال می‌شود.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "معامله خودکار: " + (if (state.settings.autoTrade) "روشن" else "خاموش") +
                        " • پرتفوی: " + state.account.positions.size + " موقعیت",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        if (state.signals.isEmpty()) {
            item {
                EmptyBox("سیگنالی وجود ندارد؛ از داشبورد بازار را به‌روزرسانی کنید.")
            }
        } else {
            items(state.signals, key = { it.assetId }) { sig ->
                SignalCard(
                    sig = sig,
                    isHeld = held.contains(sig.assetId),
                    suggestedStr = suggestedStr,
                    onBuy = onBuy,
                    onOpenAsset = onOpenAsset
                )
            }
        }
    }
}

@Composable
private fun SignalCard(
    sig: Signal,
    isHeld: Boolean,
    suggestedStr: String,
    onBuy: (String, String) -> Unit,
    onOpenAsset: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .clickable { onOpenAsset(sig.assetId) }
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sig.symbol, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    if (sig.isSimulated) {
                        Spacer(Modifier.width(6.dp))
                        SimBadge()
                    }
                    Spacer(Modifier.width(6.dp))
                    MarketChip(sig.market.faTitle)
                }
                Text(
                    sig.name,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    sig.score.toString() + " از ۱۰۰",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color(0xFFF7B731)
                )
                Text(
                    sig.action.faTitle,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = when (sig.action) {
                        Action.BUY -> pnlColor(1.0)
                        Action.SELL -> pnlColor(-1.0)
                        Action.HOLD -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        LinearProgressIndicator(
            progress = { sig.score / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .padding(top = 10.dp),
            color = Color(0xFFF7B731),
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )

        if (sig.newsLabel != null) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val sign = if (sig.newsAdj > 0) "+" else ""
                SentimentChip(sig.newsAdj.toDouble(), "اخبار: " + sign + sig.newsAdj)
                Spacer(Modifier.width(6.dp))
                Text(
                    sig.newsLabel + " • تکنیکال: " + sig.technicalScore,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (sig.newsBlocked) {
                    Spacer(Modifier.width(6.dp))
                    SentimentChip(-1.0, "خرید متوقف")
                }
            }
        }

        Text(
            text = sig.reasons.joinToString(" • "),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )

        val m = sig.metrics
        val metricParts = mutableListOf<String>()
        if (m.rsi != null) metricParts.add("آراس‌آی: " + Format.num(m.rsi, 0))
        if (m.trendPct != null) metricParts.add("روند: " + Format.pct(m.trendPct))
        if (m.momentum7 != null) metricParts.add("هفتگی: " + Format.pct(m.momentum7))
        if (m.volatility != null) metricParts.add("نوسان روزانه: " + Format.num(m.volatility, 1) + "٪")
        if (metricParts.isNotEmpty()) {
            Text(
                text = metricParts.joinToString(" | "),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onBuy(sig.assetId, suggestedStr) },
                enabled = sig.action == Action.BUY && !isHeld,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (isHeld) "در پرتفوی دارید" else "خرید پیشنهادی ($" + suggestedStr + ")")
            }
            OutlinedButton(
                onClick = { onOpenAsset(sig.assetId) },
                modifier = Modifier.weight(0.7f)
            ) {
                Text("جزئیات و نمودار")
            }
        }
    }
}
