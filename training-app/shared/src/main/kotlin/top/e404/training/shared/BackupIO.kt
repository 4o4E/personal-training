package top.e404.training.shared

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 数据包只包含业务资料和图像，不包含服务器 token、设备授权或连接配置。 */
@OptIn(ExperimentalSerializationApi::class)
fun writeBackup(backup: Backup, output: OutputStream, openMedia: (String) -> InputStream?) {
    ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry("data.json"))
        wireJson.encodeToStream(backup, zip)
        zip.closeEntry()
        backup.documents.filter { it.kind == Kind.media && !it.deleted }.map { it.value<Media>().fileName }.distinct().forEach { name ->
            require(validId(name)) { "素材文件名不正确" }
            val source = requireNotNull(openMedia(name)) { "导出所需素材不存在：$name" }
            zip.putNextEntry(ZipEntry("media/$name"))
            source.use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
fun readBackup(input: InputStream, mediaDirectory: Path): Backup {
    Files.createDirectories(mediaDirectory)
    var backup: Backup? = null
    val entries = mutableSetOf<String>()
    ZipInputStream(input).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            require(entries.add(entry.name)) { "数据包包含重复文件" }
            when {
                entry.isDirectory && entry.name == "media/" -> Unit
                entry.name == "data.json" -> backup = wireJson.decodeFromStream<Backup>(zip)
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
    return requireNotNull(backup) { "数据包缺少 data.json" }.also { require(it.formatVersion in 1..2) { "不支持此数据包版本" } }
}

/** 导入时比较实际文件，识别同名不同图；不能仅凭路径判断两份素材相同。 */
fun mediaDigest(input: InputStream): ByteArray {
    val hash = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
    return hash.digest()
}
