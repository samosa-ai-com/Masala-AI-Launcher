package com.samosaai.masala.tools

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
}
