package com.saeidkazemi.trader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saeidkazemi.trader.news.NewsDigest
import com.saeidkazemi.trader.news.NewsItem
import com.saeidkazemi.trader.ui.theme.AccentGold
import com.saeidkazemi.trader.ui.theme.LossRed
import com.saeidkazemi.trader.ui.theme.ProfitGreen
import com.saeidkazemi.trader.util.Jalali

fun sentimentColor(score: Double): Color = when {
    score >= 0.1 -> ProfitGreen
    score <= -0.1 -> LossRed
    else -> Color(0xFF9AA7C0)
}

/** زمان نسبی خوانا («۳ ساعت پیش») یا تاریخ شمسی. */
fun relativeTime(ts: Long?): String {
    if (ts == null) return "زمان نامشخص"
    val diff = System.currentTimeMillis() - ts
    val min = diff / 60_000L
    return when {
        min < 1 -> "همین حالا"
        min < 60 -> "$min دقیقه پیش"
        min < 60 * 24 -> "${min / 60} ساعت پیش"
        min < 60 * 24 * 7 -> "${min / (60 * 24)} روز پیش"
        else -> Jalali.format(ts)
    }
}

@Composable
fun SentimentChip(score: Double, label: String) {
    val c = sentimentColor(score)
    Box(
        modifier = Modifier
            .background(c.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text = label, fontSize = 10.sp, color = c, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun OfficialChip() {
    Box(
        modifier = Modifier
            .background(AccentGold.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text = "رسمی • کدال", fontSize = 10.sp, color = AccentGold, fontWeight = FontWeight.Bold)
    }
}

/** یک خبر؛ با کلیک، خبر اصلی در مرورگر باز می‌شود. */
@Composable
fun NewsItemRow(
    item: NewsItem,
    symbol: String? = null,
    onSymbolClick: (() -> Unit)? = null
) {
    val uri = LocalUriHandler.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .clickable {
                try {
                    if (item.url.isNotBlank()) uri.openUri(item.url)
                } catch (_: Exception) {
                }
            }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (symbol != null) {
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                        .let { m -> if (onSymbolClick != null) m.clickable { onSymbolClick() } else m }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(symbol, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(6.dp))
            }
            SentimentChip(item.sentiment, item.sentimentLabel)
            if (item.isOfficial) {
                Spacer(Modifier.width(6.dp))
                OfficialChip()
            }
            Spacer(Modifier.weight(1f))
            Text(
                relativeTime(item.publishedAt),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            item.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
            lineHeight = 20.sp
        )
        if (item.summary.isNotBlank() && item.summary != item.title) {
            Text(
                item.summary,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
                lineHeight = 18.sp
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(item.publisher, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.matched.isNotEmpty()) {
                Text(
                    "کلیدواژه: " + item.matched.take(3).joinToString("، "),
                    fontSize = 10.sp,
                    color = sentimentColor(item.sentiment)
                )
            }
        }
    }
}

/** خلاصه اثر اخبار روی یک دارایی. */
@Composable
fun NewsDigestSummary(digest: NewsDigest) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SentimentChip(digest.score, digest.label)
            Spacer(Modifier.width(8.dp))
            val sign = if (digest.adjustment > 0) "+" else ""
            Text(
                "اثر روی امتیاز: " + sign + digest.adjustment,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = sentimentColor(digest.adjustment.toDouble())
            )
        }
        if (digest.blockReason != null) {
            Text(
                "⛔ " + digest.blockReason,
                fontSize = 12.sp,
                color = LossRed,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        val src = mutableListOf<String>()
        if (digest.sourcesOk.isNotEmpty()) src.add("منابع: " + digest.sourcesOk.joinToString("، "))
        if (digest.sourcesFailed.isNotEmpty()) src.add("در دسترس نبود: " + digest.sourcesFailed.joinToString("، "))
        if (src.isNotEmpty()) {
            Text(
                src.joinToString(" • "),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
