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
    fun create(context: Context): List<Tool> {
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
        )
    }
}
