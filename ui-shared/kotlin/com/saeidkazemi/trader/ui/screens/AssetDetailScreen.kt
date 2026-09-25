package com.saeidkazemi.trader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saeidkazemi.trader.data.model.Action
import com.saeidkazemi.trader.ui.UiState
import com.saeidkazemi.trader.ui.components.EmptyBox
import com.saeidkazemi.trader.ui.components.KVRow
import com.saeidkazemi.trader.ui.components.MarketChip
import com.saeidkazemi.trader.ui.components.NewsDigestSummary
import com.saeidkazemi.trader.ui.components.NewsItemRow
import com.saeidkazemi.trader.ui.components.sentimentColor
import com.saeidkazemi.trader.ui.components.PriceChart
import com.saeidkazemi.trader.ui.components.SimBadge
import com.saeidkazemi.trader.ui.components.pnlColor
import com.saeidkazemi.trader.ui.planPositionPct
import com.saeidkazemi.trader.util.Format

@Composable
fun AssetDetailScreen(
    assetId: String,
    state: UiState,
    onBuy: (String, String) -> Unit,
    onSell: (String) -> Unit,
    onBack: () -> Unit
) {
    val detail = state.detail
    val suggested = minOf(
        state.equityUsd * planPositionPct(state.settings.riskLevel),
        state.account.cashUsd * 0.95
    )
    var amountInput by remember(assetId) {
        mutableStateOf(Format.raw(maxOf(0.0, suggested), 2))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "بازگشت")
            }
            Text("جزئیات دارایی", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        if (detail == null || detail.asset.id != assetId) {
            EmptyBox("در حال بارگذاری…")
            return@Column
        }

        val asset = detail.asset

        // عنوان و قیمت
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(asset.symbol, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    if (asset.isSimulated) {
                        Spacer(Modifier.width(8.dp))
                        SimBadge()
                    }
                    Spacer(Modifier.width(8.dp))
                    MarketChip(asset.market.faTitle)
                }
                Text(
                    asset.name,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                val priceText = if (asset.baseCurrency == "IRR") {
                    Format.compactIrr(asset.price) + " ریال"
                } else {
                    "$" + Format.price(asset.price)
                }
                Text(priceText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                val alt = if (asset.baseCurrency == "IRR") {
                    "≈ $" + Format.price(detail.usdPrice)
                } else {
                    "≈ " + Format.compactIrr(detail.usdPrice * state.usdIrr) + " ریال"
                }
                Text(
                    alt,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (asset.changePct24h != null) {
                    Text(
                        "۲۴ ساعت: " + Format.pct(asset.changePct24h),
                        fontSize = 12.sp,
                        color = pnlColor(asset.changePct24h),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }

        // نمودار
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .padding(12.dp)
        ) {
            Text(
                if (asset.market == com.saeidkazemi.trader.data.model.MarketKind.IR_STOCK && asset.isSimulated)
                    "نمودار قیمت (شبیه‌سازی ۱۲۰ روزه)"
                else "نمودار قیمت ۹۰ روزه",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            PriceChart(points = detail.history, modifier = Modifier.padding(top = 8.dp))
        }

        // موقعیت باز
        val pos = state.account.positions.firstOrNull { it.assetId == assetId }
        if (pos != null) {
            val cur = state.priceMap[assetId] ?: pos.avgBuyUsd
            val value = cur * pos.qty
            val pnl = value - pos.qty * pos.avgBuyUsd
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                Text("موقعیت باز شما", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                KVRow("تعداد", Format.price(pos.qty))
                KVRow("میانگین خرید", "$" + Format.price(pos.avgBuyUsd))
                KVRow("ارزش فعلی", "$" + Format.money(value))
                KVRow("سود/زیان باز", Format.pct(if (pos.avgBuyUsd > 0) (cur / pos.avgBuyUsd - 1) * 100 else 0.0), pnlColor(pnl))
                KVRow("حد ضرر", "$" + Format.price(pos.stopLossUsd), Color(0xFFF7B731))
                KVRow("حد سود", "$" + Format.price(pos.takeProfitUsd), Color(0xFFF7B731))
                Button(
                    onClick = { onSell(assetId) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text("فروش کامل")
                }
            }
        }

        // سیگنال
        val sig = detail.signal
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .padding(12.dp)
        ) {
            Text("تحلیل موتور", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            if (sig == null) {
                Text(
                    "برای این دارایی داده تاریخی کافی وجود ندارد (دارایی‌های نمایشی مثل طلا یا ارزهای رتبه‌های پایین).",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "امتیاز: " + sig.score + " از ۱۰۰",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF7B731)
                    )
                    Text(
                        "پیشنهاد: " + sig.action.faTitle,
                        fontWeight = FontWeight.Bold,
                        color = when (sig.action) {
                            Action.BUY -> pnlColor(1.0)
                            Action.SELL -> pnlColor(-1.0)
                            Action.HOLD -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                if (sig.newsLabel != null) {
                    val sign = if (sig.newsAdj > 0) "+" else ""
                    Text(
                        "امتیاز تکنیکال " + sig.technicalScore + " " + sign + sig.newsAdj + " (اثر اخبار) = " + sig.score,
                        fontSize = 12.sp,
                        color = sentimentColor(sig.newsAdj.toDouble()),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                sig.reasons.forEach { r ->
                    Text(
                        "• " + r,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                val m = sig.metrics
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    if (m.rsi != null) KVRow("آراس‌آی (۱۴)", Format.num(m.rsi, 1))
                    if (m.trendPct != null) KVRow("روند (فاصله میانگین‌ها)", Format.pct(m.trendPct))
                    if (m.momentum7 != null) KVRow("مومنتوم ۷ روزه", Format.pct(m.momentum7))
                    if (m.momentum30 != null) KVRow("مومنتوم ۳۰ روزه", Format.pct(m.momentum30))
                    if (m.volatility != null) KVRow("نوسان روزانه", Format.num(m.volatility, 2) + "٪")
                }
            }
        }

        // اخبار و اطلاعیه‌ها
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .padding(12.dp)
        ) {
            Text(
                if (asset.market == com.saeidkazemi.trader.data.model.MarketKind.IR_STOCK)
                    "اطلاعیه‌های کدال و اخبار مرتبط"
                else "اخبار مرتبط",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            val news = detail.news
            when {
                !state.settings.newsEnabled -> Text(
                    "بررسی اخبار در تنظیمات خاموش است.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )

                news == null && detail.newsLoading -> Text(
                    "در حال دریافت اخبار و اطلاعیه‌ها…",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )

                news == null || !news.available -> {
                    Text(
                        "خبر مرتبطی در ۷ روز اخیر پیدا نشد" +
                            (if (news != null && news.sourcesFailed.isNotEmpty())
                                " (در دسترس نبود: " + news.sourcesFailed.joinToString("، ") + ")" else "") + ".",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                else -> {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        NewsDigestSummary(news)
                    }
                    news.items.take(12).forEach { item ->
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            NewsItemRow(item)
                        }
                    }
                    Text(
                        "با لمس هر خبر، متن کامل در مرورگر باز می‌شود. تحلیل احساس خودکار است و ممکن است خطا داشته باشد.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        // خرید دستی
        if (pos == null && !asset.isDisplayOnly) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                Text("خرید دستی", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                OutlinedTextField(
                    value = amountInput,
                    onValueChange = { amountInput = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    label = { Text("مبلغ به دلار") },
                    singleLine = true
                )
                Text(
                    "موجودی نقد: $" + Format.money(state.account.cashUsd),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Button(
                    onClick = { onBuy(assetId, amountInput) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text("خرید")
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
