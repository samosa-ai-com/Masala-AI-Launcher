package com.samosaai.masala

import android.content.Context
import android.provider.CalendarContract
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue

/** Environment probes shared by the instrumented tests. */
object Automation {
    /** Skips a test when the device has no calendar to write to (emulator images have none). */
    fun assumeCalendar(context: Context) {
        val hasWritable = runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                null, null, null,
            )?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
        val granted = runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, android.Manifest.permission.READ_CALENDAR)
        }.isSuccess
        assumeTrue("no calendar account on this device", hasWritable && granted)
    }
}
