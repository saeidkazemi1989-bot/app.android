package com.saeidkazemi.trader.sync

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.saeidkazemi.trader.core.AppContainer
import com.saeidkazemi.trader.core.AppVersion
import com.saeidkazemi.trader.data.model.AppSettings
import com.saeidkazemi.trader.data.model.JournalEntry
import com.saeidkazemi.trader.data.remote.Http
import com.saeidkazemi.trader.util.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * اتصال اندروید و ویندوز.
 *
 * - «دستگاه اصلی» (پیش‌فرض: گوشی) معامله می‌کند و وضعیت کاملش (سرمایه، خریدها، ژورنال، بک‌تست، فعالیت ربات،
 *   تنظیمات) را رمزنگاری‌شده در اختیار دستگاه دیگر می‌گذارد و فرمان‌های آن را اجرا می‌کند.
 * - «آینه» (مثلاً ویندوز) همان وضعیت را نشان می‌دهد و خودش معامله نمی‌کند؛ خرید/فروش دستی، تغییر تنظیمات،
 *   تقسیم سرمایه و بک‌تست را برای دستگاه اصلی می‌فرستد.
 *
 * مسیر اتصال: اول مستقیم در همان شبکه (وای‌فای/مودم)، اگر نشد از طریق اینترنت (سرور واسط ntfy).
 */
class SyncManager(private val container: AppContainer) {

    private val store get() = container.store
    private val engine get() = container.tradeEngine
    private val gson: Gson = GsonBuilder().serializeSpecialFloatingPointValues().create()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status

    /** رویدادها برای صفحه‌ها: "data" (وضعیت عوض شد) یا "msg:<متن>" (نتیجه فرمان). */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events

    @Volatile private var cfg = SyncConfig()
    @Volatile private var crypto: SyncCrypto? = null
    private var lan: LanServer? = null
    private var job: Job? = null
    private var started = false

    /** آینه: آخرین وضعیت دریافت‌شده از دستگاه اصلی. */
    @Volatile var mirror: SyncSnapshot? = null
        private set

    val isFollower: Boolean get() = cfg.role == SyncRole.FOLLOWER && crypto != null
    val isHost: Boolean get() = cfg.role == SyncRole.HOST && crypto != null

    private val http by lazy {
        Http.client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    // ---------------- راه‌اندازی و تنظیم ----------------

    /** @param defaultRole نقش پیش‌فرض بار اول (گوشی: HOST، ویندوز: OFF). */
    fun start(defaultRole: String, deviceName: String) {
        if (started) return
        started = true
        var c = try { store.loadSync() } catch (_: Exception) { null } ?: SyncConfig()
        if (c.role.isEmpty()) {
            c = c.copy(role = defaultRole, code = if (defaultRole == SyncRole.HOST && c.code.isEmpty()) SyncCrypto.newCode() else c.code)
        }
        if (c.deviceName.isEmpty()) c = c.copy(deviceName = deviceName)
        if (c.role == SyncRole.HOST && c.code.isEmpty()) c = c.copy(code = SyncCrypto.newCode())
        save(c)
        scope.launch {
            engine.reports.collect { r ->
                lastNotes = r.notes
                lastCycleAt = r.ts
            }
        }
        restart()
    }

    private fun save(c: SyncConfig) {
        cfg = c
        try { store.saveSync(c) } catch (_: Exception) { }
    }

    private fun restart() {
        job?.cancel()
        job = null
        lan?.stop()
        lan = null
        val c = cfg
        crypto = if (c.role != SyncRole.OFF && SyncCrypto.isValid(c.code)) SyncCrypto(c.code) else null
        engine.followerMode = c.role == SyncRole.FOLLOWER && crypto != null
        if (c.role != SyncRole.FOLLOWER) mirror = null
        channel = ""
        connected = false
        message = ""
        when {
            crypto == null -> message = if (c.role == SyncRole.OFF) "" else "کد اتصال تنظیم نشده است."
            c.role == SyncRole.HOST -> {
                if (c.lanEnabled) {
                    val srv = LanServer(::handleLan)
                    if (srv.start()) lan = srv else message = "راه‌اندازی اتصال شبکه محلی ممکن نشد (درگاه مشغول است)."
                }
                relayCmdSince = (System.currentTimeMillis() / 1000).toString()
                job = scope.launch { hostLoop() }
            }
            c.role == SyncRole.FOLLOWER -> job = scope.launch { followerLoop() }
        }
        publishStatus()
    }

    fun becomeHost() {
        save(cfg.copy(role = SyncRole.HOST, code = if (SyncCrypto.isValid(cfg.code)) SyncCrypto.pretty(cfg.code) else SyncCrypto.newCode()))
        restart()
    }

    /** تبدیل به آینه با کد دستگاه اصلی. */
    fun becomeFollower(code: String): Boolean {
        if (!SyncCrypto.isValid(code)) return false
        save(cfg.copy(role = SyncRole.FOLLOWER, code = SyncCrypto.pretty(code)))
        restart()
        return true
    }

    fun turnOff() {
        save(cfg.copy(role = SyncRole.OFF))
        restart()
        _events.tryEmit("data")
    }

    fun regenerateCode() {
        save(cfg.copy(code = SyncCrypto.newCode()))
        restart()
    }

    fun setLan(on: Boolean) { save(cfg.copy(lanEnabled = on)); restart() }

    fun setRelay(on: Boolean) { save(cfg.copy(relayEnabled = on)); restart() }

    fun setRelayUrl(url: String) {
        val u = url.trim().ifEmpty { "https://ntfy.sh" }
        save(cfg.copy(relayUrl = if (u.startsWith("http")) u else "https://$u"))
        restart()
    }

    /** نشانی دستی دستگاه اصلی (مثلاً 192.168.1.5). */
    fun setManualHost(addr: String) {
        val a = addr.trim()
        save(cfg.copy(lastHost = if (a.isEmpty() || ':' in a) a else a + ":" + LanServer.PORTS[0]))
        restart()
    }

    /** همگام‌سازی فوری (آینه). */
    fun syncNow() {
        if (isFollower) scope.launch { pollOnce(forceRelay = true) }
    }

    // ---------------- وضعیت ----------------

    @Volatile private var channel = ""
    @Volatile private var connected = false
    @Volatile private var lastSyncAt = 0L
    @Volatile private var lastPeerSeenAt = 0L
    @Volatile private var message = ""
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun publishStatus() {
        val c = cfg
        val m = mirror
        _status.value = SyncStatus(
            role = c.role.ifEmpty { SyncRole.OFF },
            code = if (c.role == SyncRole.HOST || c.role == SyncRole.FOLLOWER) SyncCrypto.pretty(c.code) else "",
            lanEnabled = c.lanEnabled,
            relayEnabled = c.relayEnabled,
            relayUrl = c.relayUrl,
            localAddresses = if (c.role == SyncRole.HOST) lan?.port?.takeIf { it > 0 }?.let { p -> LanServer.localAddresses().map { "$it:$p" } } ?: emptyList() else emptyList(),
            lastHost = c.lastHost,
            channel = channel,
            connected = connected,
            lastSyncAt = lastSyncAt,
            peerDevice = m?.device ?: "",
            peerVersion = m?.appVersion ?: "",
            peerLastCycleAt = m?.lastCycleAt ?: 0L,
            lastPeerSeenAt = lastPeerSeenAt,
            message = message,
            pendingCommands = pending.size
        )
    }

    // ---------------- دستگاه اصلی ----------------

    @Volatile private var lastNotes: List<String> = emptyList()
    @Volatile private var lastCycleAt: Long = 0L
    private val results = ArrayDeque<SyncResult>()
    private val seen = LinkedHashMap<String, Long>()
    @Volatile private var relayCmdSince: String = ""
    @Volatile private var lastPublishedHash = ""
    @Volatile private var lastPublishAt = 0L
    @Volatile private var lastHelloAt = 0L
    @Volatile private var forcePublish = false
    private var snapCache: Pair<Long, SyncSnapshot>? = null

    /** ساخت وضعیت کامل برای ارسال (توکن نوبیتکس هرگز فرستاده نمی‌شود). */
    fun buildSnapshot(maxJournal: Int = Int.MAX_VALUE): SyncSnapshot {
        val s = store.loadSettings()
        val all = engine.journal.all()
        val journal = if (all.size > maxJournal) all.take(maxJournal) else all
        val res = synchronized(results) { results.toList() }
        val base = SyncSnapshot(
            device = cfg.deviceName,
            appVersion = AppVersion.NAME,
            settings = s.copy(nobitexToken = ""),
            hostHasToken = s.nobitexToken.isNotBlank(),
            account = container.broker.account(),
            journal = journal,
            journalTrimmed = all.size - journal.size,
            backtest = engine.backtest,
            activity = engine.lastActivity,
            notes = lastNotes,
            lastCycleAt = lastCycleAt,
            results = res
        )
        val hash = SyncCrypto.hex(SyncCrypto.sha256(gson.toJson(base).toByteArray())).take(24)
        return base.copy(hash = hash, createdAt = System.currentTimeMillis())
    }

    private fun cachedSnapshot(): SyncSnapshot {
        val now = System.currentTimeMillis()
        synchronized(this) {
            snapCache?.let { (t, s) -> if (now - t < 3000) return s }
            val s = buildSnapshot()
            snapCache = now to s
            return s
        }
    }

    private fun handleLan(method: String, path: String, query: Map<String, String>, body: ByteArray): LanServer.Response {
        val c = crypto ?: return LanServer.Response(404)
        return when (path) {
            "/my/ping" -> LanServer.Response(200, ("MY1 " + c.lanId + " " + cfg.deviceName).toByteArray(), "text/plain; charset=utf-8")
            "/my/state" -> {
                lastPeerSeenAt = System.currentTimeMillis()
                val s = cachedSnapshot()
                if (query["h"] == s.hash) LanServer.Response(204)
                else LanServer.Response(200, c.seal(gson.toJson(s).toByteArray()))
            }
            "/my/cmd" -> {
                if (method != "POST") return LanServer.Response(404)
                val plain = c.open(body) ?: return LanServer.Response(403)
                val cmd = try { gson.fromJson(String(plain, Charsets.UTF_8), SyncCommand::class.java) } catch (_: Exception) { null }
                    ?: return LanServer.Response(403)
                lastPeerSeenAt = System.currentTimeMillis()
                val r = runBlocking { executeOnce(cmd) }
                synchronized(this) { snapCache = null }
                LanServer.Response(200, c.seal(gson.toJson(r).toByteArray()))
            }
            else -> LanServer.Response(404)
        }
    }

    private suspend fun hostLoop() {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val c = cfg
            val cr = crypto ?: break
            if (c.relayEnabled) {
                try {
                    pollRelayCommands(c, cr)
                    maybePublishRelay(c, cr)
                    if (message.startsWith("اینترنت")) message = ""
                } catch (e: Exception) {
                    message = "اینترنت (سرور واسط) در دسترس نیست: " + (e.message ?: "")
                }
            }
            connected = System.currentTimeMillis() - lastPeerSeenAt < 90_000
            channel = when {
                !connected -> ""
                System.currentTimeMillis() - lastHelloAt < 90_000 -> "اینترنت"
                else -> "شبکه محلی"
            }
            publishStatus()
            delay(15_000)
        }
    }

    private suspend fun pollRelayCommands(c: SyncConfig, cr: SyncCrypto) {
        val relay = Relay(c.relayUrl)
        val msgs = relay.poll(cr.topic + "c", relayCmdSince.ifEmpty { (System.currentTimeMillis() / 1000).toString() })
        for (m in msgs) {
            relayCmdSince = m.id
            val text = m.text ?: continue
            val plain = try { cr.open(Base64.getDecoder().decode(text.trim())) } catch (_: Exception) { null } ?: continue
            val cmd = try { gson.fromJson(String(plain, Charsets.UTF_8), SyncCommand::class.java) } catch (_: Exception) { null } ?: continue
            val now = System.currentTimeMillis()
            lastPeerSeenAt = now
            if (cmd.type == "hello") {
                if (now - lastHelloAt > 30 * 60_000L) forcePublish = true
                lastHelloAt = now
                continue
            }
            lastHelloAt = now
            executeOnce(cmd)
            forcePublish = true
        }
    }

    private fun maybePublishRelay(c: SyncConfig, cr: SyncCrypto) {
        val now = System.currentTimeMillis()
        // فقط وقتی آینه‌ای در ۴۰ دقیقه اخیر از طریق اینترنت اعلام حضور کرده (صرفه‌جویی در اینترنت گوشی)
        if (now - lastHelloAt > 40 * 60_000L) return
        var snap = buildSnapshot(RELAY_JOURNAL)
        val changed = snap.hash != lastPublishedHash
        val due = (forcePublish && now - lastPublishAt >= 30_000) ||
            (changed && now - lastPublishAt >= RELAY_MIN_GAP_MS) ||
            now - lastPublishAt >= RELAY_HEARTBEAT_MS
        if (!due) return
        var bytes = cr.seal(gson.toJson(snap).toByteArray())
        if (bytes.size > RELAY_MAX_BYTES) {
            snap = buildSnapshot(100)
            bytes = cr.seal(gson.toJson(snap).toByteArray())
        }
        Relay(c.relayUrl).publishBinary(cr.topic + "s", bytes)
        lastPublishedHash = snap.hash
        lastPublishAt = now
        forcePublish = false
    }

    /** اجرای فرمان فقط یک بار (در برابر ارسال تکراری یا قدیمی). */
    private suspend fun executeOnce(cmd: SyncCommand): SyncResult {
        val now = System.currentTimeMillis()
        synchronized(seen) {
            seen[cmd.id]?.let { return SyncResult(cmd.id, now, true, "این فرمان قبلاً اجرا شده بود.") }
            if (cmd.id.isEmpty() || now - cmd.at > 15 * 60_000L || cmd.at - now > 5 * 60_000L) {
                return SyncResult(cmd.id, now, false, "فرمان منقضی شده بود و اجرا نشد.")
            }
            seen[cmd.id] = now
            while (seen.size > 300) seen.remove(seen.keys.first())
        }
        val r = try {
            val (ok, msg) = execute(cmd)
            SyncResult(cmd.id, now, ok, msg)
        } catch (e: Exception) {
            SyncResult(cmd.id, now, false, "اجرای فرمان روی دستگاه اصلی ناموفق بود: " + (e.message ?: ""))
        }
        synchronized(results) {
            results.addLast(r)
            while (results.size > 15) results.removeFirst()
        }
        _events.tryEmit("data")
        _events.tryEmit("msg:" + (if (cmd.from.isNotEmpty()) "از " + cmd.from + ": " else "از دستگاه متصل: ") + r.message)
        return r
    }

    private suspend fun execute(cmd: SyncCommand): Pair<Boolean, String> {
        val a = cmd.args ?: emptyMap()
        return when (cmd.type) {
            "settings" -> {
                val patch = cmd.patch?.let { com.google.gson.JsonParser.parseString(it).asJsonObject } ?: return false to "تغییری نبود."
                val cur = gson.toJsonTree(store.loadSettings()).asJsonObject
                for ((k, v) in patch.entrySet()) if (k !in LOCAL_KEYS) cur.add(k, v)
                store.saveSettings(gson.fromJson(cur, AppSettings::class.java))
                true to (a["msg"]?.takeIf { it.isNotBlank() } ?: "تنظیمات روی دستگاه اصلی ذخیره شد.")
            }
            "allocations" -> {
                val alloc = listOf("CRYPTO", "IR_STOCK", "METAL", "FX").associateWith { (a[it] ?: "0").toDoubleOrNull() ?: 0.0 }
                val settings = store.loadSettings().copy(allocations = alloc)
                store.saveSettings(settings)
                val assets = container.marketDataService.cachedAssets().associateBy { it.id }
                val positions = container.broker.account().positions
                val acc = container.broker.reallocate(alloc) { p ->
                    val asset = assets[p.assetId]
                    val price = asset?.let { engine.usdPriceFor(it, settings, positions) }?.takeIf { it.isFinite() && it > 0 } ?: p.avgBuyUsd
                    price * p.qty
                }
                true to ("تقسیم جدید سرمایه روی دستگاه اصلی اعمال شد" + (if (acc.positions.isNotEmpty()) "؛ " + acc.positions.size + " خرید باز دست نخورد." else "."))
            }
            "capital" -> {
                val usd = a["usd"]?.toDoubleOrNull() ?: return false to "مبلغ نامعتبر."
                val s0 = store.loadSettings()
                container.broker.adjustCapital(usd, s0.allocations)
                    ?: return false to "نقد آزاد برای این کاهش سرمایه کافی نیست؛ خریدها بسته نشدند."
                store.saveSettings(s0.copy(capitalUsd = usd))
                true to ("سرمایه روی دستگاه اصلی به " + Format.num(usd, 0) + " دلار تغییر کرد؛ خریدهای باز حفظ شدند.")
            }
            "reset" -> {
                val usd = a["usd"]?.toDoubleOrNull() ?: return false to "مبلغ نامعتبر."
                val settings = store.loadSettings().copy(capitalUsd = usd)
                store.saveSettings(settings)
                container.broker.reset(usd, settings.allocations)
                engine.journal.clear()
                true to "حساب دمو روی دستگاه اصلی با سرمایه جدید از نو ساخته شد (با تأیید شما)."
            }
            "buy" -> {
                val id = a["asset"] ?: return false to "دارایی مشخص نیست."
                val usd = a["usd"]?.toDoubleOrNull() ?: return false to "مبلغ نامعتبر."
                true to engine.manualBuy(id, usd)
            }
            "sell" -> {
                val id = a["asset"] ?: return false to "دارایی مشخص نیست."
                true to engine.manualSell(id)
            }
            "backtest" -> {
                if (engine.backtestRunning) true to "بک‌تست روی دستگاه اصلی در حال اجراست."
                else {
                    scope.launch { try { engine.runBacktest() } catch (_: Exception) { } ; _events.tryEmit("data") }
                    true to "بک‌تست روی دستگاه اصلی شروع شد؛ نتیجه چند دقیقه بعد همین‌جا دیده می‌شود."
                }
            }
            "refresh" -> {
                scope.launch { try { engine.runCycle("remote") } catch (_: Exception) { } }
                true to "دستگاه اصلی یک دور بررسی بازار را شروع کرد."
            }
            else -> false to "فرمان ناشناخته (نسخه‌ها را یکسان کنید)."
        }
    }

    // ---------------- آینه ----------------

    @Volatile private var appliedHash = ""
    @Volatile private var appliedAt = 0L
    @Volatile private var relayStateSince = ""
    @Volatile private var lastScanAt = 0L
    @Volatile private var lastRelayPollAt = 0L
    @Volatile private var lastRelayHelloAt = 0L
    @Volatile private var lanOkAt = 0L

    private suspend fun followerLoop() {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val ok = try { pollOnce(forceRelay = false) } catch (_: Exception) { false }
            delay(if (ok) 6_000 else 10_000)
        }
    }

    /** یک بار تلاش برای دریافت وضعیت: اول شبکه محلی، بعد اینترنت. */
    private suspend fun pollOnce(forceRelay: Boolean): Boolean {
        val c = cfg
        val cr = crypto ?: return false
        var ok = false
        var err = ""
        if (c.lanEnabled) {
            if (c.lastHost.isNotEmpty()) ok = lanFetch(c.lastHost, cr)
            val now = System.currentTimeMillis()
            if (!ok && (now - lastScanAt > 120_000 || forceRelay)) {
                lastScanAt = now
                val found = scan(cr)
                if (found != null) {
                    if (found != cfg.lastHost) save(cfg.copy(lastHost = found))
                    ok = lanFetch(found, cr)
                }
            }
            if (ok) lanOkAt = System.currentTimeMillis()
        }
        if (ok) {
            channel = "شبکه محلی"
        } else if (c.relayEnabled) {
            val now = System.currentTimeMillis()
            try {
                val relay = Relay(c.relayUrl)
                if (now - lastRelayHelloAt > 25 * 60_000L || forceRelay && now - lastRelayHelloAt > 60_000) {
                    sendRelay(relay, cr, SyncCommand(UUID.randomUUID().toString(), now, "hello", from = c.deviceName))
                    lastRelayHelloAt = now
                    // دستگاه اصلی چند ثانیه بعد وضعیت را می‌فرستد
                    lastRelayPollAt = now - RELAY_POLL_MS + 25_000
                }
                if (now - lastRelayPollAt >= RELAY_POLL_MS || forceRelay) {
                    lastRelayPollAt = now
                    if (relayFetch(relay, cr)) ok = true
                }
                if (System.currentTimeMillis() - lastSyncAt < 30 * 60_000L && channel == "اینترنت") ok = true
                if (lastSyncAt == 0L || System.currentTimeMillis() - lastSyncAt > 30 * 60_000L) {
                    err = "در انتظار دستگاه اصلی از طریق اینترنت… (برنامه روی گوشی باز/در حال اجرا باشد)"
                }
                if (ok) channel = "اینترنت"
            } catch (e: Exception) {
                err = "سرور واسط اینترنتی در دسترس نیست (" + (e.message ?: "") + ")"
            }
        } else if (!c.lanEnabled) {
            err = "هر دو مسیر اتصال خاموش است."
        }
        if (!ok && err.isEmpty()) err = "دستگاه اصلی در این شبکه پیدا نشد."
        connected = ok || System.currentTimeMillis() - lastSyncAt < 3 * 60_000L && lastSyncAt > 0
        if (!connected) channel = ""
        message = if (ok) "" else err
        publishStatus()
        return ok
    }

    private suspend fun lanFetch(host: String, cr: SyncCrypto): Boolean = try {
        val req = Request.Builder().url("http://$host/my/state?h=" + appliedHash).get().build()
        http.newCall(req).execute().use { r ->
            when (r.code) {
                204 -> { lastSyncAt = System.currentTimeMillis(); true }
                200 -> {
                    val plain = cr.open(r.body?.bytes() ?: ByteArray(0))
                    if (plain == null) false
                    else {
                        val snap = gson.fromJson(String(plain, Charsets.UTF_8), SyncSnapshot::class.java)
                        applySnapshot(snap)
                        true
                    }
                }
                else -> false
            }
        }
    } catch (_: Exception) {
        false
    }

    /** جستجوی دستگاه اصلی در شبکه محلی (فقط اتصال خروجی؛ نیازی به باز کردن فایروال ویندوز نیست). */
    private suspend fun scan(cr: SyncCrypto): String? {
        val cands = LanServer.subnetCandidates()
        if (cands.isEmpty()) return null
        val gate = Semaphore(64)
        val found = java.util.concurrent.atomic.AtomicReference<String?>(null)
        kotlinx.coroutines.coroutineScope {
            cands.map { ip ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        if (found.get() != null) return@withPermit
                        for (p in LanServer.PORTS) {
                            if (!LanServer.isReachable(ip, p, 350)) continue
                            if (ping("$ip:$p", cr)) { found.compareAndSet(null, "$ip:$p"); return@withPermit }
                        }
                    }
                }
            }.awaitAll()
        }
        return found.get()
    }

    private fun ping(host: String, cr: SyncCrypto): Boolean = try {
        http.newCall(Request.Builder().url("http://$host/my/ping").get().build()).execute().use { r ->
            r.isSuccessful && (r.body?.string() ?: "").split(" ").getOrNull(1) == cr.lanId
        }
    } catch (_: Exception) {
        false
    }

    private suspend fun relayFetch(relay: Relay, cr: SyncCrypto): Boolean {
        val msgs = relay.poll(cr.topic + "s", relayStateSince.ifEmpty { "3h" })
        var ok = false
        for (m in msgs.sortedByDescending { it.time }) {
            val url = m.attachmentUrl ?: continue
            val plain = try { cr.open(relay.download(url)) } catch (_: Exception) { null } ?: continue
            val snap = try { gson.fromJson(String(plain, Charsets.UTF_8), SyncSnapshot::class.java) } catch (_: Exception) { null } ?: continue
            applySnapshot(snap)
            ok = true
            break
        }
        msgs.maxByOrNull { it.time }?.let { relayStateSince = it.id }
        return ok
    }

    private fun sendRelay(relay: Relay, cr: SyncCrypto, cmd: SyncCommand) {
        val b64 = Base64.getEncoder().encodeToString(cr.seal(gson.toJson(cmd).toByteArray()))
        relay.publishText(cr.topic + "c", b64)
    }

    /** اعمال وضعیت دستگاه اصلی روی این دستگاه (تنظیمات صدا/لرزش همین دستگاه حفظ می‌شود). */
    private suspend fun applySnapshot(snap: SyncSnapshot) {
        val s = snap.settings ?: return
        val acc = snap.account ?: return
        lastSyncAt = System.currentTimeMillis()
        // نتیجه فرمان‌های فرستاده‌شده
        for (r in snap.results.orEmpty()) {
            if (pending.remove(r.id) != null) _events.tryEmit("msg:" + r.message)
        }
        if (snap.hash == appliedHash || snap.createdAt < appliedAt) return
        val local = store.loadSettings()
        val merged = s.copy(
            soundAlerts = local.soundAlerts,
            vibrateAlerts = local.vibrateAlerts,
            loudAlerts = local.loudAlerts,
            lastAppVersion = local.lastAppVersion,
            nobitexToken = local.nobitexToken
        )
        var journal: List<JournalEntry> = snap.journal.orEmpty()
        if (snap.journalTrimmed > 0) {
            val ids = journal.map { it.id }.toHashSet()
            val oldest = journal.minOfOrNull { it.openedAt } ?: Long.MAX_VALUE
            journal = journal + engine.journal.all().filter { it.id !in ids && it.openedAt <= oldest }
        }
        engine.applyMirror(merged, acc, journal, snap.backtest ?: engine.backtest, snap.activity.orEmpty())
        mirror = snap
        appliedHash = snap.hash
        appliedAt = snap.createdAt
        _events.tryEmit("data")
    }

    // ---------------- فرمان‌ها (آینه ← دستگاه اصلی) ----------------

    /** فیلدهای تغییرکرده تنظیمات (بدون فیلدهای مخصوص همین دستگاه)؛ null یعنی تغییری برای ارسال نیست. */
    fun settingsPatch(old: AppSettings, new: AppSettings): String? {
        val a = gson.toJsonTree(old).asJsonObject
        val b = gson.toJsonTree(new).asJsonObject
        val patch = JsonObject()
        for ((k, v) in b.entrySet()) {
            if (k in LOCAL_KEYS) continue
            if (a.get(k) != v) patch.add(k, v)
        }
        return if (patch.size() == 0) null else patch.toString()
    }

    /** ارسال فرمان به دستگاه اصلی؛ خروجی: پیام قابل نمایش. */
    suspend fun send(type: String, args: Map<String, String> = emptyMap(), patch: String? = null): String {
        val cr = crypto ?: return "کد اتصال تنظیم نشده است."
        val c = cfg
        val cmd = SyncCommand(UUID.randomUUID().toString(), System.currentTimeMillis(), type, args, patch, c.deviceName)
        val sealed = cr.seal(gson.toJson(cmd).toByteArray())
        if (c.lanEnabled && c.lastHost.isNotEmpty()) {
            try {
                val req = Request.Builder()
                    .url("http://" + c.lastHost + "/my/cmd")
                    .post(sealed.toRequestBody("application/octet-stream".toMediaType()))
                    .build()
                val res = http.newCall(req).execute().use { r ->
                    if (r.code != 200) null
                    else cr.open(r.body?.bytes() ?: ByteArray(0))?.let { gson.fromJson(String(it, Charsets.UTF_8), SyncResult::class.java) }
                }
                if (res != null) {
                    scope.launch { delay(1500); pollOnce(forceRelay = false) }
                    return res.message
                }
            } catch (_: Exception) {
            }
        }
        if (c.relayEnabled) {
            return try {
                Relay(c.relayUrl).publishText(cr.topic + "c", Base64.getEncoder().encodeToString(sealed))
                pending[cmd.id] = System.currentTimeMillis()
                lastRelayHelloAt = System.currentTimeMillis()
                lastRelayPollAt = System.currentTimeMillis() - RELAY_POLL_MS + 30_000
                publishStatus()
                "فرمان از طریق اینترنت برای دستگاه اصلی فرستاده شد؛ معمولاً ظرف یکی دو دقیقه اجرا و نتیجه اعلام می‌شود."
            } catch (e: Exception) {
                "دستگاه اصلی در دسترس نیست (نه در شبکه محلی، نه از طریق اینترنت)؛ فرمان فرستاده نشد."
            }
        }
        return "دستگاه اصلی در شبکه محلی در دسترس نیست؛ فرمان فرستاده نشد."
    }

    companion object {
        /** تنظیماتی که مخصوص هر دستگاه است و کپی نمی‌شود. */
        val LOCAL_KEYS = setOf("soundAlerts", "vibrateAlerts", "loudAlerts", "lastAppVersion")
        const val RELAY_JOURNAL = 300
        const val RELAY_MAX_BYTES = 1_500_000
        const val RELAY_MIN_GAP_MS = 8 * 60_000L
        const val RELAY_HEARTBEAT_MS = 100 * 60_000L
        const val RELAY_POLL_MS = 60_000L
    }
}
