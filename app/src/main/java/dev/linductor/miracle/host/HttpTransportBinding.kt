package dev.linductor.miracle.host

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * mira IHttpTransport 的 Kotlin 执行端（P3 计划决策 1）。
 *
 * native（loop_runtime 的 KotlinHttpTransport）经 HostBridge 静态门面调用：
 * start（受理）/ cancel（协作取消）；完成经 HostBridge.nativeHttpExchangeComplete
 * 恰好一次回流（done 标志竞争结算，迟到完成由 native 丢弃）。
 *
 * 安全姿态与 mira TransportLimits 默认一致：仅 https、拒绝私有/回环/链路本地
 * 地址、响应字节上限；凭据只在内存（bind 时注入，Authorization 头在此拼装）。
 * 生命周期 owner＝AgentRuntime 会话（结构化作用域，close 时 shutdown）。
 */
class HttpTransportBinding {

    private class Exchange(val id: Long) {
        val done = AtomicBoolean(false)
        @Volatile var job: Job? = null
        @Volatile var connection: HttpURLConnection? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val exchanges = ConcurrentHashMap<Long, Exchange>()

    /** 内存凭据（bind 注入；不落盘不入日志）。 */
    @Volatile
    private var credential: String? = null

    @Volatile
    private var bound = false

    val ready: Boolean get() = bound

    fun bind(apiKey: String) {
        credential = apiKey
        bound = true
    }

    fun shutdown() {
        bound = false
        credential = null
        scope.cancel()
        exchanges.values.forEach { exchange ->
            exchange.connection?.disconnect()
        }
        exchanges.clear()
    }

    /** native 调用：受理交换。0=已受理 1=未绑定 2=拒绝（容量/参数）。 */
    fun start(exchangeId: Long, requestJson: String, body: ByteArray): Int {
        if (!bound) {
            return 1
        }
        if (exchanges.size >= MAX_IN_FLIGHT) {
            return 2
        }
        val request = try {
            parseRequest(requestJson)
        } catch (_: Exception) {
            return 2
        }
        if (request == null || !isEndpointAllowed(request.url)) {
            return 2
        }
        val exchange = Exchange(exchangeId)
        if (exchanges.putIfAbsent(exchangeId, exchange) != null) {
            return 2 // id 冲突（native 侧唯一，防御）
        }
        exchange.job = scope.launch {
            val status = execute(request, body, exchange)
            if (exchange.done.compareAndSet(false, true)) {
                exchanges.remove(exchangeId)
                emitComplete(exchangeId, status)
            }
        }
        return 0
    }

    /** native 调用：协作取消（disconnect；完成以 -1 回流，若尚未完成）。 */
    fun cancel(exchangeId: Long) {
        val exchange = exchanges[exchangeId] ?: return
        exchange.job?.cancel()
        exchange.connection?.disconnect()
        if (exchange.done.compareAndSet(false, true)) {
            exchanges.remove(exchangeId)
            pendingBodies.remove(exchangeId)
            emitComplete(exchangeId, STATUS_CANCELLED)
        }
    }

    private data class ParsedRequest(
        val url: String,
        val method: String,
        val headers: List<Pair<String, String>>,
        val hasAuthorization: Boolean,
        val totalTimeoutMs: Long,
        val maxResponseBytes: Long,
    )

    private fun parseRequest(json: String): ParsedRequest? {
        val root = JSONObject(json)
        val url = root.optString("url")
        val method = root.optString("method", "POST")
        val headers = ArrayList<Pair<String, String>>()
        val array = root.optJSONArray("headers") ?: JSONArray()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("name")
            val value = item.optString("value")
            if (name.isNotBlank()) {
                headers.add(name to value)
            }
        }
        return ParsedRequest(
            url = url,
            method = method,
            headers = headers,
            hasAuthorization = root.optBoolean("has_authorization"),
            totalTimeoutMs = root.optLong("total_timeout_ms", 120_000).coerceIn(1_000, 300_000),
            maxResponseBytes = root.optLong("max_response_bytes", 8L * 1024 * 1024),
        )
    }

    /** SSRF 姿态：仅 https，且拒绝回环/私有/链路本地主机（静态检查；DNS 解析在 IO 内复核）。 */
    private fun isEndpointAllowed(urlText: String): Boolean =
        EndpointPolicyCheck.isAllowed(urlText)

    /** 端点静态策略（纯函数，JVM 单测覆盖；DNS 复核在 execute 内）。 */
    object EndpointPolicyCheck {
        fun isAllowed(urlText: String): Boolean {
        val url = try {
            URL(urlText)
        } catch (_: Exception) {
            return false
        }
        if (url.protocol.lowercase() != "https") {
            return false
        }
        val host = url.host?.lowercase()?.trim() ?: return false
        if (host.isEmpty()) {
            return false
        }
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
            host.endsWith(".internal")
        ) {
            return false
        }
        // IPv6 字面量一律拒绝（保守）；IPv4 私有段精确拒绝。
        if (host.contains(":")) {
            return false
        }
        val parts = host.split(".")
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
            val first = parts[0].toInt()
            val second = parts[1].toInt()
            if (first == 10 || first == 127 || first == 0) {
                return false
            }
            if (first == 172 && second in 16..31) {
                return false
            }
            if (first == 192 && second == 168) {
                return false
            }
            if (first == 169 && second == 254) {
                return false
            }
        }
        return true
        }
    }

    /** 返回值：HTTP 状态码（>=200）或负数错误码。 */
    private suspend fun execute(
        request: ParsedRequest,
        body: ByteArray,
        exchange: Exchange,
    ): Int = withContext(Dispatchers.IO) {
        // DNS 复核（私有/回环/链路本地地址 fail-closed）。
        val host = try {
            URL(request.url).host ?: return@withContext STATUS_CONFIG
        } catch (_: Exception) {
            return@withContext STATUS_CONFIG
        }
        try {
            if (InetAddress.getAllByName(host).any { address ->
                    address.isSiteLocalAddress || address.isLoopbackAddress ||
                        address.isLinkLocalAddress || address.isAnyLocalAddress ||
                        address.isMulticastAddress
                }
            ) {
                return@withContext STATUS_CONFIG
            }
        } catch (_: Exception) {
            return@withContext STATUS_NETWORK
        }
        var connection: HttpURLConnection? = null
        try {
            val opened = URL(request.url).openConnection() as HttpURLConnection
            connection = opened
            exchange.connection = opened
            opened.requestMethod = request.method
            opened.connectTimeout = minOf(10_000, request.totalTimeoutMs).toInt()
            opened.readTimeout = request.totalTimeoutMs.toInt()
            opened.instanceFollowRedirects = false // 重定向不跟随（端点固定）
            opened.doOutput = body.isNotEmpty() || request.method == "POST"
            for ((name, value) in request.headers) {
                opened.setRequestProperty(name, value)
            }
            if (request.hasAuthorization) {
                val key = credential
                if (key.isNullOrEmpty()) {
                    return@withContext STATUS_CONFIG
                }
                opened.setRequestProperty("Authorization", "Bearer $key")
            }
            if (body.isNotEmpty()) {
                opened.setFixedLengthStreamingMode(body.size)
                opened.outputStream.use { stream -> stream.write(body) }
            }
            val status = opened.responseCode
            if (status in 200..299) {
                val bytes = readBounded(opened.inputStream, request.maxResponseBytes)
                emitBody(exchange.id, status, opened.headerFields, bytes)
            } else {
                // 非 2xx：错误体读小量诊断（不进入结果，仅日志摘要计数）
                readBounded(opened.errorStream ?: opened.inputStream, 4 * 1024)
                emitBody(exchange.id, status, opened.headerFields, ByteArray(0))
            }
            status
        } catch (error: Exception) {
            when (error) {
                is java.net.SocketTimeoutException -> STATUS_TIMEOUT
                is kotlinx.coroutines.CancellationException -> STATUS_CANCELLED
                else -> STATUS_NETWORK
            }
        } finally {
            connection?.disconnect()
            exchange.connection = null
        }
    }

    private fun readBounded(stream: java.io.InputStream?, maxBytes: Long): ByteArray {
        if (stream == null) {
            return ByteArray(0)
        }
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                if (output.size() + read > maxBytes) {
                    throw java.io.IOException("response exceeds size limit")
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    /** 成功交换：暂存正文，complete 时恰好一次回流（取消竞态下丢弃孤儿写入）。 */
    private fun emitBody(
        exchangeId: Long,
        status: Int,
        headerFields: Map<String, List<String>>,
        body: ByteArray,
    ) {
        if (!exchanges.containsKey(exchangeId)) {
            return // 已取消/结算：迟到正文丢弃
        }
        val headers = JSONArray()
        for ((rawName, values) in headerFields) {
            // HttpURLConnection 用 null 键承载状态行；显式可空化再过滤。
            val name: String? = rawName
            if (name.isNullOrEmpty() || values.isEmpty()) {
                continue
            }
            headers.put(JSONObject().put("name", name).put("value", values.joinToString(", ")))
        }
        pendingBodies[exchangeId] = PendingBody(status, headers.toString(), body)
    }

    private class PendingBody(val status: Int, val headersJson: String, val body: ByteArray)

    private val pendingBodies = ConcurrentHashMap<Long, PendingBody>()

    private fun emitComplete(exchangeId: Long, fallbackStatus: Int) {
        val pending = pendingBodies.remove(exchangeId)
        if (pending != null) {
            HostBridge.nativeHttpExchangeComplete(
                exchangeId, pending.status, pending.headersJson, pending.body,
            )
        } else {
            HostBridge.nativeHttpExchangeComplete(
                exchangeId, fallbackStatus, "[]", ByteArray(0),
            )
        }
    }

    companion object {
        const val STATUS_CANCELLED = -1
        const val STATUS_TIMEOUT = -2
        const val STATUS_NETWORK = -3
        const val STATUS_CONFIG = -4
        const val MAX_IN_FLIGHT = 8
    }
}
