package com.samosaai.masala.tools

import android.content.Context
import com.samosaai.masala.core.tool.Tool
import com.samosaai.masala.core.tool.ToolSensitivity.CHANGES_SOMETHING
import com.samosaai.masala.core.tool.ToolSensitivity.DESTRUCTIVE
import com.samosaai.masala.core.tool.ToolSensitivity.READ_ONLY
import com.samosaai.masala.tools.MasalaToolDefinitions as Defs

/**
 * The Play-safe tools `:app` registers (plan Phase 1, lane B): the private prototype's tool classes, each call wired
 * to its definition with the argument handling from the private prototype's ToolExecutor. Sensitivity decides the
 * confirmation gate; [Tool.readsPrivateData] and [Tool.usesNetwork] feed the Skill Hub policy.
 */
object MasalaTools {
    fun create(context: Context, files: ToolFiles): List<Tool> {
        val app = context.applicationContext
        val alarms = AlarmTool(app)
        val apps = AppsTool(app)
        val calendar = CalendarTool(app)
        val clipboard = ClipboardTool(app)
        val device = DeviceTool(app)
        val phone = PhoneTool(app)
        val system = SystemTool(app)
        val wallpaper = WallpaperTool(app)
        val webSearch = WebSearchTool()
        val webFetch = WebFetchTool()
        val capture = MediaCaptureTool(app, files)
        val health = HealthTool(app)
        val mediaEdit = MediaEditTool(app, files)
        val notifications = NotificationTool(app)
        val pdf = PdfTool(app, files)
        val transcribe = TranscribeTool(files)

        return listOf(
            FunctionTool(Defs.dialNumber, READ_ONLY) { phone.dialNumber(it.string("number") ?: return@FunctionTool missing("number")) },
            FunctionTool(Defs.openApp, READ_ONLY) {
                system.openApp(it.string("package_name") ?: return@FunctionTool missing("package_name"))
            },
            FunctionTool(Defs.setWallpaper, CHANGES_SOMETHING, usesNetwork = true) { wallpaper.setWallpaper(it.string("url")) },
            FunctionTool(Defs.listCalendarEvents, READ_ONLY, readsPrivateData = true) {
                calendar.listEvents(
                    daysAhead = it.int("days_ahead"),
                    fromDate = it.string("from_date"),
                    toDate = it.string("to_date"),
                    search = it.string("search"),
                )
            },
            FunctionTool(Defs.setAlarm, CHANGES_SOMETHING) {
                alarms.setAlarm(
                    hour = it.int("hour") ?: return@FunctionTool missing("hour"),
                    minute = it.int("minute") ?: return@FunctionTool missing("minute"),
                    message = it.string("message"),
                    days = it.stringList("days"),
                    vibrate = it.boolean("vibrate"),
                )
            },
            FunctionTool(Defs.setTimer, CHANGES_SOMETHING) {
                alarms.setTimer(
                    seconds = it.int("seconds") ?: 0,
                    message = it.string("message"),
                    hours = it.int("hours"),
                    minutes = it.int("minutes"),
                )
            },
            FunctionTool(Defs.showAlarms, READ_ONLY) { alarms.showAlarms() },
            FunctionTool(Defs.nextAlarm, READ_ONLY) { alarms.nextAlarm() },
            FunctionTool(Defs.snoozeAlarm, CHANGES_SOMETHING) { alarms.snoozeAlarm(minutes = it.int("minutes")) },
            FunctionTool(Defs.dismissTimer, CHANGES_SOMETHING) { alarms.dismissTimer() },
            FunctionTool(Defs.toggleTorch, CHANGES_SOMETHING) {
                device.toggleTorch(
                    on = it.boolean("on") ?: return@FunctionTool missing("on"),
                    durationSeconds = it.int("duration_seconds"),
                )
            },
            FunctionTool(Defs.setVolume, CHANGES_SOMETHING) {
                device.setVolume(
                    stream = it.string("stream") ?: return@FunctionTool missing("stream"),
                    percent = it.int("percent") ?: return@FunctionTool missing("percent"),
                    showUi = it.boolean("show_ui") ?: false,
                )
            },
            FunctionTool(Defs.getVolume, READ_ONLY) { device.getVolume(it.string("stream")) },
            FunctionTool(Defs.setRingerMode, CHANGES_SOMETHING) {
                device.setRingerMode(it.string("mode") ?: return@FunctionTool missing("mode"))
            },
            FunctionTool(Defs.vibrate, CHANGES_SOMETHING) {
                device.vibrate(
                    durationMs = it.int("duration_ms") ?: 500,
                    intensity = it.int("intensity") ?: 100,
                    pattern = it.string("pattern"),
                )
            },
            FunctionTool(Defs.setDnd, CHANGES_SOMETHING) { device.setDnd(it.boolean("enabled") ?: return@FunctionTool missing("enabled")) },
            FunctionTool(Defs.listInstalledApps, READ_ONLY, readsPrivateData = true) { apps.listInstalledApps(it.string("search")) },
            FunctionTool(Defs.uninstallApp, DESTRUCTIVE) {
                apps.uninstallApp(it.string("package_name") ?: return@FunctionTool missing("package_name"))
            },
            FunctionTool(Defs.getAppUsage, READ_ONLY, readsPrivateData = true) { apps.getAppUsage(it.int("days") ?: 7) },
            FunctionTool(Defs.getClipboard, READ_ONLY, readsPrivateData = true) { clipboard.getClipboard() },
            FunctionTool(Defs.setClipboard, CHANGES_SOMETHING) {
                clipboard.setClipboard(it.string("text") ?: return@FunctionTool missing("text"))
            },
            FunctionTool(Defs.webSearch, READ_ONLY, usesNetwork = true) {
                webSearch.search(
                    query = it.string("query") ?: return@FunctionTool missing("query"),
                    numResults = it.int("numResults") ?: 5,
                )
            },
            FunctionTool(Defs.webFetch, READ_ONLY, usesNetwork = true) {
                webFetch.fetch(url = it.string("url") ?: return@FunctionTool missing("url"), format = it.string("format"))
            },
            FunctionTool(Defs.getBatteryInfo, READ_ONLY) { system.getBatteryInfo() },
            FunctionTool(Defs.toggleWifi, CHANGES_SOMETHING) { system.toggleWifi(it.boolean("enabled") ?: return@FunctionTool missing("enabled")) },

            // Files, through ToolFiles: app storage, or anywhere once :pro adds all-files access.
            FunctionTool(Defs.pdfEdit, CHANGES_SOMETHING, readsPrivateData = true) {
                pdf.edit(
                    operation = it.string("operation") ?: return@FunctionTool missing("operation"),
                    input = it.string("input"),
                    inputs = it.stringList("inputs"),
                    output = it.string("output"),
                    pages = it.string("pages"),
                    degrees = it.int("degrees"),
                    password = it.string("password"),
                    overwrite = it.boolean("overwrite") ?: false,
                    confirmed = it.boolean("confirmed") ?: false,
                )
            },
            FunctionTool(Defs.mediaEdit, CHANGES_SOMETHING, readsPrivateData = true) {
                mediaEdit.edit(
                    operation = it.string("operation") ?: return@FunctionTool missing("operation"),
                    input = it.string("input"),
                    inputs = it.stringList("inputs"),
                    output = it.string("output"),
                    start = it.string("start"),
                    end = it.string("end"),
                    height = it.int("height"),
                    speed = it.double("speed"),
                    overwrite = it.boolean("overwrite") ?: false,
                )
            },
            // Changes nothing on the phone, but sends the audio to the speech endpoint.
            FunctionTool(Defs.transcribeFile, READ_ONLY, readsPrivateData = true, usesNetwork = true) {
                transcribe.transcribe(
                    path = it.string("path") ?: return@FunctionTool missing("path"),
                    model = it.string("model"),
                    language = it.string("language"),
                )
            },
            FunctionTool(Defs.startAudioRecording, CHANGES_SOMETHING) {
                capture.startAudioRecording(
                    source = it.string("source"),
                    maxDurationSeconds = it.int("max_duration_seconds"),
                    outputPath = it.string("output_path"),
                    quality = it.string("quality"),
                )
            },
            FunctionTool(Defs.stopAudioRecording, CHANGES_SOMETHING) { capture.stopAudioRecording() },
            FunctionTool(Defs.pauseAudioRecording, CHANGES_SOMETHING) { capture.pauseAudioRecording() },
            FunctionTool(Defs.resumeAudioRecording, CHANGES_SOMETHING) { capture.resumeAudioRecording() },
            FunctionTool(Defs.getAudioRecordingStatus, READ_ONLY) { capture.getAudioRecordingStatus() },

            // Notification access.
            FunctionTool(Defs.readNotifications, READ_ONLY, readsPrivateData = true) {
                notifications.readNotifications(limit = it.int("limit") ?: 15, app = it.string("app"))
            },
            FunctionTool(Defs.dismissNotifications, CHANGES_SOMETHING) { notifications.dismissNotifications(it.string("key")) },
            FunctionTool(Defs.mediaControl, CHANGES_SOMETHING) {
                notifications.mediaControl(
                    action = it.string("action") ?: return@FunctionTool missing("action"),
                    app = it.string("app"),
                    positionSeconds = it.int("position_seconds"),
                )
            },
            FunctionTool(Defs.getNowPlaying, READ_ONLY, readsPrivateData = true) { notifications.getNowPlaying() },

            // Health Connect: steps and sleep.
            FunctionTool(Defs.getHealthSummary, READ_ONLY, readsPrivateData = true) { health.getSummary(it.int("days")) },
            FunctionTool(Defs.getHealthRecords, READ_ONLY, readsPrivateData = true) {
                health.getRecords(type = it.string("type") ?: return@FunctionTool missing("type"), daysArg = it.int("days"))
            },
        )
    }
}
