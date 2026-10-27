package com.samosaai.masala.tools

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Health Connect throws a SecurityException when an aggregate names a metric
 * the user has not granted, so the summary request must be built from the
 * grants actually held — a partial grant has to still produce a summary.
 * From the private prototype's test, narrowed to Masala's steps and sleep (arch §4).
 */
class HealthPermissionScopeTest {

    private fun read(type: kotlin.reflect.KClass<out androidx.health.connect.client.records.Record>) =
        HealthPermission.getReadPermission(type)

    @Test
    fun `no grants means no metrics`() {
        assertTrue(HealthTool.summaryMetricsFor(emptySet()).isEmpty())
    }

    @Test
    fun `a steps-only grant asks only for the steps metric`() {
        val metrics = HealthTool.summaryMetricsFor(setOf(read(StepsRecord::class)))
        assertEquals(setOf(StepsRecord.COUNT_TOTAL), metrics)
    }

    @Test
    fun `a sleep and steps grant asks for both, and grants Masala doesn't request add nothing`() {
        val metrics = HealthTool.summaryMetricsFor(
            setOf(read(StepsRecord::class), read(SleepSessionRecord::class), read(HeartRateRecord::class), read(WeightRecord::class))
        )
        assertEquals(setOf(StepsRecord.COUNT_TOTAL, SleepSessionRecord.SLEEP_DURATION_TOTAL), metrics)
    }

    @Test
    fun `Masala requests only steps and sleep`() {
        assertEquals(setOf(read(StepsRecord::class), read(SleepSessionRecord::class)), HealthTool.PERMISSIONS)
        assertEquals(listOf("steps", "sleep"), HealthTool.RECORD_TYPE_NAMES)
    }

    @Test
    fun `workouts alone yield nothing to aggregate`() {
        // ExerciseSessionRecord has no summary metric; getSummary treats this as
        // "nothing to show" rather than issuing an empty aggregate request.
        assertTrue(HealthTool.summaryMetricsFor(setOf(read(ExerciseSessionRecord::class))).isEmpty())
    }

    @Test
    fun `granting everything covers every summary metric`() {
        assertEquals(2, HealthTool.summaryMetricsFor(HealthTool.PERMISSIONS).size)
    }
}
