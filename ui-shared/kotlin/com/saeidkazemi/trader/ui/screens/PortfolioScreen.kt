package com.saeidkazemi.trader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saeidkazemi.trader.data.model.Position
import com.saeidkazemi.trader.data.model.Trade
import com.saeidkazemi.trader.ui.UiState
import com.saeidkazemi.trader.ui.components.InfoCard
import com.saeidkazemi.trader.ui.components.SectionTitle
import com.saeidkazemi.trader.ui.components.pnlColor
import com.saeidkazemi.trader.util.Format

@Composable
fun PortfolioScreen(
    state: UiState,
    onSell: (String) -> Unit,
    onOpenAsset: (String) -> Unit
) {
    val account = state.account
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    InfoCard(
                        title = "ارزش کل",
                        value = "$" + Format.money(state.equityUsd),
                        sub = "≈ " + Format.compactIrr(state.equityUsd * state.usdIrr) + " ریال",
                        modifier = Modifier.weight(1f)
                    )
                    InfoCard(
                        title = "نقد",
                        value = "$" + Format.money(account.cashUsd),
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    InfoCard(
                        title = "سود تحقق‌یافته",
                        value = "$" + Format.money(account.realizedPnlUsd),
                        valueColor = pnlColor(account.realizedPnlUsd),
                        modifier = Modifier.weight(1f)
                    )
                    InfoCard(
                        title = "سود/زیان باز",
                        value = "$" + Format.money(state.unrealizedUsd),
                        valueColor = pnlColor(state.unrealizedUsd),
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = "بازده کل از ابتدای حساب: " + Format.pct(state.totalReturnPct) +
                        " (" + Format.money(state.totalReturnUsd) + " دلار)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = pnlColor(state.totalReturnUsd),
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }

        item { SectionTitle("موقعیت‌های باز (" + account.positions.size + ")") }

        if (account.positions.isEmpty()) {
            item {
                Text(
                    "هنوز موقعیتی ندارید. از تب «سیگنال‌ها» خرید کنید یا معامله خودکار را روشن کنید.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(account.positions, key = { it.assetId }) { pos ->
                PositionRow(
                    pos = pos,
                    curUsd = state.priceMap[pos.assetId] ?: pos.avgBuyUsd,
                    onSell = onSell,
                    onOpen = { onOpenAsset(pos.assetId) }
                )
            }
        }

        item { SectionTitle("تاریخچه معاملات") }

        if (account.trades.isEmpty()) {
            item {
                Text(
                    "هنوز معامله‌ای ثبت نشده است.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(account.trades.take(200), key = { it.id }) { trade ->
                TradeRow(trade)
            }
        }
    }
}

@Composable
private fun PositionRow(
    pos: Position,
    curUsd: Double,
    onSell: (String) -> Unit,
    onOpen: () -> Unit
) {
    val value = curUsd * pos.qty
    val pnl = value - pos.qty * pos.avgBuyUsd
    val pnlPct = if (pos.avgBuyUsd > 0) (curUsd / pos.avgBuyUsd - 1) * 100 else 0.0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(pos.symbol, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                "تعداد: " + Format.price(pos.qty),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                "حد ضرر: $" + Format.price(pos.stopLossUsd) + " • حد سود: $" + Format.price(pos.takeProfitUsd),
                fontSize = 10.sp,
                color = Color(0xFFF7B731),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 8.dp)) {
            Text("$" + Format.money(value), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                Format.pct(pnlPct),
                fontSize = 12.sp,
                color = pnlColor(pnl)
            )
        }
        Button(onClick = { onSell(pos.assetId) }) {
            Text("فروش", fontSize = 12.sp)
        }
    }
}

@Composable
private fun TradeRow(trade: Trade) {
    val isBuy = trade.side == "BUY"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .background(
                    if (isBuy) Color(0x3316C784) else Color(0x33EA3943),
                    RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                if (isBuy) "خرید" else "فروش",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (isBuy) pnlColor(1.0) else pnlColor(-1.0)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(trade.symbol, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(trade.reason, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("$" + Format.money(trade.usdValue), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(
                Format.dateTime(trade.ts) + " • " + if (trade.mode == "PAPER") "دمو" else "واقعی",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
