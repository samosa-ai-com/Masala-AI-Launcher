package com.samosaai.masala.core.scheduled

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** [ScheduledTaskPlanner]: what a task does when it fires, and how it moves on (issue #132). */
class ScheduledTaskPlannerTest {

    private val zone = ZoneId.of("UTC")
    private val minute = 60_000L
    private val hour = 60 * minute

    private fun at(local: String): Long = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    private fun daily(now: Long = at("2026-10-19T07:00"), retries: Int = 1) = ScheduledTaskPlanner.armed(
        ScheduledTask(
            id = "t",
            name = "n",
            prompt = "p",
            startLocal = "2026-10-19T08:00",
            repeat = Repeat(RepeatKind.DAILY),
            maxRetries = retries
        ),
        now,
        zone
    )

    @Test
    fun `arming sets the next time from now`() {
        assertEquals(at("2026-10-19T08:00"), daily().nextRunAt)
        assertEquals(at("2026-10-20T08:00"), daily(now = at("2026-10-19T09:00")).nextRunAt)
    }

    @Test
    fun `a task waits until its time, then runs`() {
        val task = daily()
        assertEquals(
            ScheduledTaskPlanner.Decision.Wait,
            ScheduledTaskPlanner.decide(task, at("2026-10-19T07:59"), zone)
        )
        assertEquals(ScheduledTaskPlanner.Decision.Run, ScheduledTaskPlanner.decide(task, at("2026-10-19T08:00"), zone))
        // An alarm a few seconds early still counts.
        assertEquals(
            ScheduledTaskPlanner.Decision.Run,
            ScheduledTaskPlanner.decide(task, at("2026-10-19T08:00") - 10_000, zone)
        )
    }

    @Test
    fun `a paused task never runs`() {
        val task = daily().copy(enabled = false)
        assertNull(task.dueAt)
        assertEquals(
            ScheduledTaskPlanner.Decision.Wait,
            ScheduledTaskPlanner.decide(task, at("2026-10-19T08:00"), zone)
        )
    }

    @Test
    fun `a task found well past its time is recorded as missed and moves on`() {
        val decision = ScheduledTaskPlanner.decide(daily(), at("2026-10-19T10:00"), zone)
        val missed = (decision as ScheduledTaskPlanner.Decision.Missed).task
        assertEquals(TaskRunStatus.MISSED, missed.lastRun!!.status)
        assertEquals(at("2026-10-20T08:00"), missed.nextRunAt)
    }

    @Test
    fun `a task a little late still runs`() {
        assertEquals(
            ScheduledTaskPlanner.Decision.Run,
            ScheduledTaskPlanner.decide(daily(), at("2026-10-19T08:40"), zone)
        )
    }

    @Test
    fun `starting the run moves the task to its next time`() {
        val started = ScheduledTaskPlanner.started(daily(), at("2026-10-19T08:00"), zone)
        assertEquals(at("2026-10-20T08:00"), started.nextRunAt)
        assertNull(started.retryAt)
    }

    @Test
    fun `a failure is retried after the delay, without skipping the next time`() {
        val now = at("2026-10-19T08:00")
        val started = ScheduledTaskPlanner.started(daily(), now, zone)
        val failed = ScheduledTaskPlanner.finished(started, TaskRunStatus.FAILED, "chat", "No network", now + minute)
        assertEquals(now + minute + 10 * minute, failed.retryAt)
        assertEquals(failed.retryAt, failed.dueAt)
        assertEquals("No network", failed.lastRun!!.detail)

        // The retry fires; its start must leave tomorrow's run where it is.
        val retryAt = failed.retryAt!!
        assertEquals(ScheduledTaskPlanner.Decision.Run, ScheduledTaskPlanner.decide(failed, retryAt, zone))
        val retried = ScheduledTaskPlanner.started(failed, retryAt, zone)
        assertEquals(at("2026-10-20T08:00"), retried.nextRunAt)

        // Out of retries: the second failure is final.
        val failedAgain = ScheduledTaskPlanner.finished(retried, TaskRunStatus.FAILED, "chat", null, retryAt + minute)
        assertNull(failedAgain.retryAt)
        assertEquals(at("2026-10-20T08:00"), failedAgain.dueAt)
        assertEquals(2, failedAgain.history.size)
    }

    private fun hourly(now: Long = at("2026-10-19T07:00")) = ScheduledTaskPlanner.armed(
        ScheduledTask(
            id = "h",
            name = "n",
            prompt = "p",
            startLocal = "2026-10-19T08:00",
            repeat = Repeat(RepeatKind.HOURLY)
        ),
        now,
        zone
    )

    @Test
    fun `a retry that would land after the next scheduled time is left to that run`() {
        val started = ScheduledTaskPlanner.started(hourly(), at("2026-10-19T08:00"), zone)
        assertEquals(at("2026-10-19T09:00"), started.nextRunAt)

        // Failed at 08:55: a retry at 09:05 would come after the 09:00 run.
        val late = ScheduledTaskPlanner.finished(started, TaskRunStatus.FAILED, "chat", null, at("2026-10-19T08:55"))
        assertNull(late.retryAt)
        assertEquals(0, late.retriesUsed)
        assertEquals(at("2026-10-19T09:00"), late.dueAt)
        assertEquals(TaskRunStatus.FAILED, late.lastRun!!.status)

        // Failed at 08:30: the retry at 08:40 comes first, and keeps 09:00 as it is.
        val early = ScheduledTaskPlanner.finished(started, TaskRunStatus.FAILED, "chat", null, at("2026-10-19T08:30"))
        assertEquals(at("2026-10-19T08:40"), early.retryAt)
        val retried = ScheduledTaskPlanner.started(early, at("2026-10-19T08:40"), zone)
        assertEquals(at("2026-10-19T09:00"), retried.nextRunAt)
        assertEquals(1, retried.retriesUsed)
    }

    @Test
    fun `a retry delivered after the next scheduled time is one run for both`() {
        val started = ScheduledTaskPlanner.started(hourly(), at("2026-10-19T08:00"), zone)
        val failed = ScheduledTaskPlanner.finished(started, TaskRunStatus.FAILED, "chat", null, at("2026-10-19T08:30"))
        // The phone slept through 08:40 and woke at 09:01.
        val now = at("2026-10-19T09:01")
        assertEquals(ScheduledTaskPlanner.Decision.Run, ScheduledTaskPlanner.decide(failed, now, zone))
        val run = ScheduledTaskPlanner.started(failed, now, zone)
        assertEquals(at("2026-10-19T10:00"), run.nextRunAt)
        assertNull(run.retryAt)
        assertEquals(0, run.retriesUsed)
    }

    @Test
    fun `a new occurrence that comes while an older one waits on a busy runner replaces it`() {
        val waiting = ScheduledTaskPlanner.busy(hourly(), at("2026-10-19T08:00"), zone)
        assertEquals(at("2026-10-19T09:00"), waiting.nextRunAt)
        val recheck = at("2026-10-19T08:58")
        val stillWaiting = ScheduledTaskPlanner.busy(waiting.copy(retryAt = recheck), recheck, zone)
        assertEquals(at("2026-10-19T08:00"), stillWaiting.busySince)

        // 09:00 comes while 08:00 still waits: 08:00 is given up, and 09:00 waits on its own.
        val now = at("2026-10-19T09:00")
        val replaced = ScheduledTaskPlanner.busy(stillWaiting, now, zone)
        assertEquals(TaskRunStatus.SKIPPED_BUSY, replaced.lastRun!!.status)
        assertEquals(now, replaced.busySince)
        assertEquals(at("2026-10-19T10:00"), replaced.nextRunAt)
        assertEquals(now + ScheduledTaskPlanner.BUSY_RECHECK_MS, replaced.dueAt)
    }

    @Test
    fun `a quick pause and resume keeps a pending retry`() {
        val now = at("2026-10-19T08:00")
        val started = ScheduledTaskPlanner.started(daily(), now, zone)
        val failed = ScheduledTaskPlanner.finished(started, TaskRunStatus.FAILED, "chat", null, now + minute)

        val paused = ScheduledTaskPlanner.toggled(failed, false, now + 2 * minute, zone)
        assertNull(paused.dueAt)
        val resumed = ScheduledTaskPlanner.toggled(paused, true, now + 3 * minute, zone)
        assertEquals(failed.retryAt, resumed.retryAt)
        assertEquals(1, resumed.retriesUsed)
        assertEquals(failed.dueAt, resumed.dueAt)
    }

    @Test
    fun `a resume after the retry's time drops it and moves the schedule past the pause`() {
        val now = at("2026-10-19T08:00")
        val started = ScheduledTaskPlanner.started(daily(), now, zone)
        val failed = ScheduledTaskPlanner.finished(started, TaskRunStatus.FAILED, "chat", null, now + minute)
        val paused = ScheduledTaskPlanner.toggled(failed, false, now + 2 * minute, zone)

        val resumed = ScheduledTaskPlanner.toggled(paused, true, at("2026-10-21T12:00"), zone)
        assertNull(resumed.retryAt)
        assertEquals(0, resumed.retriesUsed)
        assertEquals(at("2026-10-22T08:00"), resumed.nextRunAt)
    }

    @Test
    fun `a task with no retries gives a failure up at once`() {
        val now = at("2026-10-19T08:00")
        val started = ScheduledTaskPlanner.started(daily(retries = 0), now, zone)
        assertNull(ScheduledTaskPlanner.finished(started, TaskRunStatus.FAILED, "chat", null, now).retryAt)
    }

    @Test
    fun `a busy runner makes the task wait, then give the occurrence up`() {
        val now = at("2026-10-19T08:00")
        val waiting = ScheduledTaskPlanner.busy(daily(), now, zone)
        // The past time is claimed, so it can't keep the alarm firing.
        assertEquals(now + ScheduledTaskPlanner.BUSY_RECHECK_MS, waiting.dueAt)
        assertEquals(at("2026-10-20T08:00"), waiting.nextRunAt)
        assertEquals(now, ScheduledTaskPlanner.busy(waiting, now + 2 * minute, zone).busySince)
        // A long wait is not "missed": it was due all along.
        val later = now + 50 * minute
        assertEquals(
            ScheduledTaskPlanner.Decision.Run,
            ScheduledTaskPlanner.decide(waiting.copy(retryAt = later), later, zone)
        )

        val gaveUp = ScheduledTaskPlanner.busy(waiting, now + hour, zone)
        assertEquals(TaskRunStatus.SKIPPED_BUSY, gaveUp.lastRun!!.status)
        assertEquals(at("2026-10-20T08:00"), gaveUp.dueAt)
    }

    @Test
    fun `a run that waited for a busy runner still moves to the next time when it starts`() {
        val now = at("2026-10-19T08:00")
        val waiting = ScheduledTaskPlanner.busy(daily(), now, zone)
        val started = ScheduledTaskPlanner.started(waiting, now + 4 * minute, zone)
        assertEquals(at("2026-10-20T08:00"), started.nextRunAt)
        assertNull(started.busySince)
    }

    @Test
    fun `a one-time task has nothing left once it has run`() {
        val once = ScheduledTaskPlanner.armed(
            ScheduledTask(id = "t", name = "n", prompt = "p", startLocal = "2026-10-19T08:00"),
            at("2026-10-19T07:00"),
            zone
        )
        val started = ScheduledTaskPlanner.started(once, at("2026-10-19T08:00"), zone)
        assertNull(started.dueAt)
        assertNull(ScheduledTaskPlanner.nextAlarm(listOf(started)))
    }

    @Test
    fun `the alarm is set for the soonest task`() {
        val soon = daily()
        val later = daily(now = at("2026-10-19T09:00")).copy(id = "u")
        assertEquals(soon.nextRunAt, ScheduledTaskPlanner.nextAlarm(listOf(later, soon)))
        assertTrue(ScheduledTaskPlanner.nextAlarm(emptyList()) == null)
    }

    @Test
    fun `a finished run keeps its skill run link`() {
        val now = at("2026-10-19T08:00")
        val started = ScheduledTaskPlanner.started(daily(), now, zone)
        val failed = ScheduledTaskPlanner.finished(started, TaskRunStatus.FAILED, "chat", null, now + minute, skillRunId = 7)
        assertEquals(7, failed.lastRun!!.skillRunId)
        assertEquals("chat", failed.lastRun!!.runId)
        val plain = ScheduledTaskPlanner.finished(started, TaskRunStatus.FAILED, "chat", null, now + minute)
        assertNull(plain.lastRun!!.skillRunId)
    }

    @Test
    fun `history keeps the newest runs only`() {
        var task = daily()
        repeat(
            ScheduledTask.MAX_HISTORY + 5
        ) { task = task.withRun(TaskRun(at = it.toLong(), status = TaskRunStatus.DONE)) }
        assertEquals(ScheduledTask.MAX_HISTORY, task.history.size)
        assertEquals((ScheduledTask.MAX_HISTORY + 4).toLong(), task.history.last().at)
    }

    private fun valid(now: Long = at("2026-10-19T07:00")) = ScheduledTask(
        id = "t",
        name = "n",
        prompt = "p",
        startLocal = "2026-10-19T08:00",
        repeat = Repeat(RepeatKind.DAILY)
    ).let { it to now }

    @Test
    fun `a task without a name can't be saved`() {
        val (task, now) = valid()
        assertTrue(ScheduledTaskPlanner.problems(task, now, zone).isEmpty())
        val problems = ScheduledTaskPlanner.problems(task.copy(name = "  "), now, zone)
        assertEquals(1, problems.size)
    }

    @Test
    fun `a task with nothing to run can't be saved`() {
        val (task, now) = valid()
        assertTrue(ScheduledTaskPlanner.problems(task.copy(prompt = "  "), now, zone).isNotEmpty())
        assertTrue(ScheduledTaskPlanner.problems(task.copy(prompt = "  ", skillId = "s"), now, zone).isEmpty())
    }

    @Test
    fun `a task repeating every zero can't be saved`() {
        val (task, now) = valid()
        val problems = ScheduledTaskPlanner.problems(task.copy(repeat = Repeat(RepeatKind.DAILY, interval = 0)), now, zone)
        assertEquals(1, problems.size)
    }

    @Test
    fun `a weekly task with no weekdays can't be saved`() {
        val (task, now) = valid()
        val problems = ScheduledTaskPlanner.problems(task.copy(repeat = Repeat(RepeatKind.WEEKLY)), now, zone)
        assertTrue(problems.isNotEmpty())
        val withDays = task.copy(repeat = Repeat(RepeatKind.WEEKLY, weekdays = listOf(1)))
        assertTrue(ScheduledTaskPlanner.problems(withDays, now, zone).isEmpty())
    }

    @Test
    fun `a one-time task starting in the past can't be saved`() {
        val (task, now) = valid()
        val past = task.copy(repeat = Repeat(), startLocal = "2026-10-19T06:00")
        assertTrue(ScheduledTaskPlanner.problems(past, now, zone).isNotEmpty())
        val future = task.copy(repeat = Repeat(), startLocal = "2026-10-19T08:00")
        assertTrue(ScheduledTaskPlanner.problems(future, now, zone).isEmpty())
    }

    @Test
    fun `a task with no next run can't be saved`() {
        val (task, now) = valid()
        val past = task.copy(repeat = Repeat(), startLocal = "2026-10-19T06:00")
        assertNull(ScheduledTaskPlanner.armed(past, now, zone).nextRunAt)
        assertTrue(ScheduledTaskPlanner.problems(past, now, zone).isNotEmpty())
        assertTrue(ScheduledTaskPlanner.problems(task, now, zone).isEmpty())
    }

    @Test
    fun `an edit from an older copy keeps a run that finished meanwhile`() {
        // The editor opens at 07:59 on the 08:00 daily task; it runs at 08:00; the rename is saved at 08:02.
        val opened = daily()
        val ran = ScheduledTaskPlanner.finished(
            ScheduledTaskPlanner.started(opened, at("2026-10-19T08:00"), zone),
            TaskRunStatus.DONE, "run-1", null, at("2026-10-19T08:01"), skillRunId = 7L,
        )
        val saved = ScheduledTaskPlanner.savedEdit(ran, opened.copy(name = "renamed"), at("2026-10-19T08:02"), zone)
        assertEquals("renamed", saved.name)
        assertEquals(ran.history, saved.history)
        assertEquals(at("2026-10-20T08:00"), saved.nextRunAt)
        // Not due again today: no second run of the 08:00 occurrence.
        assertEquals(ScheduledTaskPlanner.Decision.Wait, ScheduledTaskPlanner.decide(saved, at("2026-10-19T08:03"), zone))
    }

    @Test
    fun `an edit that changes the schedule re-arms from the stored task`() {
        val ran = ScheduledTaskPlanner.finished(
            ScheduledTaskPlanner.started(daily(), at("2026-10-19T08:00"), zone),
            TaskRunStatus.DONE, "run-1", null, at("2026-10-19T08:01"),
        )
        val saved = ScheduledTaskPlanner.savedEdit(ran, daily().copy(startLocal = "2026-10-19T09:00"), at("2026-10-19T08:02"), zone)
        assertEquals(at("2026-10-19T09:00"), saved.nextRunAt)
        assertEquals(ran.history, saved.history)
    }
}
