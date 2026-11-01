package top.e404.training.collector

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class CredentialError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object Credentials {
    fun load(root: Path): Pair<String, String> {
        val candidates = listOf(root.resolve("credentials.json"), root.resolve("cookie.txt"))
            .filter { Files.exists(it) }
        if (candidates.isEmpty()) {
            throw CredentialError("没有找到凭据。请把浏览器 cookie 保存为 cookie.txt，格式是 userId=...; passToken=...")
        }
        val path = candidates.maxWithOrNull(compareBy<Path> { Files.getLastModifiedTime(it) }.thenByDescending { candidates.indexOf(it) })!!
        if (path.fileName.toString().endsWith(".json")) {
            try {
                val value = Json.parseToJsonElement(Files.readString(path, StandardCharsets.UTF_8)).jsonObject
                val userId = pythonString(value.getValue("user_id"))
                val passToken = pythonString(value.getValue("pass_token"))
                return userId to passToken
            } catch (e: Exception) {
                throw CredentialError("读取 ${path.fileName} 失败: ${e.message}", e)
            }
        }
        val cookies = parseCookieText(Files.readString(path, StandardCharsets.UTF_8))
        val userId = cookies["userId"].orEmpty()
        val passToken = cookies["passToken"].orEmpty()
        if (userId.isEmpty() || passToken.isEmpty()) {
            throw CredentialError("${path.fileName} 缺少 userId 或 passToken。请从浏览器 cookie 中复制完整内容。")
        }
        return userId to passToken
    }

    fun save(root: Path, userId: String, passToken: String): Path {
        val path = root.resolve("credentials.json")
        val json = buildJsonObject {
            put("user_id", userId)
            put("pass_token", passToken)
        }
        val text = Json { prettyPrint = true; prettyPrintIndent = "  " }.encodeToString(JsonObject.serializer(), json)
        Files.writeString(path, text, StandardCharsets.UTF_8)
        return path
    }

    private fun parseCookieText(text: String): Map<String, String> = buildMap {
        text.split(';').forEach { part ->
            val equals = part.indexOf('=')
            if (equals >= 0) put(part.substring(0, equals).trim(), part.substring(equals + 1).trim())
        }
    }

    private fun pythonString(value: kotlinx.serialization.json.JsonElement): String = when (value) {
        kotlinx.serialization.json.JsonNull -> "None"
        is JsonPrimitive -> if (value.isString) value.content else when (value.content) {
            "true" -> "True"
            "false" -> "False"
            else -> value.content
        }
        else -> value.toString()
    }
}
