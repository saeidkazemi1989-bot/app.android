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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saeidkazemi.trader.data.model.Action
import com.saeidkazemi.trader.ui.UiState
import com.saeidkazemi.trader.ui.components.NewsItemRow
import com.saeidkazemi.trader.ui.components.SleevesCard
import com.saeidkazemi.trader.ui.components.InfoCard
import com.saeidkazemi.trader.ui.components.SectionTitle
import com.saeidkazemi.trader.ui.components.SimBadge
import com.saeidkazemi.trader.ui.components.pnlColor
import com.saeidkazemi.trader.util.Format

@Composable
fun DashboardScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onToggleAuto: (Boolean) -> Unit,
    onOpenAsset: (String) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "معامله‌یار",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onRefresh, enabled = !state.refreshing) {
                    Icon(Icons.Filled.Refresh, contentDescription = "به‌روزرسانی")
                }
            }

            if (state.error != null) {
                Text(
                    text = state.error!!,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // کارت ارزش کل
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Text("ارزش کل دارایی", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = "$" + Format.money(state.equityUsd),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = "≈ " + Format.compactIrr(state.equityUsd * state.usdIrr) + " ریال",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    InfoCard(
                        title = "نقد در دسترس",
                        value = "$" + Format.money(state.account.cashUsd),
                        modifier = Modifier.weight(1f)
                    )
                    InfoCard(
                        title = "بازده کل",
                        value = Format.pct(state.totalReturnPct),
                        sub = "$" + Format.money(state.totalReturnUsd),
                        valueColor = pnlColor(state.totalReturnUsd),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            SleevesCard(state = state, detailed = false, modifier = Modifier.padding(top = 12.dp))

            // روند کلی هر بازار
            com.saeidkazemi.trader.ui.components.MarketTrendsCard(state = state, modifier = Modifier.padding(top = 12.dp))

            // معامله خودکار
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("معامله‌گر خودکار", fontWeight = FontWeight.Bold)
                        Text(
                            text = "خرید و فروش کاملاً خودکار و بدون تأیید موردی، هر ۶۰ ثانیه، با رعایت حد ضرر و حد سود",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = state.settings.autoTrade, onCheckedChange = onToggleAuto)
                }
                val last = state.lastCycle
                if (last != null) {
                    Text(
                        text = "آخرین بررسی: " + Format.dateTime(last.ts) + " — " + last.summary(),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            // هشدارها
            if (state.notes.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .background(Color(0x26F7B731), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text("یادداشت‌ها", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF7B731))
                    state.notes.take(6).forEach { n ->
                        Text("• " + n, fontSize = 11.sp, color = TextPrimaryNote, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }

            // بهترین فرصت‌ها
            SectionTitle("بهترین فرصت‌های امروز")
            if (state.signals.isEmpty()) {
                Text(
                    "هنوز سیگنالی ساخته نشده؛ بازار را به‌روزرسانی کنید.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                state.signals.take(3).forEach { sig ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                            .clickable { onOpenAsset(sig.assetId) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(sig.symbol, fontWeight = FontWeight.Bold)
                                if (sig.isSimulated) {
                                    Spacer(Modifier.width(6.dp))
                                    SimBadge()
                                }
                            }
                            Text(sig.name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = sig.score.toString() + " از ۱۰۰",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF7B731)
                            )
                            Text(
                                text = sig.action.faTitle,
                                fontSize = 12.sp,
                                color = if (sig.action == Action.BUY) pnlColor(1.0) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Text(
                    "برای خرید پیشنهادی، وارد تب «سیگنال‌ها» شوید.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // مهم‌ترین اخبار اخیر
            val strongNews = state.newsFeed
                .filter { kotlin.math.abs(it.item.sentiment) >= 0.35 }
                .take(3)
            if (strongNews.isNotEmpty()) {
                SectionTitle("مهم‌ترین اخبار مؤثر")
                strongNews.forEach { e ->
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        NewsItemRow(item = e.item, symbol = e.symbol, onSymbolClick = { onOpenAsset(e.assetId) })
                    }
                }
            }

            // موقعیت‌های باز
            SectionTitle("موقعیت‌های باز")
            if (state.account.positions.isEmpty()) {
                Text(
                    "پرتفوی خالی است. با روشن کردن معامله‌گر خودکار یا خرید دستی شروع کنید.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                state.account.positions.forEach { pos ->
                    val cur = state.priceMap[pos.assetId] ?: pos.avgBuyUsd
                    val value = cur * pos.qty
                    val pnl = value - pos.qty * pos.avgBuyUsd
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                            .clickable { onOpenAsset(pos.assetId) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(pos.symbol, fontWeight = FontWeight.Bold)
                            Text(
                                "میانگین خرید: $" + Format.price(pos.avgBuyUsd),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("$" + Format.money(value), fontWeight = FontWeight.Bold)
                            Text(
                                Format.pct(if (pos.avgBuyUsd > 0) (cur / pos.avgBuyUsd - 1) * 100 else 0.0),
                                fontSize = 12.sp,
                                color = pnlColor(pnl)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        if (state.loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xAA0B1120)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(42.dp))
                    Text(
                        "در حال دریافت اطلاعات بازارها…",
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        }
    }
}

private val TextPrimaryNote = Color(0xFFE8ECF4)
