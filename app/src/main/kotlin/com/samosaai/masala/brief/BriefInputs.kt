package com.samosaai.masala.brief

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.samosaai.masala.MasalaApp
import com.samosaai.masala.core.brief.BriefCalendar
import com.samosaai.masala.core.brief.BriefEvent
import com.samosaai.masala.core.brief.BriefInput
import com.samosaai.masala.core.brief.BriefLinks
import com.samosaai.masala.core.brief.BriefTopics
import com.samosaai.masala.core.brief.CalendarRow
import com.samosaai.masala.core.brief.Gathered
import com.samosaai.masala.skills.SkillRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * Gathers the daily brief's data before the model starts (plan `phase5-daily-brief.md` B2, D1-A): today's
 * events, the weather, the followed topics and the links the last two briefs covered. Each part fails on
 * its own, with a reason the model passes on.
 */
class BriefInputs(private val app: MasalaApp) {
    suspend fun collect(zone: ZoneId = ZoneId.systemDefault()): BriefInput = coroutineScope {
        val today = LocalDate.now(zone)
        val events = async(Dispatchers.IO) { events(today, zone) }
        val weather = async { weather() }
        val topics = async { topics() }
        val links = async { recentLinks() }
        BriefInput(today, zone.id, app.voiceLanguage().label, events.await(), weather.await(), topics.await(), links.await())
    }

    private fun events(today: LocalDate, zone: ZoneId): Gathered<List<BriefEvent>> {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return Gathered.Missing("no calendar permission")
        }
        val range = BriefCalendar.queryRange(today, zone)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, range.first)
            ContentUris.appendId(it, range.last)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.STATUS,
        )
        return runCatching {
            val rows = mutableListOf<CalendarRow>()
            app.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(5) == CalendarContract.Instances.STATUS_CANCELED) continue
                    rows += CalendarRow(c.getString(0), c.getLong(1), c.getLong(2), c.getInt(3) == 1, c.getString(4)?.takeIf { it.isNotBlank() })
                }
            } ?: return Gathered.Missing("the calendar couldn't be read")
            Gathered.Ok(BriefCalendar.today(rows, today, zone))
        }.getOrElse {
            Log.w(TAG, "Calendar query failed", it)
            Gathered.Missing("the calendar couldn't be read")
        }
    }

    private suspend fun weather(): Gathered<String> {
        val place = app.settings.current().briefPlace ?: return Gathered.Missing("no place set")
        return app.weather.today(place).fold({ Gathered.Ok(it.line()) }, { Gathered.Missing(it.message ?: "the weather service can't be reached") })
    }

    private suspend fun topics(): List<String> =
        app.secondBrain.readPath(BriefTopics.PATH)?.text?.let(BriefTopics::parse).orEmpty()

    private suspend fun recentLinks(): List<String> = withContext(Dispatchers.IO) {
        BriefLinks.recent(
            app.database.skillRuns().runsFor(SKILL_ID).first()
                .filter { it.status == "done" }
                .mapNotNull { run -> SkillRunner.lastAnswer(run.transcriptJson) },
        )
    }

    companion object {
        const val SKILL_ID = "daily_brief"
        private const val TAG = "BriefInputs"
    }
}
