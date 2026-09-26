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
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.ui.UiState
import com.saeidkazemi.trader.ui.components.EmptyBox
import com.saeidkazemi.trader.ui.components.KVRow
import com.saeidkazemi.trader.ui.components.MarketChip
import com.saeidkazemi.trader.ui.components.NewsDigestSummary
import com.saeidkazemi.trader.ui.components.NewsItemRow
import com.saeidkazemi.trader.ui.components.sentimentColor
import com.saeidkazemi.trader.analysis.Forecast
import com.saeidkazemi.trader.data.model.PricePoint
import com.saeidkazemi.trader.ui.components.ForecastSummary
import com.saeidkazemi.trader.ui.components.OutlookSummary
import com.saeidkazemi.trader.ui.components.TrendChart
import com.saeidkazemi.trader.ui.components.TrendLegend
import androidx.compose.foundation.clickable
import com.saeidkazemi.trader.ui.components.SimBadge
import com.saeidkazemi.trader.ui.components.pnlColor
import com.saeidkazemi.trader.ui.suggestedBuyUsd
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
    val suggested = detail?.asset?.let { suggestedBuyUsd(state, it.market) * 0.98 } ?: 0.0
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
                val change24 = asset.changePct24h
                if (change24 != null) {
                    Text(
                        (if (asset.market == MarketKind.IR_STOCK) "امروز (آخرین معامله نسبت به دیروز): " else "۲۴ ساعت: ") + Format.pct(change24) +
                            (if (asset.market == MarketKind.IR_STOCK && asset.sellQueue) " • صف فروش" else if (asset.market == MarketKind.IR_STOCK && asset.buyQueue) " • صف خرید" else ""),
                        fontSize = 12.sp,
                        color = pnlColor(change24),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }

        // نمودار روند: کجا خریدید، الان در سود یا زیان هستید، و پیش‌بینی چند روز آینده
        val pos = state.account.positions.firstOrNull { it.assetId == assetId }
        val outlook = if (pos != null) state.outlooks[assetId] else null
        val factor = if (detail.usdPrice > 0 && asset.price > 0) asset.price / detail.usdPrice else 1.0
        val fmtNative: (Double) -> String = if (asset.baseCurrency == "IRR") {
            { v -> Format.compactIrr(v) }
        } else {
            { v -> "$" + Format.price(v) }
        }
        var range by remember(assetId, pos != null) { mutableStateOf(if (pos != null) "BUY" else "90") }
        val nowTs = System.currentTimeMillis()
        val hist = detail.history.filter { it.price > 0 }.sortedBy { it.t }
        val past: List<PricePoint> = run {
            if (pos != null) {
                val trend = (outlook?.trend ?: listOf(
                    PricePoint(pos.openedAt, pos.avgBuyUsd),
                    PricePoint(maxOf(nowTs, pos.openedAt + 1), detail.usdPrice)
                )).map { PricePoint(it.t, it.price * factor) }
                val before = hist.filter { it.t < pos.openedAt }
                when (range) {
                    "BUY" -> {
                        val ctx = (nowTs - pos.openedAt) / 2
                        val ctxPts = if (ctx >= 2 * 86_400_000L) before.filter { it.t >= pos.openedAt - ctx } else emptyList()
                        ctxPts + trend
                    }
                    else -> {
                        val from = nowTs - (range.toLongOrNull() ?: 90L) * 86_400_000L
                        (before + trend).filter { it.t >= from }
                    }
                }
            } else {
                val from = nowTs - (range.toLongOrNull() ?: 90L) * 86_400_000L
                val base = hist.filter { it.t >= from }
                if (base.isNotEmpty() && nowTs > base.last().t) base + PricePoint(nowTs, asset.price) else base
            }
        }
        val fcDisplay = (outlook?.forecast ?: detail.forecast)?.scaled(factor)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .padding(12.dp)
        ) {
            Text(
                if (pos != null) "روند: نقطه خرید شما، سود/زیان و پیش‌بینی " + Forecast.HORIZON_DAYS + " روز"
                else "روند قیمت و پیش‌بینی " + Forecast.HORIZON_DAYS + " روز",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            if (asset.isSimulated) {
                Text(
                    "داده این دارایی شبیه‌سازی‌شده است؛ نمودار و پیش‌بینی فقط نمایشی‌اند.",
                    fontSize = 10.sp,
                    color = Color(0xFFF7B731),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val opts = buildList {
                    if (pos != null) add("BUY" to "از خرید")
                    add("30" to "۱ ماه")
                    add("90" to "۳ ماه")
                }
                opts.forEach { (key, title) ->
                    val selected = range == key
                    Text(
                        title,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { range = key }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
            TrendChart(
                past = past,
                forecast = fcDisplay,
                fmt = fmtNative,
                buyPrice = pos?.let { it.avgBuyUsd * factor },
                buyTime = pos?.openedAt,
                stopPrice = pos?.let { it.stopLossUsd * factor },
                stopLabel = if (pos != null && pos.profitLockedPct > 0) "🔒 قفل سود" else "حد ضرر",
                takeProfit = pos?.let { it.takeProfitUsd * factor },
                height = 240.dp,
                modifier = Modifier.padding(top = 8.dp)
            )
            TrendLegend(showPosition = pos != null, showForecast = fcDisplay != null)
            Spacer(Modifier.height(8.dp))
            when {
                outlook != null -> OutlookSummary(outlook)
                fcDisplay != null -> ForecastSummary(fcDisplay)
                else -> Text(
                    "برای پیش‌بینی، تاریخچه قیمت کافی (حداقل ۲۰ روز) در دسترس نیست.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // موقعیت باز
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
                if (sig.newsLabel != null || sig.proFactors.isNotEmpty()) {
                    val sign = if (sig.newsAdj > 0) "+" else ""
                    val proSign = if (sig.proAdj > 0) "+" else ""
                    Text(
                        "امتیاز تکنیکال " + sig.technicalScore +
                            (if (sig.newsLabel != null) " " + sign + sig.newsAdj + " (اخبار)" else "") +
                            (if (sig.proFactors.isNotEmpty()) " " + proSign + sig.proAdj + " (تخصصی)" else "") +
                            " = " + sig.score,
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
                    m.rsi?.let { KVRow("آراس‌آی (۱۴)", Format.num(it, 1)) }
                    if (m.trendPct != null) KVRow("روند (فاصله میانگین‌ها)", Format.pct(m.trendPct))
                    if (m.momentum7 != null) KVRow("مومنتوم ۷ روزه", Format.pct(m.momentum7))
                    if (m.momentum30 != null) KVRow("مومنتوم ۳۰ روزه", Format.pct(m.momentum30))
                    m.volatility?.let { KVRow("نوسان روزانه", Format.num(it, 2) + "٪") }
                }
            }
        }

        // تحلیل تخصصی
        if (sig != null && sig.proFactors.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                Text(
                    "تحلیل تخصصی (اثر " + (if (sig.proAdj > 0) "+" else "") + sig.proAdj + ")",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    if (asset.market == com.saeidkazemi.trader.data.model.MarketKind.IR_STOCK)
                        "جریان پول حقیقی/حقوقی، قدرت خریدار، حجم مشکوک، P/E نسبت به گروه و وضعیت کل بازار"
                    else "ترس و طمع بازار، روند بیت‌کوین، قدرت نسبی، حجم غیرعادی و فشار دفتر سفارش",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                )
                sig.proFactors.forEach { f ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(f.title, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(f.value, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (f.note.isNotEmpty()) {
                                Text(f.note, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(
                            (if (f.impact > 0) "+" else "") + f.impact,
                            fontWeight = FontWeight.Bold,
                            color = sentimentColor(f.impact.toDouble()),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
                if (sig.proBlocked) {
                    Text(
                        "⛔ " + (sig.proBlockReason ?: "خرید متوقف شد"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = pnlColor(-1.0),
                        modifier = Modifier.padding(top = 8.dp)
                    )
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
