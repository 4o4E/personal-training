package top.e404.training.server

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
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
        val date = java.time.Instant.ofEpochMilli(time).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
        return Session(dailySessionId(date), "实际训练", p, listOf(dumbbell), time, if (status == "active") null else time + 10000, status,
            results = listOf(SetResult("item", 0, reps = 10, load = Load(20.0, "kg", "per_hand"), completedAt = time + 1000)),
            localDate = date.toString())
    }
    private fun Store.put(doc: Document, base: Long = 0): Document = sync(SyncRequest(mutations = listOf(Mutation(doc, base)))).accepted.single()
    private val historicalProgram = Program(
        "historical-program", "三分化",
        listOf(
            SplitSlot("push", "推类训练", listOf("chest")),
            SplitSlot("pull", "拉类训练", listOf("lats")),
            SplitSlot("legs", "腿部训练", listOf("quadriceps"))
        )
    )
    private fun imported(id: String, date: String, label: String?) = Session(
        dailySessionId(LocalDate.parse(date)), "导入训练", null, listOf(dumbbell), null, status = "completed",
        results = listOf(SetResult("item", 0, reps = 10)),
        items = listOf(PlanItem("item", dumbbell.id, sets = listOf(SetTarget(reps = 10)))),
        splitSlotName = label, localDate = date,
        sourceRecords = listOf(SessionSource(id, "导入训练", date,
            historicalSource = HistoricalSource("source-$id", date), splitSlotName = label, itemIds = listOf("item")))
    )

    @Test fun `导入历史分类按既有别名接续当前分化`() {
        assertEquals("pull", nextSlot(historicalProgram, listOf(imported("push-type", "2026-10-26", "推类训练"))).id)
        assertEquals("pull", nextSlot(historicalProgram, listOf(imported("push-short", "2026-10-26", "推"))).id)
        assertEquals("legs", nextSlot(historicalProgram, listOf(imported("pull-type", "2026-10-26", "拉类训练"))).id)
        assertEquals("legs", nextSlot(historicalProgram, listOf(imported("pull-short", "2026-10-26", "拉"))).id)
        assertEquals("push", nextSlot(historicalProgram, listOf(imported("legs", "2026-10-27", "腿部训练"))).id)
        assertEquals("push", nextSlot(historicalProgram, listOf(imported("legs-type", "2026-10-27", "腿类训练"))).id)
        assertEquals("push", nextSlot(historicalProgram, listOf(imported("legs-short", "2026-10-27", "腿"))).id)
    }

    @Test fun `自由历史及其他方案实时训练不推进当前分化`() {
        val free = imported("free-history", "2026-10-27", null)
        val otherProgramSession = session("other-program", "pull", "completed", start)
            .copy(snapshot = plan("other-plan", "pull").copy(programId = "another-program"))
        val ambiguousProgram = historicalProgram.copy(
            slots = historicalProgram.slots + SplitSlot("pull-duplicate", "拉", listOf("upper_back"))
        )
        assertEquals("push", nextSlot(historicalProgram, listOf(free, otherProgramSession)).id)
        assertEquals("push", nextSlot(historicalProgram, listOf(imported("unmatched", "2026-10-27", "腹部"))).id)
        assertEquals("push", nextSlot(ambiguousProgram, listOf(imported("ambiguous", "2026-10-27", "拉"))).id)
    }

    @Test fun `真实日期排序及较早记录重量修订不影响轮换位置`() {
        val earlierTime = LocalDate.parse("2026-10-24").atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val matchingProgram = program.copy(slots = listOf(
            SplitSlot("push", "推类训练", listOf("chest")),
            SplitSlot("pull", "拉类训练", listOf("lats")),
            SplitSlot("legs", "腿部训练", listOf("quadriceps"))
        ))
        val oldLive = session("old-live", "push", "completed", earlierTime)
        val newerLiveBase = session("newer-live", "pull", "completed", earlierTime + 2 * 86_400_000)
        val newerLive = newerLiveBase.copy(results = newerLiveBase.results.map { it.copy(load = Load(35.0)) })
        val olderImported = imported("older-import", "2026-10-25", "推")
        assertEquals("legs", nextSlot(matchingProgram, listOf(oldLive, newerLive, olderImported)).id)
        val correctedOldLive = oldLive.copy(results = oldLive.results.map { it.copy(load = Load(40.0)) })
        assertEquals("legs", nextSlot(matchingProgram, listOf(correctedOldLive, newerLive, olderImported)).id)
        val newestImported = imported("newest-import", "2026-10-27", "推")
        assertEquals("pull", nextSlot(matchingProgram, listOf(newerLive, newestImported)).id)
    }

    @Test fun `手动安排优先且取消同标识不会消费`() {
        val override = ArrangementOverride("manual-history", "legs")
        val cancelled = session("cancelled-manual", "pull", "cancelled", start).copy(
            snapshot = plan("cancelled-plan", "pull").copy(programId = historicalProgram.id),
            arrangementOverrideId = override.id
        )
        assertEquals("legs", nextSlot(historicalProgram, listOf(cancelled), override).id)
        val completed = session("manual-completed", "legs", "completed", start)
            .copy(arrangementOverrideId = override.id)
        assertEquals("push", nextSlot(program, listOf(completed), override).id)
    }

    @Test fun `取消及进行中真实组都推进轮换而零组不推进`() {
        val completed = session("completed", "push", "completed", start)
        val cancelled = session("cancelled", "legs", "cancelled", start + 20000)
        val active = session("active", "pull", "active", start + 40000)
        assertEquals("push", nextSlot(program, emptyList()).id)
        assertEquals("legs", nextSlot(program, listOf(active, cancelled, completed)).id)
        assertEquals("pull", nextSlot(program, listOf(completed, cancelled.copy(results = emptyList()))).id)
        val finishedPull = active.copy(status = "completed", endedAt = start + 50000)
        assertEquals("legs", nextSlot(program, listOf(completed, finishedPull)).id)
    }
    @Test fun `只有实际组关联的计划快照才算已执行`() {
        val actual = session("actual-plan", "push", "completed", start)
        assertEquals(setOf(actual.snapshot!!.id), recordedPlanIds(listOf(actual)))
        val sourced = actual.copy(sourceRecords = listOf(SessionSource(
            "old-source", "旧训练", actual.localDate, snapshot = actual.snapshot, itemIds = listOf("item")
        )))
        assertEquals(setOf(actual.snapshot!!.id), recordedPlanIds(listOf(sourced)))
        assertTrue(recordedPlanIds(listOf(sourced.copy(results = emptyList()))).isEmpty())
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
        val s = Session("session", p.name, p, listOf(dumbbell, assisted, stretch, dynamic), start, start + 5000, "cancelled", results,
            localDate = java.time.Instant.ofEpochMilli(start).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString())
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
    @Test fun `历史动作缺少教学说明仍可保存计划并记录真实训练`() {
        val historicalExercise = dumbbell.copy(
            id = "historical-machine-flat-press",
            name = "历史器械胸推",
            instructions = emptyList(),
            notes = "未记录具体形式"
        )
        val historicalPlan = Plan(
            "historical-machine-plan", "历史器械计划",
            items = listOf(PlanItem(
                "historical-machine-item", historicalExercise.id,
                sets = listOf(SetTarget(reps = 10, load = Load(20.0, "kg", "per_hand")))
            ))
        )
        val date = java.time.Instant.ofEpochMilli(start).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
        val recorded = Session(
            id = dailySessionId(date), name = historicalPlan.name, snapshot = historicalPlan,
            exercises = listOf(historicalExercise), startedAt = start, endedAt = start + 20_000,
            status = "completed",
            results = listOf(SetResult("historical-machine-item", 0, reps = 10,
                load = Load(20.0, "kg", "per_hand"), completedAt = start + 1_000)),
            items = historicalPlan.items, localDate = date.toString()
        )
        Store(directory.resolve("historical-exercise-without-instructions"), seed).use { store ->
            store.put(document(Kind.exercise, historicalExercise.id, historicalExercise))
            store.put(document(Kind.plan, historicalPlan.id, historicalPlan))
            store.put(document(Kind.session, recorded.id, recorded))
            assertEquals(emptyList(), store.get(Kind.exercise, historicalExercise.id)!!.value<Exercise>().instructions)
            assertEquals(historicalPlan, store.get(Kind.plan, historicalPlan.id)!!.value<Plan>())
            val saved = store.get(Kind.session, recorded.id)!!.value<Session>()
            assertEquals(historicalExercise, saved.exercises.single())
            assertEquals(10, saved.results.single().reps)
        }
    }
    @Test fun `独立活动在SQLite和完整备份中保留且不计入力量组`() {
        val activity = ActivityRecord(
            id = "activity-badminton", name = "羽毛球", localDate = "2026-10-22",
            durationSeconds = 7200, notes = "约", sourceId = "source-badminton-2026-10-22"
        )
        val hike = ActivityRecord(
            id = "activity-hike", name = "徒步", localDate = "2026-10-17",
            durationSeconds = 13854, distanceMeters = 12040.0, sourceId = "source-hike-2026-10-17"
        )
        val legacySession = imported("historical-activity-test", "2026-10-22", "推类训练")
        val source = directory.resolve("activity-source")
        Store(source, seed).use { store ->
            store.put(document(Kind.session, legacySession.id, legacySession))
            val nextSlotBeforeActivities = nextSlot(historicalProgram, store.list(Kind.session).map { it.value<Session>() }).id
            store.put(document(Kind.activity, activity.id, activity))
            store.put(document(Kind.activity, hike.id, hike))

            assertEquals(activity, store.get(Kind.activity, activity.id)!!.value<ActivityRecord>())
            val allHistory = summarizeHistory(store.list(Kind.session).map { it.value<Session>() }, store.list(Kind.activity).map { it.value<ActivityRecord>() })
            assertEquals(1, allHistory.strengthSets)
            assertEquals(0, allHistory.stretchSets)
            assertEquals(setOf(activity, hike), allHistory.activities.toSet())
            assertEquals(nextSlotBeforeActivities, nextSlot(historicalProgram, store.list(Kind.session).map { it.value<Session>() }).id)

            val bytes = java.io.ByteArrayOutputStream()
            Archives(source, store).export(bytes)
            val backup = readBackup(bytes.toByteArray().inputStream(), directory.resolve("activity-backup-media"))
            assertEquals(6, backup.formatVersion)
            assertEquals(setOf(activity, hike), backup.documents.filter { it.kind == Kind.activity }.map { it.value<ActivityRecord>() }.toSet())

            Store(directory.resolve("activity-restored"), seed).use { restored ->
                restored.import(backup, replace = false)
                assertEquals(activity, restored.get(Kind.activity, activity.id)!!.value<ActivityRecord>())
                assertEquals(hike, restored.get(Kind.activity, hike.id)!!.value<ActivityRecord>())
                assertEquals(1, summarizeHistory(restored.list(Kind.session).map { it.value<Session>() }, restored.list(Kind.activity).map { it.value<ActivityRecord>() }).strengthSets)
            }
        }
        assertFails {
            validateDocument(
                document(Kind.activity, "invalid-activity", activity.copy(id = "different")),
                { emptyList() }, { _, _ -> null }
            )
        }
        assertFails {
            validateDocument(
                document(Kind.activity, "bad-activity-date", activity.copy(id = "bad-activity-date", localDate = "not-a-date")),
                { emptyList() }, { _, _ -> null }
            )
        }
    }

    @Test fun `历史日期和组内降重在真实SQLite中保留且只计一组`() {
        val history = Session(
            id = dailySessionId(LocalDate.parse("2026-10-13")), name = "历史训练", snapshot = null, exercises = listOf(dumbbell),
            startedAt = null, endedAt = null, status = "completed",
            results = listOf(SetResult("actual", 0, reps = 10, completedAt = null, segments = listOf(
                SetSegment(4, Load(10.0, "kg", "unknown")),
                SetSegment(6, Load(7.5, "kg", "per_hand"))
            ))),
            items = listOf(PlanItem("actual", dumbbell.id, sets = emptyList())),
            localDate = "2026-10-13",
            sourceRecords = listOf(SessionSource("historical", "历史训练", "2026-10-13",
                historicalSource = HistoricalSource("source-2026-10-13", "2026-10-13"), itemIds = listOf("actual")))
        )
        Store(directory.resolve("history-date"), seed).use { store ->
            val saved = store.put(document(Kind.session, history.id, history))
            val restored = saved.value<Session>()
            assertNull(restored.snapshot)
            assertNull(restored.startedAt)
            assertNull(restored.endedAt)
            assertNull(restored.results.single().completedAt)
            assertEquals(LocalDate.parse("2026-10-13"), sessionDate(restored))
            val olderTime = LocalDate.parse("2026-10-12").atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
            val realOlder = session("older-realtime", "push", "completed", olderTime)
            assertEquals(dailySessionId(LocalDate.parse("2026-10-13")), summarizeHistory(listOf(realOlder, restored)).sessions.first().id)
            assertEquals(1, restored.results.size)
            assertEquals(2, restored.results.single().segments.size)
            assertEquals(listOf(4, 6), restored.results.single().segments.map { it.reps })
            assertEquals(listOf("kg", "kg"), restored.results.single().segments.map { it.load?.unit })
            assertEquals(listOf("unknown", "per_hand"), restored.results.single().segments.map { it.load?.meaning })
        }
    }
    @Test fun `旧备份合并到每日格式且v5完整往返`() {
        val date = "2026-10-13"
        val history = Session(
            id = dailySessionId(LocalDate.parse(date)), name = "历史训练", snapshot = null, exercises = listOf(dumbbell),
            startedAt = null, endedAt = null, status = "completed", notes = "原有反馈",
            results = listOf(SetResult("actual", 0, reps = 10, segments = listOf(
                SetSegment(4, Load(10.0, "kg", "unknown")), SetSegment(6, Load(7.5, "kg", "per_hand"))
            ))),
            items = listOf(PlanItem("actual", dumbbell.id, sets = emptyList())), localDate = date,
            sourceRecords = listOf(SessionSource("backup-history", "历史训练", date,
                historicalSource = HistoricalSource("backup-source", date), itemIds = listOf("actual")))
        )
        val sourceWithLegacyText = JsonObject(
            wireJson.encodeToJsonElement(HistoricalSource("backup-source", date)).jsonObject +
                ("originalText" to JsonPrimitive("legacy source"))
        )
        val legacyData = JsonObject(
            wireJson.encodeToJsonElement(history).jsonObject - setOf("localDate", "sourceRecords") +
                mapOf("id" to JsonPrimitive("backup-history"), "historicalSource" to sourceWithLegacyText)
        )
        val oldDocument = Document(Kind.session, "backup-history", legacyData)
        fun zipped(backup: Backup): ByteArray = java.io.ByteArrayOutputStream().also { bytes ->
            java.util.zip.ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("data.json"))
                zip.write(wireJson.encodeToString(backup).toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        for (version in 1..2) {
            val backup = readBackup(
                zipped(Backup(formatVersion = version, documents = listOf(oldDocument), exportedAt = start)).inputStream(),
                directory.resolve("backup-media-v$version")
            )
            assertEquals(version, backup.formatVersion)
            assertEquals(listOf("backup-history"), backup.documents.filter { it.kind == Kind.session && !it.deleted }.map { it.id })
            val migrated = mergeImportedSessions(emptyList(), backup.documents, replace = false)
            val restored = migrated.single { it.kind == Kind.session && !it.deleted }.value<Session>()
            assertEquals(history.results.single().copy(itemId = restored.items.single().id), restored.results.single())
        }
        for (version in 3..5) {
            val restored = readBackup(
                zipped(Backup(formatVersion = version, documents = listOf(document(Kind.session, history.id, history)), exportedAt = start)).inputStream(),
                directory.resolve("backup-media-v$version")
            )
            assertEquals(version, restored.formatVersion)
            assertEquals(history.results, restored.documents.single { it.kind == Kind.session && !it.deleted }.value<Session>().results)
        }
        assertFails { readBackup(zipped(Backup(formatVersion = 3, documents = listOf(oldDocument), exportedAt = start)).inputStream(),
            directory.resolve("backup-media-invalid-v3")) }

        Store(directory.resolve("legacy-store"), seed + oldDocument).use { store ->
            val canonicalId = dailySessionId(LocalDate.parse(date))
            val stored = store.get(Kind.session, canonicalId)!!
            val restoredHistory = stored.value<Session>()
            assertEquals(history.results.single().copy(itemId = restoredHistory.items.single().id), restoredHistory.results.single())
            assertTrue(store.get(Kind.session, "backup-history")!!.deleted)
            val updated = store.put(document(Kind.session, canonicalId, stored.value<Session>().copy(notes = "修改后的反馈")), stored.revision)
            assertEquals("修改后的反馈", updated.value<Session>().notes)
            store.import(Backup(formatVersion = 2, documents = listOf(oldDocument), exportedAt = start), false)
            assertEquals("修改后的反馈", store.get(Kind.session, canonicalId)!!.value<Session>().notes)

            val exportedBytes = java.io.ByteArrayOutputStream()
            Archives(directory.resolve("legacy-store"), store).export(exportedBytes)
            val exported = readBackup(exportedBytes.toByteArray().inputStream(), directory.resolve("backup-media-export"))
            assertEquals(6, exported.formatVersion)
            assertEquals("修改后的反馈", exported.documents.single { it.kind == Kind.session && !it.deleted }.value<Session>().notes)
        }
        val invalid = history.copy(status = "active")
        assertFails {
            validateDocument(document(Kind.session, invalid.id, invalid), { emptyList() }, { _, _ -> null })
        }
    }
    @Test fun `同日旧记录幂等归并保留分类分段与原单位`() {
        val date = "2026-10-30"
        fun legacy(id: String, label: String, load: Load, feedback: String) = Session(
            id, "${label}训练", null, listOf(dumbbell), null, status = "completed",
            results = listOf(SetResult("same-item", 0, reps = 10, segments = listOf(SetSegment(4, load), SetSegment(6, load.copy(value = load.value / 2))))),
            notes = feedback, items = listOf(PlanItem("same-item", dumbbell.id, sets = listOf(SetTarget(reps = 10)))),
            splitSlotName = label, localDate = date
        )
        val sources = listOf(
            document(Kind.session, "legacy-pull", legacy("legacy-pull", "拉", Load(85.0, "lb", "unknown"), "反馈甲")),
            document(Kind.session, "legacy-legs", legacy("legacy-legs", "腿", Load(80.0, "kg", "per_side"), "反馈乙"))
        )
        val normalized = normalizeDailySessionDocuments(sources)
        val merged = normalized.single { it.kind == Kind.session && !it.deleted }.value<Session>()
        assertEquals(dailySessionId(LocalDate.parse(date)), merged.id)
        assertEquals(2, merged.results.size)
        assertEquals(listOf("腿", "拉"), sessionTrainingLabels(merged))
        assertEquals(setOf("lb", "kg"), merged.results.flatMap { it.segments }.mapNotNull { it.load?.unit }.toSet())
        assertEquals(setOf("unknown", "per_side"), merged.results.flatMap { it.segments }.mapNotNull { it.load?.meaning }.toSet())
        assertEquals(merged, normalizeDailySessionDocuments(normalized).single { it.kind == Kind.session && !it.deleted }.value<Session>())
        val reversed = normalizeDailySessionDocuments(sources.reversed())
            .single { it.kind == Kind.session && !it.deleted }.value<Session>()
        assertEquals(merged, reversed)

        Store(directory.resolve("daily-merge"), seed + sources).use { store ->
            val saved = store.list(Kind.session).filterNot { it.deleted }
            assertEquals(1, saved.size)
            assertEquals(2, saved.single().value<Session>().results.size)
            assertTrue(store.get(Kind.session, "legacy-pull")!!.deleted)
            assertTrue(store.get(Kind.session, "legacy-legs")!!.deleted)
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
            val cancelled = session("cancelled-manual-next", "legs", "cancelled", start + 86_400_000).copy(
                results = emptyList(), arrangementOverrideId = nextOverride.id
            )
            store.put(document(Kind.session, cancelled.id, cancelled))
            val sessionsAfterCancelled = store.list(Kind.session).map { it.value<Session>() }
            assertEquals("legs", nextSlot(program, sessionsAfterCancelled, savedArrangement.nextOverrides[program.id]).id)
            assertEquals("push", nextSlot(program, sessionsAfterManual, override).id)
            val nextPush = session("after-manual-push", "push", "completed", start + 172_800_000).copy(snapshot = plan("push-after", "push"))
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

    @Test fun `普通写入不能改写已开始训练的原计划而完整v5恢复可以`() {
        Store(directory, seed).use { store ->
            val started = session("immutable-import", "push", "active", start)
            val stored = store.put(document(Kind.session, started.id, started))
            val changed = started.copy(snapshot = started.snapshot!!.copy(items = started.snapshot!!.items.map { item ->
                item.copy(sets = item.sets.map { it.copy(reps = 12) })
            }))
            assertFailsWith<IllegalArgumentException> {
                store.put(document(Kind.session, changed.id, changed), stored.revision)
            }
            val backup = Backup(formatVersion = 5, documents = listOf(document(Kind.session, changed.id, changed)), exportedAt = start + 2000)
            store.import(backup, replace = true)
            assertEquals(changed, store.get(Kind.session, started.id)!!.value<Session>())
        }
    }

    @Test fun `Mutation完整日恢复允许撤销追加且过期修订仍冲突`() {
        Store(directory, seed).use { store ->
            val baseline = session("mutation-restore", "push", "completed", start)
            val baselineDoc = store.put(document(Kind.session, baseline.id, baseline))
            val extra = PlanItem("post-backup-item", assisted.id, sets = listOf(SetTarget(reps = 6)))
            val extended = baseline.copy(
                exercises = baseline.exercises + assisted,
                items = listOf(baseline.items.single().copy(sets = baseline.items.single().sets + SetTarget(reps = 8))) + extra,
                results = baseline.results + SetResult(extra.id, 0, reps = 6, completedAt = start + 2000),
                sourceRecords = listOf(SessionSource("post-backup-source", "新增来源", baseline.localDate, itemIds = listOf(extra.id)))
            )
            val extendedDoc = store.put(document(Kind.session, baseline.id, extended), baselineDoc.revision)

            assertFailsWith<IllegalArgumentException> {
                store.sync(SyncRequest(mutations = listOf(Mutation(baselineDoc, extendedDoc.revision))))
            }
            val restored = store.sync(SyncRequest(mutations = listOf(
                Mutation(baselineDoc, extendedDoc.revision, restoreSession = true)
            ))).accepted.single()
            assertEquals(baseline, restored.value<Session>())
            val staleRestore = document(Kind.session, baseline.id, baseline.copy(notes = "过期恢复内容"))
            assertEquals(1, store.sync(SyncRequest(mutations = listOf(
                Mutation(staleRestore, restored.revision - 1, restoreSession = true)
            ))).conflicts.size)
            assertEquals(baseline, store.get(Kind.session, baseline.id)!!.value<Session>())

            assertFailsWith<IllegalArgumentException> {
                store.sync(SyncRequest(mutations = listOf(
                    Mutation(document(Kind.plan, "plan-invalid-restore", plan("plan-invalid-restore")), 0, restoreSession = true)
                )))
            }
        }
    }
    @Test fun `canonical归一与v5备份保持原payload且旧省略items使用snapshot动作`() {
        val canonical = session("payload", "push", "completed", start)
        val canonicalDoc = document(Kind.session, canonical.id, canonical)
        assertEquals(canonicalDoc, normalizeDailySessionDocuments(listOf(canonicalDoc)).single())
        val bytes = java.io.ByteArrayOutputStream()
        writeBackup(Backup(documents = listOf(canonicalDoc), exportedAt = start), bytes) { null }
        val restored = readBackup(bytes.toByteArray().inputStream(), directory.resolve("canonical-media"))
        assertEquals(canonicalDoc.data, restored.documents.single().data)

        val date = canonical.localDate
        val historical = Session(
            id = "legacy-items", name = "旧训练", snapshot = canonical.snapshot, exercises = canonical.exercises,
            startedAt = start, endedAt = start + 10000, status = "completed",
            results = listOf(canonical.results.single().copy(itemId = "item")),
            skippedItems = listOf("item"), localDate = date
        )
        val oldData = JsonObject(
            wireJson.encodeToJsonElement(historical).jsonObject - setOf("items", "localDate", "sourceRecords") +
                mapOf("historicalSource" to wireJson.encodeToJsonElement(HistoricalSource("legacy-items", date)))
        )
        val oldDoc = Document(Kind.session, "legacy-items", oldData)
        assertFails { oldDoc.value<Session>() }
        val migrated = normalizeDailySessionDocuments(listOf(oldDoc)).single { it.kind == Kind.session && !it.deleted }.value<Session>()
        assertEquals(1, migrated.results.size)
        assertEquals(migrated.items.single().id, migrated.results.single().itemId)
        assertEquals(migrated.items.single().id, migrated.skippedItems.single())

        val v5Bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(v5Bytes).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("data.json"))
            zip.write(wireJson.encodeToString(Backup(formatVersion = 5, documents = listOf(oldDoc), exportedAt = start)).toByteArray())
            zip.closeEntry()
        }
        assertFailsWith<IllegalArgumentException> {
            readBackup(v5Bytes.toByteArray().inputStream(), directory.resolve("noncanonical-v5"))
        }
    }

    @Test fun `独立旧来源可增量导入幂等冲突替换并保留其他来源`() {
        val date = "2026-10-29"
        fun legacy(id: String, value: Double, feedback: String, label: String) = Session(
            id = id, name = "${label}训练", snapshot = null, exercises = listOf(dumbbell),
            startedAt = null, endedAt = null, status = "completed",
            results = listOf(SetResult("item", 0, reps = 10, load = Load(value, "kg", "per_hand"))),
            notes = feedback, items = listOf(PlanItem("item", dumbbell.id, sets = listOf(SetTarget(reps = 10)))),
            splitSlotName = label, localDate = date
        )
        val a = document(Kind.session, "source-a", legacy("source-a", 20.0, "来源A反馈", "推"))
        val b = document(Kind.session, "source-b", legacy("source-b", 30.0, "来源B反馈", "拉"))
        Store(directory.resolve("incremental-import"), seed).use { store ->
            store.import(Backup(formatVersion = 4, documents = listOf(a), exportedAt = start), replace = false)
            store.import(Backup(formatVersion = 4, documents = listOf(b), exportedAt = start), replace = false)
            val canonicalId = dailySessionId(LocalDate.parse(date))
            val first = store.get(Kind.session, canonicalId)!!.value<Session>()
            assertEquals(2, first.results.size)
            assertEquals(setOf("推", "拉"), sessionTrainingLabels(first).toSet())
            assertTrue(first.notes.contains("来源A反馈") && first.notes.contains("来源B反馈"))
            store.import(Backup(formatVersion = 4, documents = listOf(b), exportedAt = start), replace = false)
            assertEquals(first, store.get(Kind.session, canonicalId)!!.value<Session>())

            val correctedB = document(Kind.session, "source-b", legacy("source-b", 35.0, "修正来源B", "拉"))
            assertFailsWith<ImportConflict> {
                store.import(Backup(formatVersion = 4, documents = listOf(correctedB), exportedAt = start), replace = false)
            }
            store.import(Backup(formatVersion = 4, documents = listOf(correctedB), exportedAt = start), replace = true)
            val replaced = store.get(Kind.session, canonicalId)!!.value<Session>()
            assertEquals(2, replaced.results.size)
            assertEquals(20.0, replaced.results.first { it.load?.value == 20.0 }.load?.value)
            assertTrue(replaced.results.any { it.load?.value == 35.0 })
            assertTrue(replaced.notes.contains("来源A反馈") && replaced.notes.contains("修正来源B"))
            assertFalse(replaced.notes.contains("来源B反馈"))
        }
    }

    @Test fun `追加待执行目标保留已有目标前缀与实际组事实`() {
        val started = session("append-target", "push", "completed", start)
        val oldDoc = document(Kind.session, started.id, started)
        val appendedItem = started.items.single().copy(sets = started.items.single().sets + started.items.single().sets.last())
        val appended = document(Kind.session, started.id, started.copy(items = listOf(appendedItem)))
        validateDocument(
            appended,
            list = { kind -> if (kind == Kind.session) listOf(oldDoc) else emptyList() },
            lookup = { kind, id -> if (kind == Kind.session && id == oldDoc.id) oldDoc else null }
        )
        val changedPrefix = started.items.single().copy(sets = listOf(started.items.single().sets.first().copy(reps = 11)) + started.items.single().sets.drop(1))
        assertFailsWith<IllegalArgumentException> {
            validateDocument(
                document(Kind.session, started.id, started.copy(items = listOf(changedPrefix))),
                list = { kind -> if (kind == Kind.session) listOf(oldDoc) else emptyList() },
                lookup = { kind, id -> if (kind == Kind.session && id == oldDoc.id) oldDoc else null }
            )
        }
    }
    @Test fun `既有完成组可纠正撤销且历史记录可不伪造开始时间继续`() {
        val date = "2026-10-18"
        val history = Session(
            id = dailySessionId(LocalDate.parse(date)), name = "旧训练", snapshot = null,
            exercises = listOf(dumbbell), startedAt = null, endedAt = null, status = "completed",
            results = listOf(SetResult("actual", 0, reps = 10)),
            items = listOf(PlanItem("actual", dumbbell.id, sets = emptyList())),
            localDate = date,
            sourceRecords = listOf(SessionSource(
                "source-history", "旧训练", date,
                historicalSource = HistoricalSource("source-history", date), itemIds = listOf("actual")
            ))
        )
        Store(directory.resolve("history-resume"), seed).use { store ->
            var saved = store.put(document(Kind.session, history.id, history))
            val corrected = history.copy(results = listOf(history.results.single().copy(reps = 12, load = Load(18.0, "lb", "unknown"))))
            saved = store.put(document(Kind.session, history.id, corrected), saved.revision)
            assertEquals(12, saved.value<Session>().results.single().reps)
            assertEquals("lb", saved.value<Session>().results.single().load?.unit)

            val revoked = corrected.copy(status = "cancelled", results = emptyList())
            saved = store.put(document(Kind.session, history.id, revoked), saved.revision)
            assertTrue(saved.value<Session>().results.isEmpty())
            val active = revoked.copy(status = "active")
            saved = store.put(document(Kind.session, history.id, active), saved.revision)
            assertNull(saved.value<Session>().snapshot)
            assertNull(saved.value<Session>().startedAt)

            val appended = active.copy(results = listOf(SetResult(
                "actual", 0, reps = 8, completedAt = start + 3000
            )))
            saved = store.put(document(Kind.session, history.id, appended), saved.revision)
            assertEquals(start + 3000, saved.value<Session>().results.single().completedAt)
            val ended = appended.copy(status = "cancelled", endedAt = start + 5000)
            saved = store.put(document(Kind.session, history.id, ended), saved.revision)
            assertEquals(start + 5000, saved.value<Session>().endedAt)
        }
    }

    @Test fun `过去已选计划复制为稳定模板优先于历史且不使用未来计划`() {
        val date = LocalDate.parse("2026-10-28")
        val pushProgram = program.copy(slots = program.slots.map { if (it.id == "push") it.copy(name = "推") else it })
        val adjusted = plan("manually-adjusted", "push").copy(
            date = "2026-10-22", notes = "计划备注",
            items = listOf(PlanItem(
                "adjusted-item", dumbbell.id, sets = listOf(SetTarget(reps = 7, load = Load(24.0, "lb", "unknown"))),
                notes = "动作备注"
            ))
        )
        val fromPast = resolveTrainingPlan(
            pushProgram, pushProgram.slots.first(), listOf(adjusted), listOf(imported("history-push", "2026-10-27", "推")),
            mapOf("push" to adjusted.id), date
        )!!
        assertNotEquals(adjusted.id, fromPast.id)
        assertNull(fromPast.date)
        assertEquals(adjusted.items, fromPast.items)
        assertEquals(adjusted.notes, fromPast.notes)
        val copiedItem = fromPast.items.single()
        assertEquals(7, copiedItem.sets.single().reps)
        assertEquals(Load(24.0, "lb", "unknown"), copiedItem.sets.single().load)
        assertEquals("动作备注", copiedItem.notes)
        assertEquals(fromPast, resolveTrainingPlan(
            pushProgram, pushProgram.slots.first(), listOf(adjusted), listOf(imported("history-push", "2026-10-27", "推")),
            mapOf("push" to adjusted.id), date
        ))

        val future = adjusted.copy(id = "future-plan", date = "2026-10-29")
        val fromHistory = resolveTrainingPlan(
            pushProgram, pushProgram.slots.first(), listOf(future),
            listOf(imported("history-push-fallback", "2026-10-27", "推")), emptyMap(), date
        )!!
        assertNotEquals(future.id, fromHistory.id)
        assertNull(fromHistory.date)
        assertEquals(10, fromHistory.items.single().sets.first().reps)
    }

    @Test fun `v5完整日恢复撤销备份后追加且删除日不能静默复活`() {
        val date = "2026-10-29"
        val legacy = Session(
            "source-v5", "旧训练", null, listOf(dumbbell), null, status = "completed",
            results = listOf(SetResult("old-item", 0, reps = 10)),
            notes = "来源反馈", items = listOf(PlanItem("old-item", dumbbell.id, sets = listOf(SetTarget(reps = 10)))),
            splitSlotName = "推", localDate = date
        )
        Store(directory.resolve("v5-restore"), seed).use { store ->
            store.import(Backup(formatVersion = 4, documents = listOf(document(Kind.session, "source-v5", legacy)), exportedAt = start), false)
            val dayId = dailySessionId(LocalDate.parse(date))
            val currentDoc = store.get(Kind.session, dayId)!!
            val current = currentDoc.value<Session>()
            val extra = PlanItem("restored-native", assisted.id, sets = listOf(SetTarget(reps = 8)), notes = "原生动作")
            val canonical = current.copy(
                notes = "v5根级反馈",
                exercises = current.exercises + assisted,
                items = current.items + extra,
                skippedItems = listOf(extra.id)
            )
            val bytes = java.io.ByteArrayOutputStream()
            writeBackup(
                Backup(formatVersion = 5, documents = listOf(document(Kind.session, dayId, canonical)), exportedAt = start),
                bytes
            ) { null }
            val backup = readBackup(bytes.toByteArray().inputStream(), directory.resolve("v5-restore-media"))
                .copy(formatVersion = 5, healthManifest = null)
            assertFailsWith<ImportConflict> { store.import(backup, replace = false) }
            store.import(backup, replace = true)
            val initialRestore = store.get(Kind.session, dayId)!!.value<Session>()
            assertEquals("v5根级反馈", initialRestore.notes)
            assertTrue(initialRestore.items.any { it.id == extra.id })
            assertEquals(listOf(extra.id), initialRestore.skippedItems)
            assertTrue(initialRestore.exercises.any { it.id == assisted.id })
            val second = Session(
                "source-v5-second", "另一来源", null, listOf(dumbbell), null, status = "completed",
                results = listOf(SetResult("second-item", 0, reps = 8)),
                items = listOf(PlanItem("second-item", dumbbell.id, sets = listOf(SetTarget(reps = 8)))),
                splitSlotName = "拉", localDate = date
            )
            store.import(
                Backup(formatVersion = 4, documents = listOf(document(Kind.session, second.id, second)), exportedAt = start + 1000),
                replace = false
            )
            val afterSourceDoc = store.get(Kind.session, dayId)!!
            val afterSource = afterSourceDoc.value<Session>()
            val laterItem = PlanItem("post-backup-native", dynamic.id, sets = listOf(SetTarget(reps = 5)))
            val afterBackupChanges = afterSource.copy(
                exercises = afterSource.exercises + dynamic,
                items = afterSource.items.map { item ->
                    if (item.id == extra.id) item.copy(sets = item.sets + SetTarget(reps = 9)) else item
                } + laterItem,
                results = afterSource.results + SetResult(laterItem.id, 0, reps = 5, completedAt = start + 4000)
            )
            store.put(document(Kind.session, dayId, afterBackupChanges), afterSourceDoc.revision)
            assertTrue(store.get(Kind.session, dayId)!!.value<Session>().sourceRecords.any { it.sourceId == second.id })

            store.import(backup, replace = true)
            val restored = store.get(Kind.session, dayId)!!.value<Session>()
            assertEquals(canonical, restored)
            assertFalse(restored.items.any { it.id == "second-item" || it.id == laterItem.id })
            assertFalse(restored.sourceRecords.any { it.sourceId == second.id })

            val restoredDoc = store.get(Kind.session, dayId)!!
            store.sync(SyncRequest(mutations = listOf(Mutation(restoredDoc.copy(deleted = true), restoredDoc.revision))))
            assertFailsWith<ImportConflict> { store.import(backup, replace = false) }
        }
    }
}
