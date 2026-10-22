package top.e404.training

import androidx.room.*
import top.e404.training.shared.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject

@Entity(tableName = "documents", primaryKeys = ["kind", "id"])
data class LocalDocument(
    val kind: String, val id: String, val payload: String, val revision: Long,
    val deleted: Boolean, val dirty: Boolean, val conflict: String? = null
) {
    fun document() = Document(Kind.valueOf(kind), id, wireJson.parseToJsonElement(payload).jsonObject, revision, deleted)
}
@Entity(tableName = "local_state") data class LocalValue(@PrimaryKey val key: String, val value: String)

@Dao interface TrainingDao {
    @Query("SELECT * FROM documents") suspend fun all(): List<LocalDocument>
    @Upsert suspend fun put(document: LocalDocument)
    @Upsert suspend fun putAll(documents: List<LocalDocument>)
    @Query("SELECT value FROM local_state WHERE `key`=:key") suspend fun value(key: String): String?
    @Upsert suspend fun putValue(value: LocalValue)
    @Query("DELETE FROM local_state WHERE `key`=:key") suspend fun removeValue(key: String)
}
@Database(entities = [LocalDocument::class, LocalValue::class], version = 1, exportSchema = true)
abstract class TrainingDatabase : RoomDatabase() { abstract fun records(): TrainingDao }

fun Document.local(dirty: Boolean, conflict: String? = null) = LocalDocument(kind.name, id, wireJson.encodeToString(data), revision, deleted, dirty, conflict)
