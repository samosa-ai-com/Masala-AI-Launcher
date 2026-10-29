package com.samosaai.masala.tools

import com.samosaai.masala.core.tool.ActionResult
import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * From the private prototype's CalendarTool: reading events only. Creating, editing and deleting need `WRITE_CALENDAR`,
 * which isn't on the core allowlist.
 */
class CalendarTool(private val context: Context) {

    private val readable = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    @Suppress("CyclomaticComplexMethod")
    fun listEvents(
        daysAhead: Int? = null,
        fromDate: String? = null,
        toDate: String? = null,
        search: String? = null
    ): ActionResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ActionResult.permissionNeeded(
                Manifest.permission.READ_CALENDAR,
                "The Calendar permission is not granted. Go to Settings → Permissions → Calendar and enable it, then ask again."
            )
        }
        return try {
            val (rangeStart, rangeEnd, rangeDesc) = resolveRange(daysAhead, fromDate, toDate)

            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, rangeStart)
            ContentUris.appendId(builder, rangeEnd)

            val projection = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.DESCRIPTION,
                CalendarContract.Instances.STATUS,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME
            )

            var selection: String? = null
            var selectionArgs: Array<String>? = null
            if (!search.isNullOrBlank()) {
                selection = "${CalendarContract.Instances.TITLE} LIKE ?"
                selectionArgs = arrayOf("%$search%")
            }

            context.contentResolver.query(
                builder.build(),
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Instances.BEGIN} ASC"
            ).use { cursor ->
                if (cursor == null) {
                    return ActionResult.error("Could not read the calendar (permission may not be granted).")
                }
                val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
                val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                val beginIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
                val locIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
                val descIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.DESCRIPTION)
                val statusIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.STATUS)
                val calIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
                val out = StringBuilder()
                var count = 0
                val indices = EventCursorIndices(idIdx, titleIdx, beginIdx, endIdx, locIdx, descIdx, statusIdx, calIdx)
                while (cursor.moveToNext() && count < 50) {
                    out.append(formatEventRow(cursor, indices))
                    count++
                }
                if (count == 0) {
                    val hint = if (!search.isNullOrBlank()) " matching '$search'" else ""
                    ActionResult.ok("No events $rangeDesc$hint.")
                } else {
                    ActionResult.ok("Events $rangeDesc ($count):\n$out")
                }
            }
        } catch (e: Exception) {
            ActionResult.error("Could not read the calendar: ${e.message}")
        }
    }

    private data class EventCursorIndices(
        val id: Int,
        val title: Int,
        val begin: Int,
        val end: Int,
        val location: Int,
        val description: Int,
        val status: Int,
        val calendarName: Int
    )

    private fun formatEventRow(cursor: android.database.Cursor, idx: EventCursorIndices): String {
        val eventId = cursor.getLong(idx.id)
        val title = cursor.getString(idx.title) ?: "(untitled)"
        val begin = readable.format(Date(cursor.getLong(idx.begin)))
        val end = readable.format(Date(cursor.getLong(idx.end)))
        val loc = cursor.getString(idx.location)
        val desc = cursor.getString(idx.description)
        val status = when (cursor.getInt(idx.status)) {
            CalendarContract.Instances.STATUS_CONFIRMED -> "confirmed"
            CalendarContract.Instances.STATUS_TENTATIVE -> "tentative"
            CalendarContract.Instances.STATUS_CANCELED -> "cancelled"
            else -> null
        }
        val calName = cursor.getString(idx.calendarName)

        return buildString {
            append("[id=$eventId]  $begin")
            if (status != null) append(" [$status]")
            append("  $title")
            if (calName != null) append("  📅 $calName")
            append("\n    ends $end")
            if (!loc.isNullOrBlank()) append("\n    📍 $loc")
            if (!desc.isNullOrBlank()) append("\n    ${desc.take(200)}")
            reminderMinutesFor(eventId).takeIf { it.isNotEmpty() }?.let {
                append("\n    ⏰ reminder(s): ${it.joinToString(", ") { m -> "${m}m before" }}")
            }
            attendeesFor(eventId).takeIf { it.isNotEmpty() }?.let {
                append("\n    👤 attendees: ${it.joinToString(", ")}")
            }
            append("\n")
        }
    }

    private fun resolveRange(daysAhead: Int?, fromDate: String?, toDate: String?): Triple<Long, Long, String> {
        val now = System.currentTimeMillis()
        if (fromDate == null && toDate == null) {
            val days = (daysAhead ?: 7).coerceIn(1, 365)
            return Triple(now, now + days * 24L * 60 * 60 * 1000, "next $days day(s)")
        }
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val rangeStart = if (fromDate != null) {
            try { df.parse(fromDate)?.time ?: now } catch (_: Exception) { now }
        } else {
            now
        }
        val rangeEnd = if (toDate != null) {
            try {
                df.parse(toDate)?.time?.plus(86_400_000L - 1) ?: (now + 86400000L)
            } catch (_: Exception) {
                now + 86400000L
            }
        } else {
            rangeStart + 86400000L
        }
        return Triple(rangeStart, rangeEnd, "${fromDate ?: "today"} → ${toDate ?: "tomorrow"}")
    }

    private fun reminderMinutesFor(eventId: Long): List<Int> {
        val minutes = mutableListOf<Int>()
        context.contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders.MINUTES),
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null
        )?.use { cursor ->
            val idx = cursor.getColumnIndexOrThrow(CalendarContract.Reminders.MINUTES)
            while (cursor.moveToNext()) minutes.add(cursor.getInt(idx))
        }
        return minutes
    }

    private fun attendeesFor(eventId: Long): List<String> {
        val emails = mutableListOf<String>()
        context.contentResolver.query(
            CalendarContract.Attendees.CONTENT_URI,
            arrayOf(CalendarContract.Attendees.ATTENDEE_EMAIL),
            "${CalendarContract.Attendees.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null
        )?.use { cursor ->
            val idx = cursor.getColumnIndexOrThrow(CalendarContract.Attendees.ATTENDEE_EMAIL)
            while (cursor.moveToNext()) cursor.getString(idx)?.let { emails.add(it) }
        }
        return emails
    }

    /**
     * Adds one event (plan: the model could read the calendar but not write to it).
     * Times are ISO local (`2026-11-02T09:00`) or a plain date for an all-day event.
     */
    fun createEvent(
        title: String,
        start: String,
        end: String? = null,
        location: String? = null,
        notes: String? = null,
        calendarName: String? = null,
    ): ActionResult {
        // Reading the calendar list is part of writing an event, and Android grants the two
        // separately on some phones, so both are needed and each is asked for on its own.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ActionResult.permissionNeeded(
                Manifest.permission.READ_CALENDAR,
                "The Calendar permission (read) is not granted, and the app needs it to find a " +
                    "calendar to write to.",
            )
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ActionResult.permissionNeeded(
                Manifest.permission.WRITE_CALENDAR,
                "The Calendar permission (write) is not granted.",
            )
        }
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return ActionResult.error("The event needs a title.")
        val allDay = start.length == 10
        val startAt = parseMoment(start, endOfDay = false) ?: return ActionResult.error(
            "Couldn't read the start time '$start'. Use 2026-11-02T09:00 or an all-day date 2026-11-02.",
        )
        val endAt = when {
            end != null -> parseMoment(end, endOfDay = end.length == 10) ?: return ActionResult.error(
                "Couldn't read the end time '$end'.",
            )
            allDay -> startAt + 86_400_000L
            else -> startAt + 3_600_000L
        }
        if (endAt < startAt) return ActionResult.error("The event ends before it starts.")
        val calendarId = calendarId(calendarName) ?: return ActionResult.error(
            "No writable calendar on this phone" + (calendarName?.let { " named '$it'" } ?: "") + ".",
        )
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, cleanTitle)
            put(CalendarContract.Events.DTSTART, startAt)
            put(CalendarContract.Events.DTEND, endAt)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            location?.takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            notes?.takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }
        return try {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return ActionResult.error("The calendar app refused the event.")
            val id = uri.lastPathSegment
            val whenText = readable.format(Date(startAt)) + if (allDay) " (all day)" else ""
            ActionResult.ok("Added '$cleanTitle' on $whenText (event id $id).")
        } catch (e: SecurityException) {
            // The provider can still refuse (a read-only calendar, a work profile): report the
            // permission when that is the cause so the executor can ask, otherwise say what failed.
            val message = e.message.orEmpty()
            when {
                message.contains("READ_CALENDAR") -> ActionResult.permissionNeeded(
                    Manifest.permission.READ_CALENDAR, "Reading the calendar was refused.",
                )
                message.contains("WRITE_CALENDAR") -> ActionResult.permissionNeeded(
                    Manifest.permission.WRITE_CALENDAR, "Writing to the calendar was refused.",
                )
                else -> ActionResult.error("The calendar app refused the event: $message")
            }
        } catch (e: IllegalArgumentException) {
            ActionResult.error("The calendar app rejected the event: ${e.message}")
        }
    }

    /**
     * The calendar to write to: the named one when given, else the primary writable one, else any
     * writable one, else the first listed. Samsung calendars often mark neither IS_PRIMARY nor
     * VISIBLE, so the access level decides writability, not those flags.
     */
    private fun calendarId(name: String?): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        return try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection, null, null, null,
            )?.use { cursor ->
                var named: Long? = null
                var primaryWritable: Long? = null
                var writable: Long? = null
                var primary: Long? = null
                var any: Long? = null
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val display = cursor.getString(1)
                    val access = cursor.getInt(2)
                    val isPrimary = cursor.getInt(3) == 1
                    val canWrite = access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
                    if (any == null) any = id
                    if (isPrimary && primary == null) primary = id
                    if (canWrite && writable == null) writable = id
                    if (canWrite && isPrimary && primaryWritable == null) primaryWritable = id
                    if (name != null && display != null && display.equals(name, ignoreCase = true)) named = id
                }
                named ?: primaryWritable ?: writable ?: primary ?: any
            }
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** ISO local time (`2026-11-02T09:00`), or a plain date (midnight, or the day's end for an end). */
    private fun parseMoment(text: String, endOfDay: Boolean): Long? {
        val trimmed = text.trim()
        val zone = TimeZone.getDefault().toZoneId()
        return runCatching {
            java.time.LocalDateTime.parse(trimmed).atZone(zone).toInstant().toEpochMilli()
        }.recoverCatching {
            val date = java.time.LocalDate.parse(trimmed)
            val moment = if (endOfDay) date.plusDays(1).atStartOfDay() else date.atStartOfDay()
            moment.atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
    }
}
