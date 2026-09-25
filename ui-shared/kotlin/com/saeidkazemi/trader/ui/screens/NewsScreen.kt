package com.saeidkazemi.trader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.ui.UiState
import com.saeidkazemi.trader.ui.components.EmptyBox
import com.saeidkazemi.trader.ui.components.NewsItemRow
import com.saeidkazemi.trader.ui.components.SentimentChip

/** صفحه اخبار: همه اخبار و اطلاعیه‌هایی که موتور برای تصمیم‌گیری بررسی کرده است. */
@Composable
fun NewsScreen(
    state: UiState,
    onRefreshNews: () -> Unit,
    onOpenAsset: (String) -> Unit
) {
    var filter by remember { mutableStateOf<MarketKind?>(null) }
    var onlyStrong by remember { mutableStateOf(false) }
    val feed = state.newsFeed.filter { e ->
        (filter == null || e.market == filter) &&
            (!onlyStrong || kotlin.math.abs(e.item.sentiment) >= 0.3)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text("اخبار و اطلاعیه‌های بررسی‌شده", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "در هر دور، اخبار ۱۰ فرصت برتر و همه دارایی‌های داخل پرتفوی بررسی می‌شود: " +
                        "برای سهام بورس تهران اطلاعیه‌های رسمی کدال (با وزن بیشتر) و اخبار فارسی، " +
                        "و برای ارز دیجیتال، ارز خارجی و طلا اخبار جهانی. هر خبر مثبت/منفی سنجیده و در امتیاز اثر داده می‌شود.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                    lineHeight = 18.sp
                )
                if (!state.settings.newsEnabled) {
                    Text(
                        "بررسی اخبار در تنظیمات خاموش است.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val digests = state.newsDigests.values
                    val pos = digests.count { it.available && it.adjustment > 0 }
                    val neg = digests.count { it.available && it.adjustment < 0 }
                    val blocked = digests.count { it.blockBuy }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SentimentChip(1.0, "مثبت: $pos")
                        Text(" ", fontSize = 10.sp)
                        SentimentChip(-1.0, "منفی: $neg")
                        if (blocked > 0) {
                            Text(" ", fontSize = 10.sp)
                            SentimentChip(-1.0, "توقف خرید: $blocked")
                        }
                    }
                    OutlinedButton(onClick = onRefreshNews, enabled = !state.newsRefreshing) {
                        Text(if (state.newsRefreshing) "در حال دریافت…" else "به‌روزرسانی اخبار", fontSize = 12.sp)
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("همه", fontSize = 11.sp) })
                FilterChip(
                    selected = filter == MarketKind.IR_STOCK,
                    onClick = { filter = MarketKind.IR_STOCK },
                    label = { Text("بورس/کدال", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = filter == MarketKind.CRYPTO,
                    onClick = { filter = MarketKind.CRYPTO },
                    label = { Text("کریپتو", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = filter == MarketKind.FX,
                    onClick = { filter = MarketKind.FX },
                    label = { Text("ارز", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = onlyStrong,
                    onClick = { onlyStrong = !onlyStrong },
                    label = { Text("فقط مهم", fontSize = 11.sp) }
                )
            }
        }

        if (feed.isEmpty()) {
            item {
                EmptyBox(
                    if (state.newsFeed.isEmpty()) "هنوز خبری دریافت نشده؛ پس از اولین دور تحلیل یا با دکمه «به‌روزرسانی اخبار» نمایش داده می‌شود."
                    else "خبری با این فیلتر وجود ندارد."
                )
            }
        } else {
            items(feed) { e ->
                NewsItemRow(
                    item = e.item,
                    symbol = e.symbol,
                    onSymbolClick = { onOpenAsset(e.assetId) }
                )
            }
        }
    }
}
