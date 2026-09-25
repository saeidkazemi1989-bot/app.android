package com.saeidkazemi.trader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
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
import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.ui.UiState
import com.saeidkazemi.trader.ui.components.EmptyBox
import com.saeidkazemi.trader.ui.components.MarketChip
import com.saeidkazemi.trader.ui.components.SimBadge
import com.saeidkazemi.trader.ui.components.pnlColor
import com.saeidkazemi.trader.util.Format

@Composable
fun MarketsScreen(
    state: UiState,
    onOpenAsset: (String) -> Unit
) {
    val tabs: List<Pair<String, MarketKind?>> = listOf(
        "همه" to null,
        "ارز دیجیتال" to MarketKind.CRYPTO,
        "بورس تهران" to MarketKind.IR_STOCK,
        "ارز خارجی" to MarketKind.FX,
        "طلا" to MarketKind.METAL
    )
    var selectedTab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }

    val kind = tabs[selectedTab].second
    val list = state.assets.filter { a ->
        (kind == null || a.market == kind) &&
            (query.isBlank() || a.name.contains(query, ignoreCase = true) || a.symbol.contains(query, ignoreCase = true))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 12.dp) {
            tabs.forEachIndexed { i, t ->
                Tab(
                    selected = selectedTab == i,
                    onClick = { selectedTab = i },
                    text = { Text(t.first, fontSize = 13.sp) }
                )
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("جستجوی نماد یا نام…") },
            singleLine = true
        )

        if (list.isEmpty()) {
            EmptyBox("موردی یافت نشد. اگر بازار خالی است، از داشبورد «به‌روزرسانی» بزنید.")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(list, key = { it.id }) { asset ->
                    MarketRow(asset = asset, onClick = { onOpenAsset(asset.id) })
                }
            }
        }
    }
}

@Composable
private fun MarketRow(asset: Asset, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(asset.symbol, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (asset.isSimulated) {
                    Spacer(Modifier.width(6.dp))
                    SimBadge()
                }
                Spacer(Modifier.width(6.dp))
                MarketChip(asset.market.faTitle)
            }
            Text(
                asset.name,
                fontSize = 11.sp,
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
            Text(priceText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            val change = asset.changePct24h
            Text(
                text = if (change != null) Format.pct(change) else "—",
                fontSize = 12.sp,
                color = pnlColor(change ?: 0.0),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}


