package top.e404.training.server

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.e404.training.shared.*
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.*

/** 使用实际目录、实际 SQLite 和内置动作资料；不替换网络、数据库或业务实现。 */
class TrainingBehaviorTest {
    @TempDir lateinit var directory: Path
    private val seed by lazy {
        javaClass.classLoader.getResourceAsStream("catalog.json")!!.bufferedReader().use { wireJson.decodeFromString<List<Document>>(it.readText()) }
    }
    private val exercises by lazy { seed.filter { it.kind == Kind.exercise }.map { it.value<Exercise>() } }
    private val dumbbell by lazy { exercises.first { it.type == "strength" && "chest" in it.muscles && it.equipmentTags.any { tag -> tag.contains("dumbbell") } } }
    private val assisted by lazy { exercises.first { it.loadMode == "assistance" && "lats" in it.muscles } }
    private val stretch by lazy { exercises.first { it.type == "stretch" && it.measurement == "seconds" && it.unilateral } }
    private val dynamic by lazy { exercises.first { it.type == "stretch" && it.measurement == "reps" } }
    private val program = Program("program", "分化方案", listOf(SplitSlot("push", "推日", listOf("chest")), SplitSlot("pull", "拉日", listOf("lats")), SplitSlot("legs", "腿日", listOf("quadriceps"))))
    private val start = 1_792_500_000_000L
    private fun plan(id: String = "plan", slot: String = "push") = Plan(id, "训练计划", program.id, slot, date = "2026-10-22", items = listOf(PlanItem("item", dumbbell.id, sets = List(4) { SetTarget(reps = 10, load = Load(20.0, "kg", "per_hand")) })))
    private fun session(id: String, slot: String, status: String, time: Long): Session {
        val p = plan("plan-$id", slot)
        return Session(id, "实际训练", p, listOf(dumbbell), time, if (status == "active") null else time + 10000, status,
            results = listOf(SetResult("item", 0, reps = 10, load = Load(20.0, "kg", "per_hand"), completedAt = time + 1000)))
    }
    private fun Store.put(doc: Document, base: Long = 0): Document = sync(SyncRequest(mutations = listOf(Mutation(doc, base)))).accepted.single()

    @Test fun `取消和进行中训练不推进轮换`() {
        val completed = session("completed", "push", "completed", start)
        val cancelled = session("cancelled", "legs", "cancelled", start + 20000)
        val active = session("active", "pull", "active", start + 40000)
        assertEquals("push", nextSlot(program, emptyList()).id)
        assertEquals("pull", nextSlot(program, listOf(active, cancelled, completed)).id)
        val finishedPull = active.copy(status = "completed", endedAt = start + 50000)
        assertEquals("legs", nextSlot(program, listOf(completed, finishedPull)).id)
    }

    @Test fun `实际组与拉伸分开统计并保留混合单位和助力`() {
        val items = listOf(
            PlanItem("db", dumbbell.id, sets = List(4) { SetTarget(reps = 12, load = Load(22.5, "kg", "per_hand")) }),
            PlanItem("assist", assisted.id, sets = List(4) { SetTarget(reps = 10, load = Load(60.0, "lb", mode = "assistance")) }),
            PlanItem("hold", stretch.id, sets = listOf(SetTarget(seconds = 25, side = "left"), SetTarget(seconds = 25, side = "right"))),
            PlanItem("move", dynamic.id, sets = listOf(SetTarget(reps = 8)))
        )
        val results = listOf(
            SetResult("db", 0, reps = 10, load = Load(20.0, "kg", "per_hand"), completedAt = start + 1000),
            SetResult("assist", 0, reps = 9, load = Load(60.0, "lb", mode = "assistance"), completedAt = start + 2000),
            SetResult("hold", 0, seconds = 25, side = "left", completedAt = start + 3000),
            SetResult("move", 0, reps = 7, completedAt = start + 4000))
        val p = Plan("mixed", "混合训练", items = items)
        val s = Session("session", p.name, p, listOf(dumbbell, assisted, stretch, dynamic), start, start + 5000, "cancelled", results)
        val summary = summarizeHistory(listOf(s))
        assertEquals(2, summary.strengthSets)
        assertEquals(2, summary.stretchSets)
        assertEquals(1, summary.strengthSetsByMuscle["chest"])
        assertEquals(1, summary.strengthSetsByMuscle["lats"])
        assertEquals("cancelled", summary.sessions.single().status)
        assertEquals(Load(20.0, "kg", "per_hand"), summary.sessions.single().results[0].load)
        assertEquals(Load(60.0, "lb", mode = "assistance"), summary.sessions.single().results[1].load)
        assertEquals(11, s.snapshot!!.items.sumOf { it.sets.size })
    }
    @Test fun `历史日期和组内降重在真实SQLite中保留且只计一组`() {
        val history = Session(
            id = "historical", name = "历史训练", snapshot = null, exercises = listOf(dumbbell),
            startedAt = null, endedAt = null, status = "completed",
            results = listOf(SetResult("actual", 0, reps = 10, completedAt = null, segments = listOf(
                SetSegment(4, Load(10.0, "kg", "unknown")),
                SetSegment(6, Load(7.5, "kg", "per_hand"))
            ))),
            items = listOf(PlanItem("actual", dumbbell.id, sets = emptyList())),
            historicalSource = HistoricalSource("source-2026-10-13", "2026-10-13", "原始历史记录")
        )
        Store(directory, seed).use { store ->
            val saved = store.put(document(Kind.session, history.id, history))
            val restored = saved.value<Session>()
            assertNull(restored.snapshot)
            assertNull(restored.startedAt)
            assertNull(restored.endedAt)
            assertNull(restored.results.single().completedAt)
            assertEquals(LocalDate.parse("2026-10-13"), sessionDate(restored))
            val olderTime = LocalDate.parse("2026-10-12").atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
            val realOlder = session("older-realtime", "push", "completed", olderTime)
            assertEquals("historical", summarizeHistory(listOf(realOlder, restored)).sessions.first().id)
            assertEquals(1, restored.results.size)
            assertEquals(2, restored.results.single().segments.size)
            assertEquals(listOf(4, 6), restored.results.single().segments.map { it.reps })
            assertEquals(listOf("kg", "kg"), restored.results.single().segments.map { it.load?.unit })
            assertEquals(listOf("unknown", "per_hand"), restored.results.single().segments.map { it.load?.meaning })
        }
    }
    @Test fun `历史多段记录在一二版备份中往返`() {
        val history = Session(
            id = "backup-history", name = "历史训练", snapshot = null, exercises = listOf(dumbbell),
            startedAt = null, endedAt = null, status = "completed",
            results = listOf(SetResult("actual", 0, reps = 10, segments = listOf(
                SetSegment(4, Load(10.0, "kg", "unknown")), SetSegment(6, Load(7.5, "kg", "per_hand"))
            ))),
            items = listOf(PlanItem("actual", dumbbell.id, sets = emptyList())),
            historicalSource = HistoricalSource("backup-source", "2026-10-13", "原始文本")
        )
        val docs = listOf(document(Kind.session, history.id, history))
        for (version in 1..2) {
            val bytes = java.io.ByteArrayOutputStream()
            writeBackup(Backup(formatVersion = version, documents = docs, exportedAt = start), bytes) { null }
            val restored = readBackup(bytes.toByteArray().inputStream(), directory.resolve("backup-media-$version"))
            assertEquals(version, restored.formatVersion)
            assertEquals(history, restored.documents.single().value<Session>())
            if (version == 2) {
                Store(directory.resolve("import-v2"), seed).use { store ->
                    store.import(restored, replace = false)
                    assertEquals(history, store.get(Kind.session, history.id)!!.value<Session>())
                }
            }
        }
    }


    @Test fun `已完成手动下次选择后不因后续训练重新生效`() {
        Store(directory, seed).use { store ->
            store.put(document(Kind.program, program.id, program))
            val legsPlan = plan("legs-plan", "legs")
            store.put(document(Kind.plan, legsPlan.id, legsPlan))
            val override = ArrangementOverride("manual-next-1", "legs")
            val savedOldArrangement = store.put(document(Kind.arrangement, "current", Arrangement(programId = program.id, nextOverrides = mapOf(program.id to override))))
            val manual = session("manual-legs", "legs", "completed", start).copy(
                snapshot = legsPlan, arrangementOverrideId = override.id
            )
            store.put(document(Kind.session, manual.id, manual))
            val nextOverride = ArrangementOverride("manual-next-2", "legs")
            store.put(
                document(Kind.arrangement, "current", Arrangement(programId = program.id,
                    nextOverrides = mapOf(program.id to nextOverride))),
                savedOldArrangement.revision
            )
            val savedArrangement = store.get(Kind.arrangement, "current")!!.value<Arrangement>()
            val sessionsAfterManual = store.list(Kind.session).map { it.value<Session>() }
            assertEquals("legs", nextSlot(program, sessionsAfterManual, savedArrangement.nextOverrides[program.id]).id)
            val cancelled = session("cancelled-manual-next", "legs", "cancelled", start + 10000).copy(
                results = emptyList(), arrangementOverrideId = nextOverride.id
            )
            store.put(document(Kind.session, cancelled.id, cancelled))
            val sessionsAfterCancelled = store.list(Kind.session).map { it.value<Session>() }
            assertEquals("legs", nextSlot(program, sessionsAfterCancelled, savedArrangement.nextOverrides[program.id]).id)
            assertEquals("push", nextSlot(program, sessionsAfterManual, override).id)
            val nextPush = session("after-manual-push", "push", "completed", start + 20000).copy(snapshot = plan("push-after", "push"))
            store.put(document(Kind.session, nextPush.id, nextPush))
            val sessionsAfterPush = store.list(Kind.session).map { it.value<Session>() }
            assertEquals("pull", nextSlot(program, sessionsAfterPush, override).id)
        }
    }

    @Test fun `并发版本冲突不覆盖内容且其他资料仍可提交`() {
        Store(directory, seed).use { store ->
            val original = store.put(document(Kind.gym, "gym-a", Gym("gym-a", "健身房甲")))
            val current = store.put(document(Kind.gym, "gym-a", Gym("gym-a", "健身房甲的新名称")), original.revision)
            val local = document(Kind.gym, "gym-a", Gym("gym-a", "离线修改"))
            val independent = document(Kind.gym, "gym-b", Gym("gym-b", "健身房乙"))
            val response = store.sync(SyncRequest(mutations = listOf(Mutation(local, original.revision), Mutation(independent, 0))))
            assertEquals(current, response.conflicts.single().remote)
            assertEquals("gym-b", response.accepted.single().id)
            assertEquals(current, store.get(Kind.gym, "gym-a"))
            val replay = store.sync(SyncRequest(mutations = listOf(Mutation(independent, 0))))
            assertEquals(response.accepted.single().revision, replay.accepted.single().revision)
            assertEquals(2, store.list(Kind.gym).size)
        }
        Store(directory, seed).use { store -> assertEquals("健身房甲的新名称", store.get(Kind.gym, "gym-a")!!.value<Gym>().name) }
    }

    @Test fun `已有完成组时替换剩余动作不会改写开始计划`() {
        Store(directory, seed).use { store ->
            store.put(document(Kind.program, program.id, program))
            val p = plan()
            store.put(document(Kind.plan, p.id, p))
            val started = session("session", "push", "active", start).copy(snapshot = p)
            val saved = store.put(document(Kind.session, started.id, started))
            val another = exercises.first { it.id != dumbbell.id && it.type == "strength" && "chest" in it.muscles && it.equipmentTags.any { tag -> tag.contains("dumbbell") } }
            val replacement = PlanItem("replacement", another.id, sets = List(3) { SetTarget(reps = 10, load = Load(20.0, "kg", "per_hand")) })
            val revised = started.copy(items = started.items + replacement, skippedItems = listOf("item"), exercises = started.exercises + another)
            val accepted = store.put(document(Kind.session, revised.id, revised), saved.revision)
            assertEquals(started.snapshot, accepted.value<Session>().snapshot)
            assertEquals(started.results, accepted.value<Session>().results)
            val rewritten = revised.copy(snapshot = revised.snapshot!!.copy(name = "改写原计划"))
            assertFailsWith<IllegalArgumentException> { store.put(document(Kind.session, revised.id, rewritten), accepted.revision) }
            val changedCompletedTarget = revised.copy(items = revised.items.map { if (it.id == "item") it.copy(sets = List(4) { SetTarget(reps = 20) }) else it })
            assertFailsWith<IllegalArgumentException> { store.put(document(Kind.session, revised.id, changedCompletedTarget), accepted.revision) }
            assertEquals(revised, store.get(Kind.session, revised.id)!!.value<Session>())
        }
    }

    @Test fun `目录新增资料参与增量同步并保留用户编辑`() {
        var cursor = 0L
        var edited: Document? = null
        Store(directory, seed).use { store ->
            val original = store.get(Kind.muscle, "chest")!!
            edited = store.put(document(Kind.muscle, "chest", original.value<Muscle>().copy(name = "胸部自定义名称")), original.revision)
            cursor = store.sync(SyncRequest()).cursor
        }
        val sourceMedia = seed.first { it.kind == Kind.media }.value<Media>()
        val additional = document(Kind.media, "additional", sourceMedia.copy(id = "additional", name = "独立素材"))
        Store(directory, seed + additional).use { store ->
            val response = store.sync(SyncRequest(cursor))
            assertEquals(listOf("additional"), response.documents.map { it.id })
            assertTrue(response.cursor > cursor)
            assertEquals(edited, store.get(Kind.muscle, "chest"))
        }
    }

    @Test fun `批量解除关联再删除会按依赖顺序处理`() {
        Store(directory, seed).use { store ->
            val gym = store.put(document(Kind.gym, "gym", Gym("gym", "健身房")))
            val equipment = store.put(document(Kind.equipment, "equipment", Equipment("equipment", "哑铃", "gym", listOf("dumbbell"))))
            assertFailsWith<IllegalArgumentException> { store.put(gym.copy(deleted = true), gym.revision) }
            val response = store.sync(SyncRequest(mutations = listOf(Mutation(gym.copy(deleted = true), gym.revision), Mutation(equipment.copy(deleted = true), equipment.revision))))
            assertTrue(store.get(Kind.gym, "gym")!!.deleted)
            assertTrue(store.get(Kind.equipment, "equipment")!!.deleted)
            assertEquals(setOf("gym", "equipment"), response.accepted.map { it.id }.toSet())
        }
    }

    @Test fun `素材同名上传可重复但不同内容不能覆盖缓存身份`() {
        val pictures = seed.filter { it.kind == Kind.media && it.value<Media>().mimeType == "image/jpeg" }.take(2).map { it.value<Media>() }
        val original = javaClass.classLoader.getResourceAsStream("media/${pictures[0].fileName}")!!.use { it.readBytes() }
        val different = javaClass.classLoader.getResourceAsStream("media/${pictures[1].fileName}")!!.use { it.readBytes() }
        Store(directory, seed).use { store ->
            val archives = Archives(directory, store)
            archives.saveMedia(pictures[0].fileName, original.inputStream())
            assertFailsWith<ImportConflict> { archives.saveMedia(pictures[0].fileName, different.inputStream()) }
            assertContentEquals(original, archives.openMedia(pictures[0].fileName)!!.use { it.readBytes() })
            archives.saveMedia("independent.jpg", different.inputStream())
            assertContentEquals(different, archives.openMedia("independent.jpg")!!.use { it.readBytes() })
        }
    }

    @Test fun `明确替换导入仍不能改写已开始训练的目标`() {
        Store(directory, seed).use { store ->
            val started = session("immutable-import", "push", "active", start)
            store.put(document(Kind.session, started.id, started))
            val changed = started.copy(snapshot = started.snapshot!!.copy(items = started.snapshot!!.items.map { item ->
                item.copy(sets = item.sets.map { it.copy(reps = 12) })
            }))
            val backup = Backup(documents = listOf(document(Kind.session, changed.id, changed)), exportedAt = start + 2000)
            assertFailsWith<IllegalArgumentException> { store.import(backup, true) }
            assertEquals(started, store.get(Kind.session, started.id)!!.value<Session>())
        }
    }
}
