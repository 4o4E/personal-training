package top.e404.training

import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.e404.training.shared.Exercise
import top.e404.training.shared.ActivityRecord
import top.e404.training.shared.HealthDaySummary
import top.e404.training.shared.HealthDataset
import top.e404.training.shared.Load
import top.e404.training.shared.Plan
import top.e404.training.shared.PlanItem
import top.e404.training.shared.Session
import top.e404.training.shared.SetResult
import top.e404.training.shared.SetSegment
import top.e404.training.shared.wireJson
import java.time.LocalDate

class SelectiveExportTest {
    @Test fun rangeIsInclusiveAndRejectsReversedOrEmptyRequests() {
        val from = LocalDate.parse("2026-04-05")
        val to = LocalDate.parse("2026-04-07")
        assertTrue(from.isInExportRange(from, to))
        assertTrue(to.isInExportRange(from, to))
        assertFalse(from.minusDays(1).isInExportRange(from, to))
        assertFailsWith<IllegalArgumentException> {
            validateSelectiveExportRange(to, from, setOf(SelectiveExportItem.training))
        }
        assertFailsWith<IllegalArgumentException> {
            validateSelectiveExportRange(from, to, emptySet())
        }
    }

    @Test fun actualSessionProjectionIncludesOnlyPerformedNamedActionsAndTheirSetValues() {
        val lift = Exercise("lift", "Bench press", muscles = emptyList(), instructions = emptyList())
        val unused = Exercise("unused", "Not done", muscles = emptyList(), instructions = emptyList())
        val session = Session(
            id = "day-2026-04-05",
            name = "Strength",
            snapshot = Plan("secret-plan", "Plan snapshot"),
            exercises = listOf(lift, unused),
            startedAt = 1L,
            endedAt = 2L,
            status = "complete",
            results = listOf(SetResult(
                itemId = "item-private-id",
                setIndex = 0,
                reps = 8,
                load = Load(60.0, "kg"),
                completedAt = 3L,
                segments = listOf(SetSegment(8, Load(60.0, "kg")))
            )),
            items = listOf(
                PlanItem("item-private-id", "lift", sets = emptyList(), notes = "shoulder felt fine"),
                PlanItem("unperformed-item-id", "unused", sets = emptyList())
            ),
            localDate = "2026-04-05"
        )
        val projected = assertNotNull(session.toSelectiveExportRecord())
        assertEquals(null, session.copy(results = emptyList()).toSelectiveExportRecord())
        assertEquals(listOf("Bench press"), projected.actions.map { it.name })
        assertEquals("shoulder felt fine", projected.actions.single().notes)
        val encoded = wireJson.encodeToString(projected)
        assertTrue(encoded.contains("\"reps\":8"))
        assertTrue(encoded.contains("\"unit\":\"kg\""))
        assertFalse(encoded.contains("snapshot"))
        assertFalse(encoded.contains("secret-plan"))
        assertFalse(encoded.contains("item-private-id"))
        assertFalse(encoded.contains("Not done"))
        assertFalse(encoded.contains("unperformed-item-id"))
    }

    @Test fun dailySummaryKeepsStepAndMinuteCaloriesSeparateAndUnknownOmitted() {
        val summary = HealthDaySummary(
            localDate = "2026-04-05",
            revision = 4,
            steps = 0,
            distanceMeters = 0.0,
            stepCaloriesKcal = 12.5,
            calorieMinutesKcal = 23.5
        )
        val steps = summary.toSelectiveHealthSummary(HealthDataset.steps).toString()
        val calories = summary.toSelectiveHealthSummary(HealthDataset.calories).toString()
        assertTrue(steps.contains("\"steps\":0"))
        assertTrue(steps.contains("\"distanceMeters\":0.0"))
        assertFalse(steps.contains("stepCaloriesKcal"))
        assertTrue(calories.contains("\"stepCaloriesKcal\":12.5"))
        assertTrue(calories.contains("\"calorieMinutesKcal\":23.5"))
        assertEquals(null, HealthDaySummary("2026-04-05", 4).toSelectiveHealthSummary(HealthDataset.steps))
    }

    @Test fun customActivityExportExcludesOnlyAutomaticXiaomiProjection() {
        assertFalse(ActivityRecord("xiaomi-run", "Auto", "2026-04-05",
            sourceId = "misports:activities:run").isSelectiveCustomActivity())
        assertTrue(ActivityRecord("manual-run", "Manual", "2026-04-05",
            sourceId = "misports:activities:run").isSelectiveCustomActivity())
    }
}
