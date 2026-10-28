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
    @Test fun `徒步来源保留手工编辑并且删除后重复提交不复活`() {
        Store(directory, emptyList()).use { store ->
            val start = 1_793_059_200_000L
            fun activity(duration: Int, distance: Double, end: Long) = ActivityPayload(
                sportKey = "outdoor_walking", startAt = start, endAt = end,
                durationSeconds = duration, distanceMeters = distance
            )
            fun record(payload: ActivityPayload) = HealthRecord(
                sourceId = "xiaomi_hike_01", localDate = "2026-10-27", measuredAt = start,
                payload = wireJson.encodeToJsonElement(payload).jsonObject
            )
            store.healthImport(HealthImportRequest(HealthDataset.activity, listOf(activity(13_854, 12_040.0, start + 13_857_000).let(::record))))
            var saved = store.list(Kind.activity).single().value<ActivityRecord>()
            assertEquals(13_854, saved.durationSeconds)
            assertEquals(start, saved.startedAt)
            assertEquals(start + 13_857_000, saved.endedAt)
            saved = saved.copy(name = "手工徒步名称", notes = "手工备注", durationSeconds = 13_000)
            store.sync(SyncRequest(mutations = listOf(Mutation(document(Kind.activity, saved.id, saved), store.get(Kind.activity, saved.id)!!.revision))))
            store.healthImport(HealthImportRequest(HealthDataset.activity, listOf(activity(13_900, 12_500.0, start + 13_900_000).let(::record))))
            saved = store.list(Kind.activity).single().value<ActivityRecord>()
            assertEquals("手工徒步名称", saved.name)
            assertEquals("手工备注", saved.notes)
            assertEquals(13_000, saved.durationSeconds)
            assertEquals(12_500.0, saved.distanceMeters)
            val current = store.get(Kind.activity, saved.id)!!
            store.sync(SyncRequest(mutations = listOf(Mutation(current.copy(deleted = true), current.revision))))
            store.healthImport(HealthImportRequest(HealthDataset.activity, listOf(activity(14_000, 13_000.0, start + 14_000_000).let(::record))))
            assertTrue(store.get(Kind.activity, saved.id)!!.deleted)
            assertEquals(1, store.list(Kind.activity, includeDeleted = true).size)
        }
    }
}
