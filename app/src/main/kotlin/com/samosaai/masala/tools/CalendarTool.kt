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
 * Device calendar: read, create, edit and delete through the provider. Needs `READ_CALENDAR` to
 * list and find a calendar, `WRITE_CALENDAR` for everything that changes one; both are asked for
 * on their own when missing. Recurrence uses an RRULE string the provider stores as-is.
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
        allDay: Boolean? = null,
        recurrence: String? = null,
        recurrenceCount: Int? = null,
        recurrenceUntil: String? = null,
        reminderMinutes: Int? = null,
        attendees: String? = null,
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
        val isAllDay = allDay ?: (start.length == 10)
        // All-day instants are midnight UTC with the UTC zone (what sync adapters expect); timed
        // instants are local with the phone's zone.
        val zoneId = if (isAllDay) java.time.ZoneOffset.UTC else TimeZone.getDefault().toZoneId()
        val startAt = parseMoment(start, endOfDay = false, zoneId) ?: return ActionResult.error(
            "Couldn't read the start time '$start'. Use 2026-11-02T09:00 or an all-day date 2026-11-02.",
        )
        val endAt = when {
            end != null -> parseMoment(end, endOfDay = end.length == 10, zoneId) ?: return ActionResult.error(
                "Couldn't read the end time '$end'.",
            )
            isAllDay -> startAt + 86_400_000L
            else -> startAt + 3_600_000L
        }
        if (endAt < startAt) return ActionResult.error("The event ends before it starts.")
        val (rule, ruleError) = rrule(recurrence, recurrenceCount, recurrenceUntil)
        if (ruleError != null) return ActionResult.error(ruleError)
        val (reminder, reminderError) = validatedReminder(reminderMinutes)
        if (reminderError != null) return ActionResult.error(reminderError)
        val mails = splitAttendees(attendees)
        val calendarId = calendarId(calendarName) ?: return ActionResult.error(
            "No writable calendar on this phone" + (calendarName?.let { " named '$it'" } ?: "") + ".",
        )
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, cleanTitle)
            put(CalendarContract.Events.DTSTART, startAt)
            if (rule != null) {
                // A repeating event carries its length as a duration, never an end instant.
                put(CalendarContract.Events.DURATION, durationOf(startAt, endAt))
            } else {
                put(CalendarContract.Events.DTEND, endAt)
            }
            put(CalendarContract.Events.ALL_DAY, if (isAllDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (isAllDay) "UTC" else TimeZone.getDefault().id)
            if (rule != null) put(CalendarContract.Events.RRULE, rule)
            location?.takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            notes?.takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }
        return try {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return ActionResult.error("The calendar app refused the event.")
            val id = uri.lastPathSegment?.toLongOrNull()
            if (id != null) {
                if (reminder != null) addReminder(id, reminder)
                mails.forEach { addAttendee(id, it) }
            }
            val whenText = readable.format(Date(startAt)) + if (isAllDay) " (all day)" else ""
            val extra = buildString {
                if (rule != null) append(", repeats ${recurrence!!.lowercase()}")
                if (reminder != null) append(", reminds ${reminder}m before")
                if (mails.isNotEmpty()) append(", with ${mails.size} attendee(s)")
            }
            ActionResult.ok("Added '$cleanTitle' on $whenText (event id $id)$extra.")
        } catch (e: SecurityException) {
            // The provider can still refuse (a read-only calendar, a work profile): report the
            // permission when that is the cause so the executor can ask, otherwise say what failed.
            writeRefused(e)
        } catch (e: IllegalArgumentException) {
            ActionResult.error("The calendar app rejected the event: ${e.message}")
        }
    }

    /**
     * Changes an event's fields; only the arguments passed are touched. Reminder and attendee
     * changes replace the whole set: pass the full list, nothing to leave them alone, or clear
     * them with [clearReminder] / an empty attendees string.
     */
    fun editEvent(
        eventId: Long,
        title: String? = null,
        start: String? = null,
        end: String? = null,
        location: String? = null,
        notes: String? = null,
        allDay: Boolean? = null,
        recurrence: String? = null,
        recurrenceCount: Int? = null,
        recurrenceUntil: String? = null,
        reminderMinutes: Int? = null,
        attendees: String? = null,
        clearRecurrence: Boolean = false,
        clearReminder: Boolean = false,
    ): ActionResult {
        needsWrite()?.let { return it }
        if (title != null && title.isBlank()) return ActionResult.error("The title can't be blank.")
        val (rule, ruleError) = if (recurrence != null || recurrenceCount != null || recurrenceUntil != null) {
            rrule(recurrence, recurrenceCount, recurrenceUntil)
        } else {
            null to null
        }
        if (ruleError != null) return ActionResult.error(ruleError)
        val (reminder, reminderError) = validatedReminder(reminderMinutes)
        if (reminderError != null) return ActionResult.error(reminderError)
        // Recurrence and all-day decisions need the stored row.
        val stored = readStored(eventId)
        if ((start != null || end != null || allDay != null || rule != null || clearRecurrence) && stored == null) {
            return ActionResult.error(
                "No event with id $eventId on this phone. It may have been deleted, or live in a profile the calendar can't see; list_calendar_events shows what is there.",
            )
        }
        val zoneId = TimeZone.getDefault().toZoneId()
        if (start != null && parseMoment(start, endOfDay = false, zoneId) == null) {
            return ActionResult.error("Couldn't read the start time '$start'. Use 2026-11-02T09:00 or a date like 2026-11-02.")
        }
        if (end != null && parseMoment(end, endOfDay = end.length == 10, zoneId) == null) {
            return ActionResult.error("Couldn't read the end time '$end'.")
        }
        if (start != null && end != null) {
            val s = parseMoment(start, endOfDay = false, zoneId)
            val e = parseMoment(end, endOfDay = end.length == 10, zoneId)
            if (s != null && e != null && e < s) return ActionResult.error("The event ends before it starts.")
        }
        val span = stored?.let {
            if (start != null || end != null || allDay != null || rule != null || clearRecurrence) {
                spanForEdit(it, start, end, allDay, rule, clearRecurrence, zoneId)
            } else null
        }
        val values = ContentValues().apply {
            title?.trim()?.let { put(CalendarContract.Events.TITLE, it) }
            if (span != null) {
                put(CalendarContract.Events.DTSTART, span.dtstart)
                if (span.duration != null) {
                    put(CalendarContract.Events.DURATION, span.duration)
                    putNull(CalendarContract.Events.DTEND)
                } else {
                    span.dtend?.let { put(CalendarContract.Events.DTEND, it) }
                }
                put(CalendarContract.Events.ALL_DAY, if (span.allDay) 1 else 0)
                put(CalendarContract.Events.EVENT_TIMEZONE, span.timezone)
            }
            location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            notes?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            if (rule != null) put(CalendarContract.Events.RRULE, rule)
            if (clearRecurrence) {
                putNull(CalendarContract.Events.RRULE)
                putNull(CalendarContract.Events.DURATION)
            }
        }
        if (values.size() == 0 && reminder == null && attendees == null && !clearReminder) {
            return ActionResult.error("Nothing to change: pass at least one new field.")
        }
        return try {
            val rows = if (values.size() > 0) {
                context.contentResolver.update(
                    CalendarContract.Events.CONTENT_URI, values,
                    "${CalendarContract.Events._ID} = ?", arrayOf(eventId.toString()),
                )
            } else 1
            if (rows == 0) {
                return ActionResult.error(
                    "No event with id $eventId on this phone. It may have been deleted, or live in a profile the calendar can't see; list_calendar_events shows what is there.",
                )
            }
            val changed = mutableListOf<String>()
            if (values.size() > 0) changed += "fields"
            if (clearReminder) {
                context.contentResolver.delete(
                    CalendarContract.Reminders.CONTENT_URI,
                    "${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()),
                )
                changed += "reminder (cleared)"
            } else if (reminder != null) {
                context.contentResolver.delete(
                    CalendarContract.Reminders.CONTENT_URI,
                    "${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()),
                )
                addReminder(eventId, reminder)
                changed += "reminder (${reminder}m)"
            }
            if (attendees != null) {
                context.contentResolver.delete(
                    CalendarContract.Attendees.CONTENT_URI,
                    "${CalendarContract.Attendees.EVENT_ID} = ?", arrayOf(eventId.toString()),
                )
                val mails = splitAttendees(attendees)
                mails.forEach { addAttendee(eventId, it) }
                changed += if (mails.isEmpty()) "attendees (cleared)" else "attendees (${mails.size})"
            }
            ActionResult.ok("Updated event $eventId (${changed.joinToString(", ")}).")
        } catch (e: SecurityException) {
            writeRefused(e)
        } catch (e: IllegalArgumentException) {
            ActionResult.error("The calendar app rejected the change: ${e.message}")
        }
    }

    /** Deletes one event. The gate asks first (DESTRUCTIVE); reminders and attendees go with it. */
    fun deleteEvent(eventId: Long): ActionResult {
        needsWrite()?.let { return it }
        return try {
            val rows = context.contentResolver.delete(
                CalendarContract.Events.CONTENT_URI,
                "${CalendarContract.Events._ID} = ?", arrayOf(eventId.toString()),
            )
            if (rows == 0) {
                ActionResult.error(
                    "No event with id $eventId on this phone. It may already be deleted; list_calendar_events shows what is there.",
                )
            } else {
                ActionResult.ok("Deleted event $eventId.")
            }
        } catch (e: SecurityException) {
            writeRefused(e)
        } catch (e: IllegalArgumentException) {
            ActionResult.error("The calendar app refused the delete: ${e.message}")
        }
    }

    /** The stored row pieces an edit reasons about. */
    internal data class StoredEvent(
        val startAt: Long,
        val endAt: Long?,
        val durationMillis: Long?,
        val allDay: Boolean,
        val rrule: String?,
    )

    private fun readStored(eventId: Long): StoredEvent? {
        return try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND,
                    CalendarContract.Events.DURATION,
                    CalendarContract.Events.ALL_DAY,
                    CalendarContract.Events.RRULE,
                ),
                "${CalendarContract.Events._ID} = ?",
                arrayOf(eventId.toString()),
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                fun longOrNull(i: Int) = if (cursor.isNull(i)) null else cursor.getLong(i)
                StoredEvent(
                    startAt = cursor.getLong(0),
                    endAt = longOrNull(1),
                    durationMillis = cursor.getString(2)?.let(::parseDuration),
                    allDay = cursor.getInt(3) == 1,
                    rrule = cursor.getString(4),
                )
            }
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Null when both grants are in, else the permission result to return. */
    private fun needsWrite(): ActionResult? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ActionResult.permissionNeeded(
                Manifest.permission.READ_CALENDAR,
                "The Calendar permission (read) is not granted.",
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
        return null
    }

    private fun writeRefused(e: SecurityException): ActionResult {
        val message = e.message.orEmpty()
        return when {
            message.contains("READ_CALENDAR") -> ActionResult.permissionNeeded(
                Manifest.permission.READ_CALENDAR, "Reading the calendar was refused.",
            )
            message.contains("WRITE_CALENDAR") -> ActionResult.permissionNeeded(
                Manifest.permission.WRITE_CALENDAR, "Writing to the calendar was refused.",
            )
            else -> ActionResult.error("The calendar app refused: $message")
        }
    }

    private fun addReminder(eventId: Long, minutes: Int) {
        context.contentResolver.insert(
            CalendarContract.Reminders.CONTENT_URI,
            ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, minutes)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_DEFAULT)
            },
        )
    }

    private fun addAttendee(eventId: Long, email: String) {
        context.contentResolver.insert(
            CalendarContract.Attendees.CONTENT_URI,
            ContentValues().apply {
                put(CalendarContract.Attendees.EVENT_ID, eventId)
                put(CalendarContract.Attendees.ATTENDEE_EMAIL, email)
                put(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.RELATIONSHIP_NONE)
                put(CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.TYPE_REQUIRED)
                put(CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_STATUS_NONE)
            },
        )
    }

    internal companion object {
        /** RFC 2445 length for a repeating event: whole days as PnD, anything else as PTnS. */
        fun durationOf(startAt: Long, endAt: Long): String {
            val seconds = ((endAt - startAt) / 1000).coerceAtLeast(0)
            return if (seconds % 86_400L == 0L) "P${seconds / 86_400L}D" else "PT${seconds}S"
        }

        /** RFC 2445 durations the provider writes ("PT1H", "P1D", "P1W", "PT30M") back to millis. */
        fun parseDuration(value: String): Long? = runCatching {
            val t = value.trim()
            if (!t.startsWith("P")) return null
            val (datePart, timePart) = t.substring(1).split("T").let {
                if (it.size == 2) it[0] to it[1] else it[0] to ""
            }
            var seconds = 0L
            val num = StringBuilder()
            for (c in datePart) {
                if (c.isDigit()) num.append(c)
                else {
                    val n = num.toString().toLongOrNull() ?: return null
                    seconds += when (c) {
                        'D' -> n * 86_400L
                        'W' -> n * 604_800L
                        else -> return null
                    }
                    num.clear()
                }
            }
            for (c in timePart) {
                if (c.isDigit()) num.append(c)
                else {
                    val n = num.toString().toLongOrNull() ?: return null
                    seconds += when (c) {
                        'H' -> n * 3_600L
                        'M' -> n * 60L
                        'S' -> n
                        else -> return null
                    }
                    num.clear()
                }
            }
            if (num.isNotEmpty()) return null
            seconds * 1000L
        }.getOrNull()

        /** A whole replacement span for an edit that touches when the event happens. */
        data class SpanResult(
            val dtstart: Long,
            val dtend: Long?,
            val duration: String?,
            val allDay: Boolean,
            val timezone: String,
        )

        /** The date half of "2026-11-02" or "2026-11-02T09:00", or null. */
        fun datePart(text: String): java.time.LocalDate? = runCatching {
            java.time.LocalDate.parse(text.trim().take(10))
        }.getOrNull()

        /**
         * Works out the event's new instants. Null means the stored row stays as it is (the caller
         * only reaches here when start, end, the all-day flag or the repeat changed).
         */
        fun spanForEdit(
            stored: StoredEvent,
            start: String?,
            end: String?,
            allDay: Boolean?,
            rule: String?,
            clearRecurrence: Boolean,
            zone: java.time.ZoneId,
        ): SpanResult {
            val utc = java.time.ZoneOffset.UTC
            val finalAllDay = allDay ?: stored.allDay
            val finalRule = rule ?: if (clearRecurrence) null else stored.rrule
            if (finalAllDay) {
                val storedDate = java.time.Instant.ofEpochMilli(stored.startAt)
                    .atZone(if (stored.allDay) utc else zone).toLocalDate()
                val sDate = start?.let(::datePart) ?: storedDate
                val eDate = end?.let(::datePart) ?: sDate
                val sUtc = sDate.atStartOfDay(utc).toInstant().toEpochMilli()
                return if (finalRule != null) {
                    val eUtc = eDate.plusDays(1).atStartOfDay(utc).toInstant().toEpochMilli()
                    SpanResult(sUtc, null, durationOf(sUtc, eUtc), true, "UTC")
                } else {
                    SpanResult(sUtc, eDate.plusDays(1).atStartOfDay(utc).toInstant().toEpochMilli(), null, true, "UTC")
                }
            }
            val storedTime = java.time.Instant.ofEpochMilli(stored.startAt).atZone(zone).toLocalTime()
            val sAt = start?.let {
                val d = datePart(it)
                if (d != null && it.trim().length == 10) {
                    // A bare date moves the day and keeps the time of day.
                    d.atTime(if (stored.allDay) java.time.LocalTime.MIDNIGHT else storedTime)
                        .atZone(zone).toInstant().toEpochMilli()
                } else {
                    parseMoment(it, endOfDay = false, zone)
                }
            } ?: stored.startAt
            val storedLen = stored.durationMillis
                ?: stored.endAt?.let { (it - stored.startAt).coerceAtLeast(0L) }
                ?: if (stored.allDay) 86_400_000L else 3_600_000L
            val eAt = end?.let { parseMoment(it, endOfDay = it.trim().length == 10, zone) }
                ?: (sAt + storedLen)
            return if (finalRule != null) {
                SpanResult(sAt, null, durationOf(sAt, eAt), false, zone.id)
            } else {
                SpanResult(sAt, eAt, null, false, zone.id)
            }
        }

        /** ISO local time (`2026-11-02T09:00`), or a plain date (midnight, or the day's end for an end). */
        fun parseMoment(text: String, endOfDay: Boolean, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long? {
            val trimmed = text.trim()
            return runCatching {
                java.time.LocalDateTime.parse(trimmed).atZone(zone).toInstant().toEpochMilli()
            }.recoverCatching {
                val date = java.time.LocalDate.parse(trimmed)
                val moment = if (endOfDay) date.plusDays(1).atStartOfDay() else date.atStartOfDay()
                moment.atZone(zone).toInstant().toEpochMilli()
            }.getOrNull()
        }

        fun rrule(repeat: String?, count: Int?, until: String?): Pair<String?, String?> {
            if (repeat == null && count == null && until == null) return null to null
            val freq = when (repeat?.trim()?.lowercase()) {
                null -> null
                "daily" -> "DAILY"
                "weekly" -> "WEEKLY"
                "monthly" -> "MONTHLY"
                "yearly" -> "YEARLY"
                else -> return null to "Recurrence must be daily, weekly, monthly or yearly, not '$repeat'."
            }
            if (freq == null) {
                if (count != null || until != null) {
                    return null to "A repeat count or end needs a recurrence too (daily, weekly, monthly, yearly)."
                }
                return null to null
            }
            val rule = buildString {
                append("FREQ=").append(freq)
                val n = count
                if (n != null) {
                    if (n < 1) return null to "The repeat count must be at least 1."
                    append(";COUNT=").append(n)
                }
                if (until != null) {
                    append(";UNTIL=").append(untilUtc(until) ?: return null to "Couldn't read the repeat end '$until'. Use a date like 2026-12-31.")
                }
            }
            return rule to null
        }

        /** UNTIL is always UTC (`…T000000Z`), whatever the phone's zone is. */
        fun untilUtc(until: String): String? {
            val trimmed = until.trim()
            val zone = TimeZone.getDefault().toZoneId()
            val instant = runCatching { java.time.LocalDateTime.parse(trimmed).atZone(zone).toInstant() }
                .recoverCatching {
                    java.time.LocalDate.parse(trimmed).plusDays(1).atStartOfDay().atZone(zone).toInstant()
                }.getOrNull() ?: return null
            return java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(java.time.ZoneOffset.UTC).format(instant)
        }

        /** Reminder minutes (null in means null out), or an error naming the bad value. */
        fun validatedReminder(minutes: Int?): Pair<Int?, String?> {
            if (minutes == null) return null to null
            if (minutes !in 0..40320) return null to "The reminder must be 0-40320 minutes before, not $minutes."
            return minutes to null
        }

        fun splitAttendees(raw: String?): List<String> =
            raw?.split(',', ';')?.map { it.trim() }?.filter { it.contains('@') } ?: emptyList()
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
}
