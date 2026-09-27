package com.saeidkazemi.trader.sync

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * سرور HTTP بسیار سبک برای شبکه محلی (بدون کتابخانه اضافه؛ روی اندروید و ویندوز یکسان).
 * همه بدنه‌ها رمزنگاری‌شده‌اند؛ خود سرور فقط مسیر را تشخیص می‌دهد.
 */
class LanServer(
    private val handler: (method: String, path: String, query: Map<String, String>, body: ByteArray) -> Response
) {
    data class Response(val code: Int, val body: ByteArray = ByteArray(0), val type: String = "application/octet-stream")

    @Volatile
    var port: Int = 0
        private set

    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "sync-lan").apply { isDaemon = true } }

    fun start(): Boolean {
        if (server != null) return true
        for (p in PORTS) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress("0.0.0.0", p))
                server = s
                port = p
                Thread({ acceptLoop(s) }, "sync-lan-accept").apply { isDaemon = true }.start()
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) { }
        server = null
        port = 0
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val sock = try { s.accept() } catch (e: Exception) { break }
            pool.execute { serve(sock) }
        }
    }

    private fun serve(sock: Socket) {
        try {
            sock.soTimeout = 15000
            sock.use { so ->
                val input = BufferedInputStream(so.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val target = parts[1]
                var length = 0
                while (true) {
                    val h = readLine(input) ?: break
                    if (h.isEmpty()) break
                    val i = h.indexOf(':')
                    if (i > 0 && h.substring(0, i).trim().equals("Content-Length", true)) {
                        length = h.substring(i + 1).trim().toIntOrNull() ?: 0
                    }
                }
                if (length > MAX_BODY) return
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) break
                    read += n
                }
                val path = target.substringBefore('?')
                val query = target.substringAfter('?', "").split('&').filter { '=' in it }
                    .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                val resp = try { handler(method, path, query, body) } catch (e: Exception) { Response(500) }
                val out = so.getOutputStream()
                val head = "HTTP/1.1 " + resp.code + " " + reason(resp.code) + "\r\n" +
                    "Content-Type: " + resp.type + "\r\n" +
                    "Content-Length: " + resp.body.size + "\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(head.toByteArray(Charsets.US_ASCII))
                out.write(resp.body)
                out.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun readLine(input: InputStream): String? {
        val bos = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bos.size() == 0) null else bos.toString("UTF-8")
            if (b == '\n'.code) break
            if (b != '\r'.code) bos.write(b)
            if (bos.size() > 8192) return null
        }
        return bos.toString("UTF-8")
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 204 -> "No Content"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Error"
    }

    companion object {
        val PORTS = listOf(47631, 47632)
        private const val MAX_BODY = 1_000_000

        /** نشانی‌های IPv4 محلی این دستگاه (وای‌فای/شبکه). */
        fun localAddresses(): List<String> = try {
            val up = NetworkInterface.getNetworkInterfaces().toList()
                .filter { ni -> try { ni.isUp && !ni.isLoopback && !ni.isVirtual } catch (_: Exception) { false } }
            fun ips(list: List<NetworkInterface>) = list
                .flatMap { ni -> ni.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .map { it.hostAddress }
                .distinct()
            // اول کارت‌های شبکه واقعی (وای‌فای/کابل)؛ اگر چیزی نماند، همه
            val real = up.filterNot { ni ->
                val n = (ni.name + " " + (ni.displayName ?: "")).lowercase()
                listOf("vmware", "virtualbox", "vbox", "hyper-v", "docker", "vethernet", "wsl", "tun", "rmnet", "ccmni").any { it in n }
            }
            ips(real).ifEmpty { ips(up) }
        } catch (e: Exception) {
            emptyList()
        }

        /** همه نشانی‌های /24 شبکه‌های محلی (برای جستجوی دستگاه اصلی). */
        fun subnetCandidates(): List<String> {
            val out = ArrayList<String>()
            for (ip in localAddresses().take(3)) {
                val base = ip.substringBeforeLast('.')
                for (i in 1..254) {
                    val cand = "$base.$i"
                    if (cand != ip) out.add(cand)
                }
            }
            return out
        }

        fun isReachable(host: String, port: Int, timeoutMs: Int): Boolean = try {
            Socket().use { s -> s.connect(InetSocketAddress(InetAddress.getByName(host), port), timeoutMs); true }
        } catch (_: Exception) {
            false
        }
    }
}
