package top.e404.training.shared

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 数据包只包含业务资料和图像，不包含服务器 token、设备授权或连接配置。 */
fun writeBackup(backup: Backup, output: OutputStream, openMedia: (String) -> InputStream?) =
    writeBackup(backup, output, openMedia, emptySequence())

@OptIn(ExperimentalSerializationApi::class)
fun writeBackup(
    backup: Backup, output: OutputStream, openMedia: (String) -> InputStream?,
    healthRecords: Sequence<HealthBackupEntry>
) {
    val cleaned = backup.documents.map(::withoutDeprecatedHistoricalText)
    val documents = normalizeDailySessionDocuments(cleaned)
    val manifest = backup.healthManifest ?: HealthBackupManifest("cache")
    require(manifest.scope in setOf("complete", "cache")) { "健康备份范围不正确" }
    val export = backup.copy(formatVersion = 6, documents = documents, healthManifest = manifest)
    ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry("data.json"))
        wireJson.encodeToStream(export, zip)
        zip.closeEntry()
        export.documents.filter { it.kind == Kind.media && !it.deleted }.map { it.value<Media>().fileName }.distinct().forEach { name ->
            require(validId(name)) { "素材文件名不正确" }
            val source = requireNotNull(openMedia(name)) { "导出所需素材不存在：$name" }
            zip.putNextEntry(ZipEntry("media/$name"))
            source.use { it.copyTo(zip) }
            zip.closeEntry()
        }
        if (manifest.recordCount > 0) {
            zip.putNextEntry(ZipEntry("health.jsonl"))
            var count = 0L
            healthRecords.forEach { entry ->
                zip.write(wireJson.encodeToString(entry).toByteArray(Charsets.UTF_8))
                zip.write('\n'.code)
                count++
            }
            require(count == manifest.recordCount) { "健康清单记录数与数据流不一致" }
            zip.closeEntry()
        } else require(healthRecords.none()) { "健康记录必须具有健康清单" }
    }
}

@OptIn(ExperimentalSerializationApi::class)
fun readBackup(
    input: InputStream, mediaDirectory: Path,
    onHealthEntry: ((HealthBackupEntry) -> Unit)? = null
): Backup {
    Files.createDirectories(mediaDirectory)
    var backup: Backup? = null
    var healthCount = 0L
    var hadHealthStream = false
    val entries = mutableSetOf<String>()
    ZipInputStream(input).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            require(entries.add(entry.name)) { "数据包包含重复文件" }
            when {
                entry.isDirectory && entry.name == "media/" -> Unit
                entry.name == "data.json" -> {
                    val decoded = wireJson.decodeFromStream<Backup>(zip)
                    require(decoded.formatVersion in 1..6) { "不支持此数据包版本" }
                    if (decoded.formatVersion >= 5) {
                        require(decoded.documents.filter { it.kind == Kind.session }.all {
                            it.deleted || it.isCanonicalSessionDocument()
                        }) { "版本${decoded.formatVersion}数据包必须使用canonical每日训练格式" }
                    }
                    if (decoded.formatVersion == 6) requireNotNull(decoded.healthManifest) { "版本6数据包缺少健康清单" }
                    val compatible = if (decoded.formatVersion < 3) decoded.copy(
                        documents = decoded.documents.map(::withoutDeprecatedHistoricalText)
                    ) else {
                        require(decoded.documents.none(::containsDeprecatedHistoricalText)) {
                            "版本${decoded.formatVersion}数据包不能包含已废弃的 historicalSource.originalText"
                        }
                        decoded
                    }
                    backup = compatible
                }
                entry.name == "health.jsonl" -> {
                    hadHealthStream = true
                    val consumer = requireNotNull(onHealthEntry) { "数据包包含健康资料；请使用支持健康数据的导入流程" }
                    val reader = zip.bufferedReader(Charsets.UTF_8)
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val row = wireJson.decodeFromString<HealthBackupEntry>(line)
                        decodeHealthPayload(row.dataset, row.record)
                        consumer(row)
                        healthCount++
                    }
                }
                entry.name.startsWith("media/") -> {
                    val name = entry.name.removePrefix("media/")
                    require(validId(name)) { "素材路径不正确" }
                    Files.copy(zip, mediaDirectory.resolve(name))
                }
                else -> throw IllegalArgumentException("数据包包含未知文件：${entry.name}")
            }
            zip.closeEntry()
        }
    }
    val result = requireNotNull(backup) { "数据包缺少 data.json" }
    if (result.formatVersion == 6) {
        val manifest = requireNotNull(result.healthManifest)
        require(manifest.scope in setOf("complete", "cache")) { "健康备份范围不正确" }
        require(healthCount == manifest.recordCount) { "健康清单记录数与数据流不一致" }
        require(hadHealthStream || manifest.recordCount == 0L) { "健康清单声明有记录但缺少health.jsonl" }
    } else require(!hadHealthStream) { "旧版本数据包不能包含健康记录" }
    return result
}
/** 0.0.3 库存及手机缓存/同步文档在读取时兼容；只投影废弃来源字段，不改写库存或用户备注。 */
@PublishedApi
internal fun withoutDeprecatedHistoricalText(document: Document): Document {
    if (document.kind != Kind.session) return document
    val data = document.data
    val legacy = data["historicalSource"]?.takeUnless { it is JsonNull }?.jsonObject
    val cleanedLegacy = legacy?.let { if (it.containsKey("originalText")) JsonObject(it - "originalText") else it }
    val sources = data["sourceRecords"]?.takeUnless { it is JsonNull }?.jsonArray
    val cleanedSources = sources?.let { records ->
        JsonArray(records.map { source ->
            val value = source.jsonObject
            val historical = value["historicalSource"]?.takeUnless { it is JsonNull }?.jsonObject
            if (historical?.containsKey("originalText") == true) JsonObject(value + ("historicalSource" to JsonObject(historical - "originalText")))
            else source
        })
    }
    val updated = data.toMutableMap()
    if (cleanedLegacy != null) updated["historicalSource"] = cleanedLegacy
    if (cleanedSources != null) updated["sourceRecords"] = cleanedSources
    return if (updated == data) document else document.copy(data = JsonObject(updated))
}

private fun containsDeprecatedHistoricalText(document: Document): Boolean {
    if (document.kind != Kind.session) return false
    val legacy = document.data["historicalSource"]?.takeUnless { it is JsonNull }?.jsonObject
    val sources = document.data["sourceRecords"]?.takeUnless { it is JsonNull }?.jsonArray.orEmpty()
    return legacy?.containsKey("originalText") == true ||
        sources.any { it.jsonObject["historicalSource"]?.takeUnless { value -> value is JsonNull }?.jsonObject?.containsKey("originalText") == true }
}

/** 导入时比较实际文件，识别同名不同图；不能仅凭路径判断两份素材相同。 */
fun mediaDigest(input: InputStream): ByteArray {
    val hash = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
    return hash.digest()
}
