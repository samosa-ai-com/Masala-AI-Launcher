package com.samosaai.masala.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.samosaai.masala.tools.HealthPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The two numbers a home widget shows from Health Connect (plan
 * `docs/plans/widgets-functional-pass.md` §2.2): today's steps and last night's sleep.
 *
 * Read-only and grant-driven: nothing is asked for here and a missing grant simply leaves the value
 * null, which the card renders as "Needs Health Connect" with a way to the permission screen. The
 * aggregate is a single query per metric, cheap enough for a 15-minute refresh.
 */
object HealthSignals {
    data class Reading(
        val steps: Int?,
        val sleepHours: Double?,
        val stepsAccess: Boolean,
        val sleepAccess: Boolean,
    )

    suspend fun read(context: Context): Reading = withContext(Dispatchers.IO) {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            return@withContext Reading(null, null, stepsAccess = false, sleepAccess = false)
        }
        val client = runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()
            ?: return@withContext Reading(null, null, stepsAccess = false, sleepAccess = false)
        val granted = runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
        HealthPermissionState.set(granted.isNotEmpty())
        val zone = ZoneId.systemDefault()
        val now = Instant.now()

        val stepsAccess = HealthPermission.getReadPermission(StepsRecord::class) in granted
        val sleepAccess = HealthPermission.getReadPermission(SleepSessionRecord::class) in granted
        val steps = if (stepsAccess) {
            runCatching {
                val start = LocalDate.now(zone).atStartOfDay(zone).toInstant()
                client.aggregate(
                    AggregateRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(start, now),
                    ),
                )[StepsRecord.COUNT_TOTAL]?.toInt()
            }.getOrNull()
        } else {
            null
        }

        val sleep = if (sleepAccess) {
            runCatching {
                // "Last night": from 18:00 yesterday to now (or noon, so a late refresh does not
                // count tonight's nap as last night).
                val start = LocalDate.now(zone).minusDays(1).atTime(LocalTime.of(18, 0)).atZone(zone).toInstant()
                val end = minOf(now, LocalDate.now(zone).atTime(LocalTime.NOON).atZone(zone).toInstant())
                client.aggregate(
                    AggregateRequest(
                        metrics = setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(start, end),
                    ),
                )[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.toMinutes()?.let { it / 60.0 }
            }.getOrNull()
        } else {
            null
        }
        Reading(steps, sleep, stepsAccess, sleepAccess)
    }
}
