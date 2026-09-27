package com.saeidkazemi.trader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.saeidkazemi.trader.core.TraderController
import com.saeidkazemi.trader.sync.SyncRole
import com.saeidkazemi.trader.sync.SyncStatus

private val OkGreen = Color(0xFF2FB47C)
private val WarnAmber = Color(0xFFE0A526)

/** «۲۰ ثانیه پیش» و … */
fun agoFa(ts: Long): String {
    if (ts <= 0L) return "هنوز نه"
    val s = (System.currentTimeMillis() - ts) / 1000
    return when {
        s < 10 -> "همین الان"
        s < 60 -> "$s ثانیه پیش"
        s < 3600 -> (s / 60).toString() + " دقیقه پیش"
        s < 86400 -> (s / 3600).toString() + " ساعت پیش"
        else -> (s / 86400).toString() + " روز پیش"
    }
}

/** نوار وضعیت اتصال در داشبورد. */
@Composable
fun SyncBanner(sync: SyncStatus) {
    val text: String
    val color: Color
    when (sync.role) {
        SyncRole.FOLLOWER -> {
            if (sync.lastSyncAt > 0 && sync.connected) {
                text = "حالت آینه • وضعیت «" + sync.peerDevice.ifBlank { "دستگاه اصلی" } + "» از طریق " + sync.channel +
                    " • آخرین همگام‌سازی " + agoFa(sync.lastSyncAt) + ". معاملات روی همان دستگاه انجام می‌شود."
                color = OkGreen
            } else {
                text = "حالت آینه • " + (if (sync.lastSyncAt > 0) "آخرین وضعیت دریافتی مال " + agoFa(sync.lastSyncAt) + " است. " else "") +
                    sync.message.ifBlank { "در حال اتصال به دستگاه اصلی…" }
                color = WarnAmber
            }
        }
        SyncRole.HOST -> {
            if (!sync.connected) return
            text = "دستگاه دیگر وصل است (" + sync.channel.ifBlank { "شبکه محلی" } + ") • آخرین تماس " + agoFa(sync.lastPeerSeenAt)
            color = OkGreen
        }
        else -> return
    }
    Text(
        text,
        fontSize = 12.sp,
        color = color,
        lineHeight = 19.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(10.dp)
    )
}

/** کارت تنظیمات اتصال اندروید و ویندوز. */
@Composable
fun SyncSettingsCard(sync: SyncStatus, isDesktop: Boolean, vm: TraderController) {
    var wantFollower by remember(sync.role) { mutableStateOf(sync.role == SyncRole.FOLLOWER) }
    var codeInput by remember(sync.role, sync.code) { mutableStateOf(if (sync.role == SyncRole.FOLLOWER) sync.code else "") }
    var hostInput by remember(sync.lastHost) { mutableStateOf(sync.lastHost) }
    var relayInput by remember(sync.relayUrl) { mutableStateOf(sync.relayUrl) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Text("اتصال اندروید و ویندوز", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(
            "یک دستگاه «اصلی» است و معامله می‌کند (پیشنهاد: گوشی)؛ دستگاه دیگر «آینه» است: همان سرمایه، خریدها، ژورنال، " +
                "بک‌تست و فعالیت ربات را نشان می‌دهد و خرید/فروش دستی و تغییر تنظیماتش روی دستگاه اصلی اجرا می‌شود. " +
                "اتصال اول مستقیم در همان وای‌فای/مودم برقرار می‌شود و اگر نشد از طریق اینترنت. همه اطلاعات با کد اتصال رمزنگاری می‌شود.",
            fontSize = 11.sp,
            color = muted,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = sync.role == SyncRole.OFF && !wantFollower,
                onClick = { wantFollower = false; if (sync.role != SyncRole.OFF) vm.syncTurnOff() },
                label = { Text("مستقل", fontSize = 12.sp) }
            )
            FilterChip(
                selected = sync.role == SyncRole.HOST,
                onClick = { wantFollower = false; if (sync.role != SyncRole.HOST) vm.syncBecomeHost() },
                label = { Text("دستگاه اصلی", fontSize = 12.sp) }
            )
            FilterChip(
                selected = sync.role == SyncRole.FOLLOWER || wantFollower,
                onClick = { wantFollower = true },
                label = { Text("آینه", fontSize = 12.sp) }
            )
        }

        when {
            sync.role == SyncRole.HOST -> {
                Text("کد اتصال این دستگاه:", fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                SelectionContainer {
                    Text(
                        sync.code,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                Text(
                    "در " + (if (isDesktop) "گوشی" else "نسخه ویندوز") + ": تنظیمات ← اتصال اندروید و ویندوز ← «آینه» را بزنید و همین کد را وارد کنید.",
                    fontSize = 11.sp, color = muted, lineHeight = 18.sp
                )
                if (sync.localAddresses.isNotEmpty()) {
                    Text(
                        "نشانی در شبکه محلی: " + sync.localAddresses.joinToString("، ") + " (معمولاً خودکار پیدا می‌شود)",
                        fontSize = 11.sp, color = muted, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Text(
                    if (sync.connected) "✓ دستگاه دیگر وصل است (" + sync.channel.ifBlank { "شبکه محلی" } + ")، آخرین تماس " + agoFa(sync.lastPeerSeenAt)
                    else if (sync.lastPeerSeenAt > 0) "دستگاه دیگر آخرین بار " + agoFa(sync.lastPeerSeenAt) + " وصل بود."
                    else "هنوز دستگاهی وصل نشده است.",
                    fontSize = 12.sp,
                    color = if (sync.connected) OkGreen else muted,
                    modifier = Modifier.padding(top = 6.dp)
                )
                OutlinedButton(onClick = { vm.syncNewCode() }, modifier = Modifier.padding(top = 6.dp)) {
                    Text("ساخت کد جدید (قطع دستگاه‌های قبلی)", fontSize = 12.sp)
                }
            }
            sync.role == SyncRole.FOLLOWER || wantFollower -> {
                OutlinedTextField(
                    value = codeInput,
                    onValueChange = { codeInput = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("کد اتصال دستگاه اصلی (مثل ABCDE-12345)") },
                    singleLine = true
                )
                Button(onClick = { vm.syncBecomeFollower(codeInput) }, modifier = Modifier.padding(top = 6.dp)) {
                    Text(if (sync.role == SyncRole.FOLLOWER) "ذخیره و اتصال دوباره" else "اتصال و نمایش وضعیت دستگاه اصلی")
                }
                if (sync.role != SyncRole.FOLLOWER) {
                    Text(
                        "با اتصال، این دستگاه دیگر خودش معامله نمی‌کند و سرمایه، خریدها و ژورنال دستگاه اصلی جایگزین اطلاعات فعلی این دستگاه می‌شود.",
                        fontSize = 11.sp, color = WarnAmber, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp)
                    )
                } else {
                    val st = when {
                        sync.connected && sync.lastSyncAt > 0 ->
                            "✓ وصل به «" + sync.peerDevice.ifBlank { "دستگاه اصلی" } + "» از طریق " + sync.channel + " • آخرین همگام‌سازی " + agoFa(sync.lastSyncAt)
                        else -> sync.message.ifBlank { "در حال اتصال…" } +
                            (if (sync.lastSyncAt > 0) " (آخرین وضعیت دریافتی: " + agoFa(sync.lastSyncAt) + ")" else "")
                    }
                    Text(
                        st, fontSize = 12.sp, lineHeight = 19.sp,
                        color = if (sync.connected) OkGreen else WarnAmber,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    if (sync.peerVersion.isNotEmpty()) {
                        Text(
                            "نسخه دستگاه اصلی: " + sync.peerVersion + (if (sync.peerLastCycleAt > 0) " • آخرین دور بررسی آن: " + agoFa(sync.peerLastCycleAt) else "") +
                                (if (sync.pendingCommands > 0) " • " + sync.pendingCommands + " فرمان در انتظار اجرا" else ""),
                            fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        OutlinedButton(onClick = { vm.syncNow() }) { Text("همگام‌سازی الان", fontSize = 12.sp) }
                        OutlinedButton(onClick = { vm.syncTurnOff() }) { Text("قطع اتصال", fontSize = 12.sp) }
                    }
                    OutlinedTextField(
                        value = hostInput,
                        onValueChange = { hostInput = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("نشانی دستگاه اصلی در شبکه (اختیاری، مثل 192.168.1.5)") },
                        singleLine = true
                    )
                    OutlinedButton(onClick = { vm.syncSetHost(hostInput) }, modifier = Modifier.padding(top = 4.dp)) {
                        Text("ذخیره نشانی", fontSize = 12.sp)
                    }
                }
            }
            else -> {
                Text(
                    "این دستگاه مستقل کار می‌کند. برای اتصال: روی یک دستگاه «دستگاه اصلی» و روی دیگری «آینه» را انتخاب کنید.",
                    fontSize = 11.sp, color = muted, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        if (sync.role != SyncRole.OFF) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("اتصال مستقیم در همان وای‌فای/مودم", fontSize = 12.sp, modifier = Modifier.weight(1f))
                Switch(checked = sync.lanEnabled, onCheckedChange = { vm.syncSetLan(it) })
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("اتصال از طریق اینترنت (وقتی در یک شبکه نیستید)", fontSize = 12.sp, modifier = Modifier.weight(1f))
                Switch(checked = sync.relayEnabled, onCheckedChange = { vm.syncSetRelay(it) })
            }
            if (sync.relayEnabled) {
                Text(
                    "اتصال اینترنتی از سرویس رایگان ntfy استفاده می‌کند (اطلاعات رمزنگاری‌شده است و سرور چیزی از آن نمی‌فهمد). " +
                        "ممکن است بدون فیلترشکن در دسترس نباشد؛ وضعیت با تأخیر چنددقیقه‌ای و فقط وقتی دستگاه آینه باز است فرستاده می‌شود.",
                    fontSize = 10.sp, color = muted, lineHeight = 16.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    OutlinedTextField(
                        value = relayInput,
                        onValueChange = { relayInput = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("سرور واسط", fontSize = 11.sp) },
                        singleLine = true
                    )
                    OutlinedButton(onClick = { vm.syncSetRelayUrl(relayInput) }, modifier = Modifier.padding(start = 6.dp)) {
                        Text("ذخیره", fontSize = 12.sp)
                    }
                }
            }
            if (sync.role == SyncRole.HOST && sync.message.isNotBlank()) {
                Text(sync.message, fontSize = 11.sp, color = WarnAmber, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
