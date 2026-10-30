package top.e404.training

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import top.e404.training.shared.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject

val HEALTH_CACHE_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS health_days (localDate TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL, revision INTEGER NOT NULL, cachedAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS health_records (localDate TEXT NOT NULL, dataset TEXT NOT NULL, sourceId TEXT NOT NULL, measuredAt INTEGER NOT NULL, payload TEXT NOT NULL, revision INTEGER NOT NULL, cachedAt INTEGER NOT NULL, PRIMARY KEY(localDate, dataset, revision, sourceId))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_health_records_localDate_dataset_revision_measuredAt ON health_records (localDate, dataset, revision, measuredAt)")
        db.execSQL("CREATE TABLE IF NOT EXISTS health_detail_state (localDate TEXT NOT NULL, dataset TEXT NOT NULL, revision INTEGER NOT NULL, complete INTEGER NOT NULL, cachedAt INTEGER NOT NULL, error TEXT, PRIMARY KEY(localDate, dataset))")
    }
}


@Entity(tableName = "documents", primaryKeys = ["kind", "id"])
data class LocalDocument(
    val kind: String, val id: String, val payload: String, val revision: Long,
    val deleted: Boolean, val dirty: Boolean, val conflict: String? = null
) {
    fun document() = Document(Kind.valueOf(kind), id, wireJson.parseToJsonElement(payload).jsonObject, revision, deleted)
}
@Entity(tableName = "local_state") data class LocalValue(@PrimaryKey val key: String, val value: String)

@Entity(tableName = "health_days")
data class LocalHealthDay(
    @PrimaryKey val localDate: String,
    val payload: String,
    val revision: Long,
    val cachedAt: Long
)

@Entity(
    tableName = "health_records",
    primaryKeys = ["localDate", "dataset", "revision", "sourceId"],
    indices = [Index(value = ["localDate", "dataset", "revision", "measuredAt"], name = "index_health_records_localDate_dataset_revision_measuredAt")]
)
data class LocalHealthRecord(
    val localDate: String,
    val dataset: String,
    val sourceId: String,
    val measuredAt: Long,
    val payload: String,
    val revision: Long,
    val cachedAt: Long
)

@Entity(tableName = "health_detail_state", primaryKeys = ["localDate", "dataset"])
data class LocalHealthDetailState(
    val localDate: String,
    val dataset: String,
    val revision: Long,
    val complete: Boolean,
    val cachedAt: Long,
    val error: String? = null
)

@Dao interface TrainingDao {
    @Query("SELECT * FROM documents") suspend fun all(): List<LocalDocument>
    @Upsert suspend fun put(document: LocalDocument)
    @Upsert suspend fun putAll(documents: List<LocalDocument>)
    @Query("SELECT value FROM local_state WHERE `key`=:key") suspend fun value(key: String): String?
    @Upsert suspend fun putValue(value: LocalValue)
    @Query("DELETE FROM local_state WHERE `key`=:key") suspend fun removeValue(key: String)
    @Query("SELECT * FROM health_days WHERE localDate BETWEEN :from AND :to ORDER BY localDate DESC")
    suspend fun healthDays(from: String, to: String): List<LocalHealthDay>
    @Query("SELECT * FROM health_days WHERE localDate=:date")
    suspend fun healthDay(date: String): LocalHealthDay?
    @Upsert suspend fun putHealthDays(days: List<LocalHealthDay>)
    @Query("SELECT * FROM health_records WHERE localDate=:date AND dataset=:dataset AND revision=:revision ORDER BY measuredAt, sourceId")
    suspend fun healthRecords(date: String, dataset: String, revision: Long): List<LocalHealthRecord>
    @Upsert suspend fun putHealthRecords(records: List<LocalHealthRecord>)
    @Query("DELETE FROM health_records WHERE localDate=:date AND dataset=:dataset")
    suspend fun clearHealthRecords(date: String, dataset: String)
    @Query("SELECT * FROM health_detail_state WHERE localDate=:date")
    suspend fun healthDetailStates(date: String): List<LocalHealthDetailState>
    @Query("SELECT * FROM health_detail_state WHERE localDate=:date AND dataset=:dataset")
    suspend fun healthDetailState(date: String, dataset: String): LocalHealthDetailState?
    @Upsert suspend fun putHealthDetailState(state: LocalHealthDetailState)
    @Query("SELECT * FROM health_days ORDER BY localDate")
    suspend fun allHealthDays(): List<LocalHealthDay>
    @Query("SELECT * FROM health_records ORDER BY localDate, dataset, measuredAt, sourceId")
    suspend fun allHealthRecords(): List<LocalHealthRecord>
    @Query("SELECT * FROM health_detail_state ORDER BY localDate, dataset")
    suspend fun allHealthDetailStates(): List<LocalHealthDetailState>
}
@Database(entities = [LocalDocument::class, LocalValue::class, LocalHealthDay::class, LocalHealthRecord::class, LocalHealthDetailState::class], version = 2, exportSchema = true)
abstract class TrainingDatabase : RoomDatabase() { abstract fun records(): TrainingDao }

fun Document.local(dirty: Boolean, conflict: String? = null) = LocalDocument(kind.name, id, wireJson.encodeToString(data), revision, deleted, dirty, conflict)
