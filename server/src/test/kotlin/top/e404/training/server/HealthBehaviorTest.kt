package top.e404.training.server

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.e404.training.shared.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HealthBehaviorTest {
    @TempDir lateinit var directory: Path

    private fun step(id: String, count: Long, date: String = "2026-10-27") = HealthRecord(
        sourceId = id,
        localDate = date,
        measuredAt = 1_793_059_200_000L + count,
        payload = buildJsonObject {
            put("steps", count)
            put("distanceMeters", 12.5)
            put("caloriesKcal", 1.25)
        }
    )

    @Test fun `健康自然键重复不推进修订且日期汇总有限范围`() {
        Store(directory, emptyList()).use { store ->
            val source = "cloud:1793059200"
            val first = store.healthImport(HealthImportRequest(HealthDataset.steps, listOf(step(source, 100))))
            assertEquals(1, first.accepted)
            val rev = first.revisionByDate.getValue("2026-10-27")
            val reordered = step(source, 100).copy(payload = buildJsonObject {
                put("caloriesKcal", 1.25); put("distanceMeters", 12.5); put("steps", 100)
            })
            val retry = store.healthImport(HealthImportRequest(HealthDataset.steps, listOf(reordered)))
            assertEquals(0, retry.accepted)
            assertEquals(1, retry.unchanged)
            assertEquals(rev, retry.revisionByDate.getValue("2026-10-27"))
            val days = store.healthDays(LocalDate.parse("2026-10-27"), LocalDate.parse("2026-10-27"))
            assertEquals(100L, days.days.single().steps)
            assertEquals(12.5, days.days.single().distanceMeters)
            assertNull(store.healthDays(LocalDate.parse("2026-10-28"), LocalDate.parse("2026-10-29")).days.firstOrNull())
        }
    }

    @Test fun `健康详情分页固定顺序且新页面包含日期修订`() {
        Store(directory, emptyList()).use { store ->
            val records = (0..500).map { step("minute-$it", it.toLong()) }
            store.healthImport(HealthImportRequest(HealthDataset.steps, records))
            val first = store.healthRecords(LocalDate.parse("2026-10-27"), HealthDataset.steps, null)
            assertEquals(500, first.records.size)
            assertEquals("minute-0", first.records.first().sourceId)
            assertEquals("500", first.nextCursor)
            val second = store.healthRecords(LocalDate.parse("2026-10-27"), HealthDataset.steps, first.nextCursor)
            assertEquals(1, second.records.size)
            assertNull(second.nextCursor)
            assertEquals(first.revision, second.revision)
        }
    }

    @Test fun `采集失败保留最后成功时间并且重复请求合并`() {
        Store(directory, emptyList()).use { store ->
            store.collectionState(HealthCollectionStateRequest("succeeded"))
            val lastSuccess = store.healthStatus().collection.lastSuccessAt
            val request = store.collectionRequest()
            assertTrue(request.requested)
            assertTrue(!store.collectionRequest().requested)
            store.collectionState(HealthCollectionStateRequest("failed", "login", "source unavailable"))
            val state = store.healthStatus().collection
            assertEquals("failed", state.stage)
            assertEquals(lastSuccess, state.lastSuccessAt)
            assertEquals("source unavailable", state.error)
        }
    }
    @Test fun `发布既有资料不推进采集成功时间且可再次请求`() {
        Store(directory, emptyList()).use { store ->
            store.collectionState(HealthCollectionStateRequest("succeeded"))
            val lastSuccess = store.healthStatus().collection.lastSuccessAt
            val published = store.collectionState(HealthCollectionStateRequest("published", "publish-existing"))
            assertEquals("published", published.stage)
            assertTrue(published.submittedAt != null)
            assertNull(published.error)
            assertEquals(lastSuccess, published.lastSuccessAt)
            assertTrue(store.collectionRequest().requested)
        }
    }

    @Test fun `完整健康备份流式往返且无消费者的健康包明确拒绝`() {
        val row = HealthBackupEntry(HealthDataset.steps, step("minute-1", 42))
        val backup = Backup(
            documents = emptyList(), exportedAt = 1L,
            healthManifest = HealthBackupManifest("complete", listOf("2026-10-27"), listOf(HealthDataset.steps), 1)
        )
        val bytes = ByteArrayOutputStream()
        writeBackup(backup, bytes, { null }, sequenceOf(row))
        val seen = mutableListOf<HealthBackupEntry>()
        val restored = readBackup(ByteArrayInputStream(bytes.toByteArray()), directory.resolve("media"), seen::add)
        assertEquals(6, restored.formatVersion)
        assertEquals(backup.healthManifest, restored.healthManifest)
        assertEquals(listOf(row), seen)
        assertFails { readBackup(ByteArrayInputStream(bytes.toByteArray()), directory.resolve("media-no-consumer")) }
    }
    @Test fun `运动摘要导入只保存原始健康记录且不生成或覆盖用户活动`() {
        Store(directory, emptyList()).use { store ->
            val start = 1_793_059_200_000L
            fun healthRecord(duration: Int, distance: Double) = HealthRecord(
                sourceId = "xiaomi_hike_01", localDate = "2026-10-27", measuredAt = start,
                payload = wireJson.encodeToJsonElement(ActivityPayload(
                    sportKey = "outdoor_walking", startAt = start, endAt = start + duration * 1_000L,
                    durationSeconds = duration, distanceMeters = distance
                )).jsonObject
            )
            val auto = ActivityRecord(
                id = "xiaomi-0123456789abcdef0123456789abcdef",
                name = "徒步", localDate = "2026-10-27", sourceId = "misports:activities:xiaomi_hike_01"
            )
            val imported = ActivityRecord(
                id = "activity-hike", name = "已导入徒步", localDate = "2026-10-27",
                durationSeconds = 13_854, distanceMeters = 12_040.0,
                sourceId = "misports:activities:xiaomi_hike_01"
            )
            store.sync(SyncRequest(mutations = listOf(Mutation(document(Kind.activity, auto.id, auto), 0))))
            val first = healthRecord(13_854, 12_040.0)
            val result = store.healthImport(HealthImportRequest(HealthDataset.activity, listOf(first)))
            assertEquals(1, result.accepted)
            assertEquals(listOf(auto), store.list(Kind.activity).map { it.value<ActivityRecord>() })
            assertEquals(auto, store.get(Kind.activity, auto.id)!!.value<ActivityRecord>())
            assertTrue(auto.isXiaomiAutoProjection())
            assertFalse(imported.isXiaomiAutoProjection())

            store.sync(SyncRequest(mutations = listOf(Mutation(document(Kind.activity, imported.id, imported), 0))))
            store.healthImport(HealthImportRequest(HealthDataset.activity, listOf(healthRecord(13_900, 12_500.0))))
            assertEquals(imported, store.get(Kind.activity, imported.id)!!.value<ActivityRecord>())
            val visibleActivities = store.list(Kind.activity).map { it.value<ActivityRecord>() }.filterNot(ActivityRecord::isXiaomiAutoProjection)
            assertEquals(listOf(imported), visibleActivities)

            val raw = store.healthRecords(LocalDate.parse("2026-10-27"), HealthDataset.activity, null)
            assertEquals(1, raw.records.size)
            assertEquals(13_900, raw.records.single().decodePayload<ActivityPayload>().durationSeconds)
            assertEquals("xiaomi_hike_01", store.allHealthRecords().single().record.sourceId)
        }
    }
}
