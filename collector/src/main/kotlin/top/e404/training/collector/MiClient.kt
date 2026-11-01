package top.e404.training.collector

import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64

class AuthError(message: String) : RuntimeException(message)

class MiClient(
    userId: String,
    passToken: String,
    region: String = "cn",
) : AutoCloseable {
    var userId: String = userId
        private set
    var passToken: String = passToken
        private set
    private val baseUrl = if (region.isEmpty() || region == "cn") "https://hlth.io.mi.com" else "https://$region.hlth.io.mi.com"
    private var ssecurity = ByteArray(0)
    private var cookies = ""
    private var lastRequestAt = 0L
    private var closed = false

    override fun close() {
        closed = true
    }

    fun login(): Pair<String, String> {
        val response = http("GET", "https://account.xiaomi.com/pass/serviceLogin?_json=true&sid=miothealth", mapOf("Cookie" to "userId=$userId; passToken=$passToken"))
        requireSuccess(response)
        val text = response.body.toString(StandardCharsets.UTF_8)
        if (!text.startsWith(LOGIN_PREFIX)) throw AuthError("小米登录响应格式不符合预期，passToken 可能已经失效")
        val payload = try { JSON.parseToJsonElement(text.substring(LOGIN_PREFIX.length)).jsonObject } catch (e: Exception) {
            throw AuthError("无法解析小米登录响应")
        }
        val required = listOf("passToken", "userId", "ssecurity", "location")
        val missing = required.filter { payload[it]?.jsonPrimitive?.contentOrNull.isNullOrEmpty() }
        if (missing.isNotEmpty()) throw AuthError("小米登录响应缺少字段: ${missing.joinToString(", ")}")
        passToken = payload.getValue("passToken").jsonPrimitive.content
        userId = payload.getValue("userId").jsonPrimitive.content
        ssecurity = try { Base64.getDecoder().decode(payload.getValue("ssecurity").jsonPrimitive.content) } catch (e: IllegalArgumentException) {
            throw AuthError("小米登录响应 ssecurity 不是有效 Base64")
        }
        val redirect = http("GET", payload.getValue("location").jsonPrimitive.content)
        requireSuccess(redirect)
        val cookieParts = redirect.headers.filterKeys { it.equals("Set-Cookie", true) }.values.flatten().map { it.substringBefore(';') }
        if (cookieParts.none { it.startsWith("serviceToken=") && it != "serviceToken=" }) throw AuthError("登录重定向没有返回 serviceToken")
        cookies = cookieParts.joinToString("; ")
        return userId to passToken
    }

    fun request(path: String, payload: JsonObject, signPath: String? = null): Pair<JsonObject, JsonObject> {
        val signaturePath = signPath ?: path
        var lastError: Exception? = null
        for (attempt in 0..2) {
            try {
                throttle()
                val form = linkedMapOf("data" to JSON.encodeToString(JsonObject.serializer(), payload))
                val nonce = ByteArray(8).also { java.security.SecureRandom().nextBytes(it) } +
                    java.nio.ByteBuffer.allocate(4).putInt((System.currentTimeMillis() / 60_000L).toInt()).array()
                val signedNonce = MessageDigest.getInstance("SHA-256").digest(ssecurity + nonce)
                form["rc4_hash__"] = signature("POST", signaturePath, form, signedNonce)
                val encrypted = linkedMapOf<String, String>()
                form.forEach { (key, value) -> encrypted[key] = Base64.getEncoder().encodeToString(rc4(signedNonce, value.toByteArray(StandardCharsets.UTF_8))) }
                encrypted["signature"] = signature("POST", signaturePath, encrypted, signedNonce)
                encrypted["_nonce"] = Base64.getEncoder().encodeToString(nonce)
                val encoded = encrypted.entries.joinToString("&") { (key, value) -> urlEncode(key) + "=" + urlEncode(value) }
                val response = http("POST", baseUrl + path, mapOf("Cookie" to cookies, "Content-Type" to "application/x-www-form-urlencoded"), encoded.toByteArray(StandardCharsets.UTF_8))
                requireSuccess(response)
                val plaintext = rc4(signedNonce, Base64.getDecoder().decode(response.body.toString(StandardCharsets.UTF_8).trim()))
                val body = JSON.parseToJsonElement(plaintext.toString(StandardCharsets.UTF_8)).jsonObject
                val code = body["code"]?.jsonPrimitive
                if (code?.content != "0") {
                    val message = body["message"]?.jsonPrimitive?.contentOrNull ?: "unknown error"
                    if (isAuthError(code, message)) throw AuthError(message)
                    throw RuntimeException("云端返回错误 code=${code?.content ?: "null"}: $message")
                }
                val result = body["result"]?.let { if (it is JsonNull) null else it.jsonObject } ?: JsonObject(emptyMap())
                return body to result
            } catch (e: AuthError) {
                throw e
            } catch (e: Exception) {
                lastError = e
                val retryable = e is IOException || e is java.net.SocketTimeoutException || e is HttpFailure && (e.status == 429 || e.status >= 500)
                if (attempt == 2 || !retryable) break
                Thread.sleep(minOf(4000L, 500L * (1L shl attempt)))
            }
        }
        throw RuntimeException("请求失败: ${lastError?.message}", lastError)
    }

    fun fetchKey(key: String, start: String, end: String, onPage: (Int, JsonObject) -> Unit): List<JsonObject> {
        val (startTs, endTs) = dateRangeTimestamps(start, end)
        var nextKey: String? = null
        val seen = mutableSetOf<String>()
        val items = mutableListOf<JsonObject>()
        var page = 0
        while (true) {
            if (++page > MAX_PAGES) throw RuntimeException("分页超过安全上限")
            val payload = buildJsonObject {
                put("start_time", startTs); put("end_time", endTs); put("key", key)
                nextKey?.let { put("next_key", it) }
            }
            val (body, result) = request("/app/v1/data/get_fitness_data_by_time", payload)
            onPage(page, body)
            result["data_list"]?.let { if (it !is JsonNull) items.addAll(it.jsonArray.map { element -> element.jsonObject }) }
            if (result["has_more"]?.jsonPrimitive?.booleanOrNull != true) break
            val candidate = result["next_key"]?.jsonPrimitive?.contentOrNull ?: break
            if (candidate in seen) throw RuntimeException("分页游标出现循环")
            seen.add(candidate); nextKey = candidate
        }
        return items
    }

    fun fetchSportRecords(start: String, end: String, onPage: (Int, JsonObject) -> Unit): List<JsonObject> {
        val (startTs, endTs) = dateRangeTimestamps(start, end)
        var nextKey: String? = null
        val seen = mutableSetOf<String>()
        val items = mutableListOf<JsonObject>()
        var page = 0
        while (true) {
            if (++page > MAX_PAGES) throw RuntimeException("运动记录分页超过安全上限")
            val payload = buildJsonObject {
                put("start_time", startTs); put("end_time", endTs); put("limit", 50)
                nextKey?.let { put("next_key", it) }
            }
            val (body, result) = request("/app/v1/data/get_sport_records_by_time", payload)
            onPage(page, body)
            result["sport_records"]?.let { if (it !is JsonNull) items.addAll(it.jsonArray.map { element -> element.jsonObject }) }
            if (result["has_more"]?.jsonPrimitive?.booleanOrNull != true) break
            val candidate = result["next_key"]?.jsonPrimitive?.contentOrNull ?: break
            if (candidate in seen) throw RuntimeException("运动记录分页游标出现循环")
            seen.add(candidate); nextKey = candidate
        }
        return items
    }

    fun downloadFds(url: String, objKey: String): ByteArray {
        val response = http("GET", url)
        requireSuccess(response)
        var raw = response.body.toString(StandardCharsets.UTF_8).trim()
        if (raw.startsWith('"') && raw.endsWith('"')) raw = raw.substring(1, raw.length - 1)
        return Fds.decrypt(raw, objKey)
    }

    private fun throttle() {
        val elapsed = System.nanoTime() - lastRequestAt
        val interval = 300_000_000L
        if (lastRequestAt != 0L && elapsed < interval) Thread.sleep((interval - elapsed) / 1_000_000L, ((interval - elapsed) % 1_000_000L).toInt())
        lastRequestAt = System.nanoTime()
    }

    private fun http(method: String, url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): HttpResult {
        check(!closed) { "MiClient 已关闭" }
        val connection = URL(url).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.requestMethod = method
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body) }
        }
        return try {
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            val responseHeaders = connection.headerFields.filterKeys { it != null }.mapKeys { it.key!! }
            HttpResult(status, bytes, responseHeaders)
        } finally { connection.disconnect() }
    }

    private fun requireSuccess(response: HttpResult) {
        if (response.status !in 200..299) throw HttpFailure(response.status, "HTTP ${response.status}")
    }

    private fun signature(method: String, path: String, values: Map<String, String>, signedNonce: ByteArray): String {
        val base = method + "&" + path + "&data=" + values.getValue("data") +
            (values["rc4_hash__"]?.let { "&rc4_hash__=$it" } ?: "") + "&" + Base64.getEncoder().encodeToString(signedNonce)
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(base.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun rc4(key: ByteArray, payload: ByteArray): ByteArray {
        val state = IntArray(256) { it }; var j = 0
        for (i in 0..255) { j = (j + state[i] + (key[i % key.size].toInt() and 255)) and 255; val t = state[i]; state[i] = state[j]; state[j] = t }
        var i = 0; j = 0
        fun nextByte(): Int { i = (i + 1) and 255; j = (j + state[i]) and 255; val t = state[i]; state[i] = state[j]; state[j] = t; return state[(state[i] + state[j]) and 255] }
        repeat(1024) { nextByte() }
        return ByteArray(payload.size) { index -> (payload[index].toInt() xor nextByte()).toByte() }
    }

    private fun isAuthError(code: JsonPrimitive?, message: String): Boolean {
        if (code?.content in setOf("401", "403", "-6", "-10001")) return true
        return listOf("authentication failed", "invalid credential", "invalid pass token", "invalid passtoken", "login required", "not logged in", "session expired", "unauthorized").any { message.contains(it, ignoreCase = true) }
    }

    private fun dateRangeTimestamps(start: String, end: String): Pair<Long, Long> {
        val startDate = LocalDate.parse(start)
        val endDate = LocalDate.parse(end)
        return startDate.atStartOfDay().toEpochSecond(ZoneOffset.ofHours(8)) to endDate.plusDays(1).atStartOfDay().minusSeconds(1).toEpochSecond(ZoneOffset.ofHours(8))
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private data class HttpResult(val status: Int, val body: ByteArray, val headers: Map<String, List<String>>)
    private class HttpFailure(val status: Int, message: String) : IOException(message)

    companion object {
        private const val LOGIN_PREFIX = "&&&START&&&"
        private const val MAX_PAGES = 200
        private const val TIMEOUT_MS = 30_000
        private val JSON = Json
    }
}
