package top.e404.training.server

import top.e404.training.shared.*
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

class Archives(private val directory: Path, private val store: Store) {
    private val media = directory.resolve("media").also { Files.createDirectories(it) }
    fun openMedia(name: String): InputStream? {
        require(validId(name)) { "素材文件名不正确" }
        val file = media.resolve(name)
        return if (Files.exists(file)) Files.newInputStream(file) else javaClass.classLoader.getResourceAsStream("media/$name")
    }

    @Synchronized fun saveMedia(name: String, source: InputStream) {
        require(validId(name)) { "素材文件名不正确" }
        val temporary = Files.createTempFile(media, ".upload-", ".part")
        try {
            Files.newOutputStream(temporary).use { source.copyTo(it) }
            val existing = openMedia(name)
            if (existing != null) {
                val same = existing.use { old -> Files.newInputStream(temporary).use { incoming -> mediaDigest(old).contentEquals(mediaDigest(incoming)) } }
                if (!same) throw ImportConflict("素材文件名已使用，请为新图片使用新的文件名")
            } else Files.move(temporary, media.resolve(name), StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temporary) }
    }

    fun export(output: OutputStream) = writeBackup(store.backup(), output, ::openMedia)

    fun import(input: InputStream, replace: Boolean): List<Document> {
        val temporary = Files.createTempDirectory(directory, ".import-")
        val created = mutableListOf<Path>()
        var committed = false
        try {
            val backup = readBackup(input, temporary)
            val prepared = backup.documents.map { doc ->
                if (doc.kind != Kind.media || doc.deleted) doc else {
                    val item = doc.value<Media>()
                    val staged = temporary.resolve(item.fileName)
                    require(Files.exists(staged)) { "数据包缺少素材：${item.name}" }
                    val old = openMedia(item.fileName)
                    val same = old?.use { existing -> Files.newInputStream(staged).use { incoming -> mediaDigest(existing).contentEquals(mediaDigest(incoming)) } } ?: false
                    if (same) doc else {
                        if (old != null && !replace) throw ImportConflict("已有不同素材：${item.name}；需明确选择替换")
                        // 不覆盖旧文件：数据库提交失败时，原有可见素材仍完整。
                        val name = "import-${UUID.randomUUID()}.${item.fileName.substringAfterLast('.')}"
                        val target = media.resolve(name)
                        Files.copy(staged, target); created.add(target)
                        document(Kind.media, item.id, item.copy(fileName = name)).copy(revision = doc.revision)
                    }
                }
            }
            val result = store.import(backup.copy(documents = prepared), replace)
            committed = true
            return result
        } finally {
            if (!committed) created.forEach { Files.deleteIfExists(it) }
            Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }

}
