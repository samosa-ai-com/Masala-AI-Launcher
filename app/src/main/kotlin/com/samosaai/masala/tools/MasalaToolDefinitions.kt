package com.samosaai.masala.tools

import com.samosaai.masala.core.tool.ToolDefinition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * JSON schemas for the tools Masala takes from the private prototype (plan Phase 1, lane B), copied from the private prototype's
 * ToolDefinitions.kt. Descriptions that named the private prototype-only tools, its in-app alarms or health metrics
 * Masala doesn't read are reworded; set_timer lost its `system` flag (timers always go to the clock
 * app) and next_alarm is new.
 */
object MasalaToolDefinitions {

    private fun schema(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            put("type", "object")
            block()
        }

    private fun tool(name: String, description: String, parameters: JsonObject) = ToolDefinition(name, description, parameters)

    val dialNumber = tool(
        "dial_number",
        "Open the dialer pre-filled with a number WITHOUT calling — the user must tap call " +
            "themselves. Only use this when the user explicitly wants to review the number first " +
            "(e.g. 'open the dialer with…', 'call X'). Masala never places calls itself.",
        schema {
            putJsonObject("properties") {
                putJsonObject("number") {
                    put("type", "string")
                    put("description", "Phone number to dial, e.g. +49123456789")
                }
            }
            putJsonArray("required") { add("number") }
        }
    )

    val openApp = tool(
        "open_app",
        "Launch an installed app by its package name (e.g. com.android.settings) or by a human name " +
            "the executor will try to resolve.",
        schema {
            putJsonObject("properties") {
                putJsonObject("package_name") {
                    put("type", "string")
                    put("description", "Android package name, e.g. com.google.android.youtube")
                }
            }
            putJsonArray("required") { add("package_name") }
        }
    )

    val setWallpaper = tool(
        "set_wallpaper",
        "Set the phone's wallpaper from an image. Accepts a web link, a local file path " +
            "(/sdcard/Download/photo.jpg), a file:// path, or a content:// URI from a picker. The " +
            "image is fitted to the screen, not cropped, and the leftover edges are filled with a " +
            "colour taken from the image. Omit the source for a random image.",
        schema {
            putJsonObject("properties") {
                putJsonObject("url") {
                    put("type", "string")
                    put(
                        "description",
                        "Image source: https://…, /sdcard/…, file://… or content://…. Omit for a " +
                            "random wallpaper.",
                    )
                }
                putJsonObject("target") {
                    put("type", "string")
                    put(
                        "description",
                        "Which screens: 'home' (default), 'lock', or 'both'.",
                    )
                    putJsonArray("enum") {
                        add("home")
                        add("lock")
                        add("both")
                    }
                }
            }
        }
    )

    val listCalendarEvents = tool(
        "list_calendar_events",
        "List events from the phone's calendars in a date range, with optional title search.",
        schema {
            putJsonObject("properties") {
                putJsonObject("days_ahead") {
                    put("type", "integer")
                    put(
                        "description",
                        "Days ahead to include (1-365). Default 7 when from_date/to_date are unset."
                    )
                }
                putJsonObject("from_date") {
                    put("type", "string")
                    put("description", "Start date, e.g. '2026-01-01'. Overrides days_ahead.")
                }
                putJsonObject("to_date") {
                    put("type", "string")
                    put("description", "End date, e.g. '2026-01-31'. Defaults to one day after from_date.")
                }
                putJsonObject("search") {
                    put("type", "string")
                    put("description", "Filter events whose title contains this text (case-insensitive).")
                }
            }
        }
    )

    val createMiniApp = tool(
        "create_mini_app",
        "Save a self-contained HTML mini-app (single file: inline CSS and JS, no external " +
            "requests unless the user enabled network for it). Returns the new mini-app id and any " +
            "console errors from loading it once; tell the user to open it from home once pinned.",
        schema {
            putJsonObject("properties") {
                putJsonObject("title") {
                    put("type", "string")
                    put("description", "Short title shown in the host and on home.")
                }
                putJsonObject("html") {
                    put("type", "string")
                    put("description", "Complete HTML document, inline style and script only.")
                }
                putJsonObject("icon") {
                    put("type", "string")
                    put(
                        "description",
                        "Optional icon: a built-in name (e.g. timer, music_note, checklist) or one small " +
                            "inline SVG (<= 8 KB, viewBox, no scripts, no links).",
                    )
                }
            }
            putJsonArray("required") { add("html") }
        }
    )

    val updateMiniApp = tool(
        "update_mini_app",
        "Replace a mini-app's HTML, keeping its title and settings. Returns console errors from " +
            "loading the new version, and any the old one logged while the user had it open.",
        schema {
            putJsonObject("properties") {
                putJsonObject("id") {
                    put("type", "string")
                    put("description", "Mini-app id from create_mini_app.")
                }
                putJsonObject("html") {
                    put("type", "string")
                    put("description", "Complete replacement HTML document.")
                }
                putJsonObject("icon") {
                    put("type", "string")
                    put(
                        "description",
                        "Optional icon: a built-in name (e.g. timer, music_note, checklist) or one small " +
                            "inline SVG (<= 8 KB, viewBox, no scripts, no links). Missing leaves the stored one alone.",
                    )
                }
            }
            putJsonArray("required") {
                add("id")
                add("html")
            }
        }
    )

    val listMiniApps = tool(
        "list_mini_apps",
        "List the HTML mini-apps stored on this phone: id, title, last update, whether it may use " +
            "the internet, and whether it has an icon. To change one: read_mini_app, then update_mini_app.",
        schema { putJsonObject("properties") {} }
    )

    val readMiniApp = tool(
        "read_mini_app",
        "Read a stored mini-app's full HTML, title and settings, to change it with update_mini_app. " +
            "The user may name an app by its title: call list_mini_apps first to find the id.",
        schema {
            putJsonObject("properties") {
                putJsonObject("id") {
                    put("type", "string")
                    put("description", "Mini-app id from list_mini_apps.")
                }
            }
            putJsonArray("required") { add("id") }
        }
    )

    val openMiniAppTool = tool(
        "open_mini_app",
        "Open a stored mini-app on screen.",
        schema {
            putJsonObject("properties") {
                putJsonObject("id") {
                    put("type", "string")
                    put("description", "Mini-app id from list_mini_apps or create_mini_app.")
                }
            }
            putJsonArray("required") { add("id") }
        }
    )

    val pinMiniAppTool = tool(
        "pin_mini_app",
        "Put a stored mini-app on the home screen so the user can tap it. Use this after " +
            "creating an app if it isn't on home yet.",
        schema {
            putJsonObject("properties") {
                putJsonObject("id") {
                    put("type", "string")
                    put("description", "Mini-app id.")
                }
            }
            putJsonArray("required") { add("id") }
        }
    )

    val deleteMiniAppTool = tool(
        "delete_mini_app",
        "Delete a stored mini-app. Home items pointing at it stop working.",
        schema {
            putJsonObject("properties") {
                putJsonObject("id") {
                    put("type", "string")
                    put("description", "Mini-app id.")
                }
            }
            putJsonArray("required") { add("id") }
        }
    )

    // Generated home widgets (plan docs/plans/widget-maker.md §7.2).
    val listWidgets = tool(
        "list_widgets",
        "List the widget library: id, title, where each widget sits on home. " +
            "Call it directly for read-only questions (what exists, is one on home), no consult. " +
            "To change one: read_widget, then update_widget. To use a template as a start, read it too.",
        schema { putJsonObject("properties") {} },
    )

    val readWidget = tool(
        "read_widget",
        "Read one library widget's full spec JSON, to change it with update_widget. " +
            "The user may name a widget by its title: call list_widgets first to find the id.",
        schema {
            putJsonObject("properties") {
                putJsonObject("id") {
                    put("type", "string")
                    put("description", "Widget id from list_widgets.")
                }
            }
            putJsonArray("required") { add("id") }
        },
    )

    val widgetSchema = tool(
        "widget_schema",
        "How a widget spec is shaped: components, bindings, sources and limits. " +
            "Read this before writing a spec by hand; create_widget from a sentence skips it.",
        schema { putJsonObject("properties") {} },
    )

    val createWidget = tool(
        "create_widget",
        "Make a home-screen widget: either the full spec JSON, or one sentence and the maker " +
            "writes the spec (checked, one repair turn). If a similar widget already exists, the call " +
            "refuses and names it — offer to use, place or revise it instead of saving a duplicate. " +
            "The answer names the widget id and any " +
            "problems; a spec with problems is never saved. Nothing goes on home until place_widget, " +
            "which always asks the user first.",
        schema {
            putJsonObject("properties") {
                putJsonObject("spec") {
                    put("type", "string")
                    put("description", "Complete WidgetSpec JSON. One of spec or sentence.")
                }
                putJsonObject("sentence") {
                    put("type", "string")
                    put("description", "What the widget should be, e.g. 'a water counter with a goal of 8'. One of spec or sentence.")
                }
            }
        },
    )

    val updateWidget = tool(
        "update_widget",
        "Change a library widget: either the complete replacement spec JSON (same id), or a " +
            "sentence and the maker revises it (checked, one repair turn). The look applies at " +
            "once with Undo. Never moves it on home: place_widget does that.",
        schema {
            putJsonObject("properties") {
                putJsonObject("id") {
                    put("type", "string")
                    put("description", "Widget id from list_widgets.")
                }
                putJsonObject("spec") {
                    put("type", "string")
                    put("description", "Complete replacement WidgetSpec JSON with the same id. One of spec or sentence.")
                }
                putJsonObject("sentence") {
                    put("type", "string")
                    put("description", "What to change, e.g. 'make the goal 10'. One of spec or sentence.")
                }
            }
            putJsonArray("required") { add("id") }
        },
    )

    val placeWidget = tool(
        "place_widget",
        "Put a library widget on home. Always asks the user first, naming the widget. " +
            "The same widget may sit on two pages.",
        schema {
            putJsonObject("properties") {
                putJsonObject("id") {
                    put("type", "string")
                    put("description", "Widget id from list_widgets or create_widget.")
                }
                putJsonObject("page") {
                    put("type", "string")
                    put("description", "Page id for the widget. Missing puts it on the first page.")
                }
                putJsonObject("rows") {
                    put("type", "integer")
                    put("description", "Height override in rows, inside the widget's own size range. Missing keeps its default.")
                }
                putJsonObject("approved") {
                    put("type", "boolean")
                    put("description", "Set true after the user agreed to the widget's web fetching (the error names the hosts). Missing asks first.")
                }
            }
            putJsonArray("required") { add("id") }
        },
    )

    val removeWidget = tool(
        "remove_widget",
        "Take a widget off home wherever it sits, keeping it in the library. " +
            "Deleting it from the library instead is delete_widget.",
        schema {
            putJsonObject("properties") {
                putJsonObject("id") {
                    put("type", "string")
                    put("description", "Widget id from list_widgets.")
                }
            }
            putJsonArray("required") { add("id") }
        },
    )

    val deleteWidget = tool(
        "delete_widget",
        "Delete a widget from the library and take it off home everywhere. Lost for good.",
        schema {
            putJsonObject("properties") {
                putJsonObject("id") {
                    put("type", "string")
                    put("description", "Widget id from list_widgets.")
                }
            }
            putJsonArray("required") { add("id") }
        },
    )

    // Stream sources for live media widgets (plan docs/plans/widget-maker.md §7.2).
    val listStreamSources = tool(
        "list_stream_sources",
        "The cameras and streams the phone knows: name, kind and whether it plays. " +
            "A widget shows one by its source id; the URLs and their secrets never leave the phone.",
        schema { putJsonObject("properties") {} },
    )

    val addStreamSource = tool(
        "add_stream_source",
        "Add a camera or stream by name and URL (rtsp, hls, dash, progressive mp4, mjpeg or " +
            "snapshot jpeg). Never put a username or password in the URL or in chat: when one is " +
            "needed the user types it into a sheet on screen. The answer names the source id for " +
            "video and snapshot components.",
        schema {
            putJsonObject("properties") {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "Name shown in Settings, e.g. Front door.")
                }
                putJsonObject("url") {
                    put("type", "string")
                    put("description", "Stream or snapshot URL, without any username or password.")
                }
                putJsonObject("kind") {
                    put("type", "string")
                    put("description", "rtsp, hls, dash, progressive, mjpeg or snapshot. Guessed from the URL when missing.")
                }
                putJsonObject("username") {
                    put("type", "string")
                    put("description", "Camera login. The password is asked on screen, never in chat.")
                }
            }
            putJsonArray("required") {
                add("name")
                add("url")
            }
        },
    )

    val testStream = tool(
        "test_stream",
        "Connect to a stream source for up to 8 seconds and report the codec, the resolution and " +
            "the latency, or the error. Run it before placing a video or snapshot widget.",
        schema {
            putJsonObject("properties") {
                putJsonObject("id") {
                    put("type", "string")
                    put("description", "Source id from list_stream_sources.")
                }
            }
            putJsonArray("required") { add("id") }
        },
    )

    val createCalendarEvent = tool(
        "create_calendar_event",
        "Add an event to the phone's calendar. The answer names the event id for later edits.",
        schema {
            putJsonObject("properties") {
                putJsonObject("title") {
                    put("type", "string")
                    put("description", "Event title.")
                }
                putJsonObject("start") {
                    put("type", "string")
                    put(
                        "description",
                        "Start time, ISO local like 2026-11-02T09:00, or a date for an all-day event (2026-11-02).",
                    )
                }
                putJsonObject("end") {
                    put("type", "string")
                    put("description", "End time in the same form. Defaults to one hour after start.")
                }
                putJsonObject("location") {
                    put("type", "string")
                    put("description", "Optional place.")
                }
                putJsonObject("notes") {
                    put("type", "string")
                    put("description", "Optional description.")
                }
                putJsonObject("calendar") {
                    put("type", "string")
                    put("description", "Optional calendar name; defaults to the primary calendar.")
                }
                putJsonObject("all_day") {
                    put("type", "boolean")
                    put("description", "Treat as an all-day event even when start has a time. Default false.")
                }
                putJsonObject("recurrence") {
                    put("type", "string")
                    put("description", "Repeat: daily, weekly, monthly or yearly. Omit for a one-time event.")
                }
                putJsonObject("recurrence_count") {
                    put("type", "integer")
                    put("description", "Stop after this many occurrences. Omit to repeat without end.")
                }
                putJsonObject("recurrence_until") {
                    put("type", "string")
                    put("description", "Last repeat, as a date (2026-12-31). Omit to repeat without end.")
                }
                putJsonObject("reminder_minutes") {
                    put("type", "integer")
                    put("description", "Remind this many minutes before, e.g. 30. Omit for no reminder.")
                }
                putJsonObject("attendees") {
                    put("type", "string")
                    put("description", "Invitees as comma-separated email addresses. Omit for none.")
                }
            }
            putJsonArray("required") {
                add("title")
                add("start")
            }
        }
    )

    val editCalendarEvent = tool(
        "edit_calendar_event",
        "Change an event's fields; only the arguments passed are touched. Reminders and attendees " +
            "replace the whole set when passed. Find the event id with list_calendar_events first.",
        schema {
            putJsonObject("properties") {
                putJsonObject("event_id") {
                    put("type", "integer")
                    put("description", "The event's id, from list_calendar_events.")
                }
                putJsonObject("title") {
                    put("type", "string")
                    put("description", "New title.")
                }
                putJsonObject("start") {
                    put("type", "string")
                    put("description", "New start, ISO local like 2026-11-02T09:00.")
                }
                putJsonObject("end") {
                    put("type", "string")
                    put("description", "New end in the same form.")
                }
                putJsonObject("location") {
                    put("type", "string")
                    put("description", "New place.")
                }
                putJsonObject("notes") {
                    put("type", "string")
                    put("description", "New description.")
                }
                putJsonObject("all_day") {
                    put("type", "boolean")
                    put("description", "true for all-day, false for timed.")
                }
                putJsonObject("recurrence") {
                    put("type", "string")
                    put("description", "Repeat: daily, weekly, monthly or yearly.")
                }
                putJsonObject("recurrence_count") {
                    put("type", "integer")
                    put("description", "Stop after this many occurrences.")
                }
                putJsonObject("recurrence_until") {
                    put("type", "string")
                    put("description", "Last repeat, as a date (2026-12-31).")
                }
                putJsonObject("clear_recurrence") {
                    put("type", "boolean")
                    put("description", "true to make a repeating event one-time again.")
                }
                putJsonObject("clear_reminder") {
                    put("type", "boolean")
                    put("description", "true to remove the event's reminders.")
                }
                putJsonObject("reminder_minutes") {
                    put("type", "integer")
                    put("description", "Replace the reminder set with one this many minutes before.")
                }
                putJsonObject("attendees") {
                    put("type", "string")
                    put("description", "Replace the invitee set with these comma-separated emails (empty clears it).")
                }
            }
            putJsonArray("required") {
                add("event_id")
            }
        }
    )

    val deleteCalendarEvent = tool(
        "delete_calendar_event",
        "Delete one event by id. The user is asked first, and reminders and attendees go with it. " +
            "Find the id with list_calendar_events first.",
        schema {
            putJsonObject("properties") {
                putJsonObject("event_id") {
                    put("type", "integer")
                    put("description", "The event's id, from list_calendar_events.")
                }
            }
            putJsonArray("required") {
                add("event_id")
            }
        }
    )

    val scheduleAgentTask = tool(
        "schedule_agent_task",
        "Remind yourself of something later: Masala runs the prompt at the set time, even with the " +
            "app closed. The user approves every task, and one-time tasks must start in the future. " +
            "Alarms are inexact (a few minutes late at worst).",
        schema {
            putJsonObject("properties") {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "Short name, e.g. 'Water the plants'.")
                }
                putJsonObject("prompt") {
                    put("type", "string")
                    put("description", "What Masala should do when the time comes, e.g. 'Remind me to water the plants'.")
                }
                putJsonObject("start") {
                    put("type", "string")
                    put("description", "First run, local like 2026-11-02T08:00, or a date for midnight.")
                }
                putJsonObject("repeat") {
                    put("type", "string")
                    put("description", "none (default), hourly, daily, weekly or monthly.")
                }
                putJsonObject("interval") {
                    put("type", "integer")
                    put("description", "Every N hours/days for hourly/daily repeats. Default 1.")
                }
                putJsonObject("weekdays") {
                    put("type", "array")
                    put("description", "Weekly repeats only: e.g. ['mon','wed'] or ['weekdays'].")
                    putJsonObject("items") { put("type", "string") }
                }
                putJsonObject("skill_id") {
                    put("type", "string")
                    put("description", "Optional installed skill to run instead of a bare prompt.")
                }
                putJsonObject("memory_note") {
                    put("type", "string")
                    put("description", "Optional Second Brain note path this task belongs to.")
                }
            }
            putJsonArray("required") {
                add("name")
                add("start")
            }
        }
    )

    val listScheduledTasks = tool(
        "list_scheduled_tasks",
        "List the scheduled tasks with their ids, next runs and repeats.",
        schema { putJsonObject("properties") {} }
    )

    val editScheduledTask = tool(
        "edit_scheduled_task",
        "Change a scheduled task; only the arguments passed are touched. Find the id with " +
            "list_scheduled_tasks first.",
        schema {
            putJsonObject("properties") {
                putJsonObject("task_id") {
                    put("type", "string")
                    put("description", "The task's id.")
                }
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "New name.")
                }
                putJsonObject("prompt") {
                    put("type", "string")
                    put("description", "New prompt.")
                }
                putJsonObject("start") {
                    put("type", "string")
                    put("description", "New first run, local like 2026-11-02T08:00.")
                }
                putJsonObject("repeat") {
                    put("type", "string")
                    put("description", "New repeat: none, hourly, daily, weekly or monthly.")
                }
                putJsonObject("interval") {
                    put("type", "integer")
                    put("description", "New every-N for hourly/daily repeats.")
                }
                putJsonObject("weekdays") {
                    put("type", "array")
                    put("description", "New weekday set for weekly repeats.")
                    putJsonObject("items") { put("type", "string") }
                }
                putJsonObject("enabled") {
                    put("type", "boolean")
                    put("description", "false pauses the task, true resumes it.")
                }
                putJsonObject("skill_id") {
                    put("type", "string")
                    put("description", "New skill to run (empty clears it).")
                }
                putJsonObject("memory_note") {
                    put("type", "string")
                    put("description", "New note path (empty clears it).")
                }
            }
            putJsonArray("required") {
                add("task_id")
            }
        }
    )

    val deleteScheduledTask = tool(
        "delete_scheduled_task",
        "Delete a scheduled task by id; its future runs are cancelled. The user is asked first.",
        schema {
            putJsonObject("properties") {
                putJsonObject("task_id") {
                    put("type", "string")
                    put("description", "The task's id, from list_scheduled_tasks.")
                }
            }
            putJsonArray("required") {
                add("task_id")
            }
        }
    )

    val searchContacts = tool(
        "search_contacts",
        "Look up phone numbers and email addresses in the phone's contacts by name or number.",
        schema {
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Part of a name or a phone number. Empty lists the first few contacts.")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Maximum matches, default 10.")
                }
            }
        }
    )

    val addContact = tool(
        "add_contact",
        "Add a contact through the Contacts app: it opens prefilled and the user reviews and saves " +
            "it there, so nothing is added silently.",
        schema {
            putJsonObject("properties") {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "Full name for the new contact.")
                }
                putJsonObject("phone") {
                    put("type", "string")
                    put("description", "Optional phone number.")
                }
                putJsonObject("email") {
                    put("type", "string")
                    put("description", "Optional email address.")
                }
            }
            putJsonArray("required") {
                add("name")
            }
        }
    )

    val setAlarm = tool(
        "set_alarm",
        "Set an alarm in the system clock app. Android has no API to delete a scheduled alarm: to " +
            "remove or change one, open the clock app with show_alarms and tell the user to tap it there.",
        schema {
            putJsonObject("properties") {
                putJsonObject("hour") {
                    put("type", "integer")
                    put("description", "Hour in 24h format (0-23).")
                }
                putJsonObject("minute") {
                    put("type", "integer")
                    put("description", "Minute (0-59).")
                }
                putJsonObject("message") {
                    put("type", "string")
                    put("description", "Optional alarm label.")
                }
                putJsonObject("days") {
                    put("type", "array")
                    put(
                        "description",
                        "Repeating days: e.g. ['mon','wed','fri'] or ['weekdays']. Omit for one-time alarm."
                    )
                    putJsonObject("items") { put("type", "string") }
                }
                putJsonObject("vibrate") {
                    put("type", "boolean")
                    put("description", "Whether the alarm should vibrate. Default true.")
                }
            }
            putJsonArray("required") {
                add("hour")
                add("minute")
            }
        }
    )

    val setTimer = tool(
        "set_timer",
        "Start a countdown timer in the system clock app. The user stops it there.",
        schema {
            putJsonObject("properties") {
                putJsonObject("seconds") {
                    put("type", "integer")
                    put("description", "Seconds; combine with minutes/hours for longer durations.")
                }
                putJsonObject("minutes") {
                    put("type", "integer")
                    put("description", "Additional minutes (e.g. 5 for '5 minutes').")
                }
                putJsonObject("hours") {
                    put("type", "integer")
                    put("description", "Additional hours.")
                }
                putJsonObject("message") {
                    put("type", "string")
                    put("description", "Optional timer label.")
                }
            }
            putJsonArray("required") { add("seconds") }
        }
    )

    val showAlarms = tool(
        "show_alarms",
        "Open the system clock app's alarms list so the user can see, change or delete alarms.",
        schema { putJsonObject("properties") {} }
    )

    val snoozeAlarm = tool(
        "snooze_alarm",
        "Snooze the currently ringing alarm. Only has an effect if an alarm is actively ringing " +
            "in the clock app right now.",
        schema {
            putJsonObject("properties") {
                putJsonObject("minutes") {
                    put("type", "integer")
                    put("description", "Snooze duration in minutes. Omit to use the clock app's default.")
                }
            }
        }
    )

    val openUrl = tool(
        "open_url",
        "Open a web link in the phone's browser.",
        schema {
            putJsonObject("properties") {
                putJsonObject("url") {
                    put("type", "string")
                    put("description", "Full http:// or https:// URL.")
                }
            }
            putJsonArray("required") { add("url") }
        }
    )

    val toggleTorch = tool(
        "toggle_torch",
        "Turn the phone's flashlight on or off. Pass on=true or on=false to set it; omit 'on' to " +
            "flip whatever it is doing now. Use this for 'turn the torch on', 'turn it off' or " +
            "'toggle the flashlight'.",
        schema {
            putJsonObject("properties") {
                putJsonObject("on") {
                    put("type", "boolean")
                    put(
                        "description",
                        "true to turn the flashlight on, false to turn it off. Omit to flip the " +
                            "current state, which is what a plain 'toggle' means.",
                    )
                }
                putJsonObject("duration_seconds") {
                    put("type", "integer")
                    put("description", "Optional: switch it off again after this many seconds (1-300).")
                }
            }
        }
    )

    val setVolume = tool(
        "set_volume",
        "Set a volume stream to a percentage, reporting the previous and new levels.",
        schema {
            putJsonObject("properties") {
                putJsonObject("stream") {
                    put("type", "string")
                    put("description", "Which stream: media, ring, alarm, notification, or call.")
                }
                putJsonObject("percent") {
                    put("type", "integer")
                    put("description", "Target volume 0-100 (0 = mute, 100 = max).")
                }
                putJsonObject("show_ui") {
                    put("type", "boolean")
                    put("description", "Show the volume slider briefly. Default false.")
                }
            }
            putJsonArray("required") {
                add("stream")
                add("percent")
            }
        }
    )

    val getVolume = tool(
        "get_volume",
        "Read a stream's current volume percentage, or every stream's if none is given.",
        schema {
            putJsonObject("properties") {
                putJsonObject("stream") {
                    put("type", "string")
                    put(
                        "description",
                        "Which stream to read: media, ring, alarm, notification, call. If omitted, all streams are reported."
                    )
                }
            }
        }
    )

    val setRingerMode = tool(
        "set_ringer_mode",
        "Set the ringer mode: normal, vibrate, or silent. Silent/vibrate need Do Not Disturb access.",
        schema {
            putJsonObject("properties") {
                putJsonObject("mode") {
                    put("type", "string")
                    put("description", "One of: normal, vibrate, silent.")
                }
            }
            putJsonArray("required") { add("mode") }
        }
    )

    val vibrate = tool(
        "vibrate",
        "Vibrate the device. Supports custom intensity and predefined patterns. " +
            "Use for haptic feedback, notifications, alerts, or attention-getting.",
        schema {
            putJsonObject("properties") {
                putJsonObject("duration_ms") {
                    put("type", "integer")
                    put(
                        "description",
                        "Vibration length in milliseconds (1-5000). Default 500. Ignored when pattern is set."
                    )
                }
                putJsonObject("intensity") {
                    put("type", "integer")
                    put("description", "Vibration strength 0-100 (0 = none, 100 = max). Default 100.")
                }
                putJsonObject("pattern") {
                    put("type", "string")
                    put(
                        "description",
                        "Predefined pattern: short (100ms), long (1s), double (two quick buzzes), sos (...---...). Overrides duration_ms."
                    )
                }
            }
        }
    )

    val setDnd = tool(
        "set_dnd",
        "Turn Do Not Disturb on or off. Needs Do Not Disturb (notification-policy) access.",
        schema {
            putJsonObject("properties") {
                putJsonObject("enabled") {
                    put("type", "boolean")
                    put("description", "true to enable Do Not Disturb, false to disable.")
                }
            }
            putJsonArray("required") { add("enabled") }
        }
    )

    val listInstalledApps = tool(
        "list_installed_apps",
        "List installed apps optionally filtered by name or package. Returns a summary of " +
            "how many apps are installed and, when a search is provided, the matching apps. " +
            "Use this before uninstall_app to find the exact package name.",
        schema {
            putJsonObject("properties") {
                putJsonObject("search") {
                    put("type", "string")
                    put("description", "Optional: filter to apps whose name or package contains this text.")
                }
            }
        }
    )

    val uninstallApp = tool(
        "uninstall_app",
        "Uninstall an app by its name or package name. Accepts both exact package names " +
            "(e.g. com.example.app) and human-readable app names (e.g. YouTube). " +
            "When a name is given, the tool resolves it to the matching package automatically. " +
            "The user will be asked to confirm before the uninstall proceeds.",
        schema {
            putJsonObject("properties") {
                putJsonObject("package_name") {
                    put("type", "string")
                    put("description", "App name or package name to uninstall, e.g. com.example.app or YouTube.")
                }
            }
            putJsonArray("required") { add("package_name") }
        }
    )

    val getAppUsage = tool(
        "get_app_usage",
        "Report per-app screen time (top apps) over a recent window. Needs Usage-access special access.",
        schema {
            putJsonObject("properties") {
                putJsonObject("days") {
                    put("type", "integer")
                    put("description", "Look-back window in days (1-90). Default 7.")
                }
            }
        }
    )

    val getClipboard = tool(
        "get_clipboard",
        "Read the current text on the clipboard.",
        schema { putJsonObject("properties") {} }
    )

    val setClipboard = tool(
        "set_clipboard",
        "Copy text onto the clipboard.",
        schema {
            putJsonObject("properties") {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "Text to place on the clipboard.")
                }
            }
            putJsonArray("required") { add("text") }
        }
    )

    val webSearch = tool(
        "websearch",
        "Search the web (DuckDuckGo), returning up to 10 titles, URLs and snippets. " +
            "For a result's full content, follow up with webfetch on its URL.",
        schema {
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Search query (natural language or keywords).")
                }
                putJsonObject("numResults") {
                    put("type", "integer")
                    put("description", "Number of results to return (1-10). Default 5.")
                }
            }
            putJsonArray("required") { add("query") }
        }
    )

    val webFetch = tool(
        "webfetch",
        "Fetch content from an HTTP or HTTPS URL and return it as text or Markdown. " +
            "Use this to look up documentation, read articles, or gather information from the web. " +
            "Only text responses (HTML, JSON, XML) are supported; binary content is rejected.",
        schema {
            putJsonObject("properties") {
                putJsonObject("url") {
                    put("type", "string")
                    put("description", "HTTP or HTTPS URL to fetch.")
                }
                putJsonObject("format") {
                    put("type", "string")
                    put(
                        "description",
                        "Output format: 'text' (default, page text) or 'markdown' (headings, links, lists, code kept as Markdown)."
                    )
                }
            }
            putJsonArray("required") { add("url") }
        }
    )

    val weather = tool(
        "weather",
        "Today's weather: conditions, temperature now, the high and low in °C, the chance of rain, " +
            "and the coming hours. " +
            "Without a place it is for the place the user set in Settings → Daily brief.",
        schema {
            putJsonObject("properties") {
                putJsonObject("place") {
                    put("type", "string")
                    put("description", "Optional city or town name, for weather somewhere else.")
                }
            }
        }
    )

    val getBatteryInfo = tool(
        "get_battery_info",
        "Report battery percentage and charging state.",
        schema { putJsonObject("properties") {} }
    )

    val toggleWifi = tool(
        "toggle_wifi",
        "Turn Wi-Fi on or off. Android 10 and newer forbid apps from toggling the radio, so on " +
            "any current device this opens the Wi-Fi panel for the user to flip instead — say so " +
            "rather than claiming the radio changed. Direct toggling only works below Android 10. " +
            "For any other settings screen, use open_setting.",
        schema {
            putJsonObject("properties") {
                putJsonObject("enabled") {
                    put("type", "boolean")
                    put("description", "true to turn Wi-Fi on, false to turn it off")
                }
            }
            putJsonArray("required") { add("enabled") }
        }
    )

    val takePhoto = tool(
        "take_photo",
        "Take a photo through the camera app: it opens, the user takes the picture, and the file " +
            "lands in Masala's work folder. Needs no permission, because the camera app (not Masala) " +
            "takes the picture. The answer names the file; read it with read_file on a LATER turn — " +
            "never chain a read in the same turn, the user hasn't taken the photo yet and " +
            "cancelling leaves no file. If they cancel, say what to rephrase and move on.",
        schema { putJsonObject("properties") {} }
    )

    val getStorageInfo = tool(
        "get_storage_info",
        "Report free and total space: internal storage, plus the app's folder on shared storage.",
        schema { putJsonObject("properties") {} }
    )

    val openSetting = tool(
        "open_setting",
        "Open a system settings screen by name — wifi, internet, volume, nfc, bluetooth, location, " +
            "notifications, battery, display, sound, apps, storage, language, date, airplane or " +
            "accessibility. Nothing is toggled by the tool itself; the user flips the switch on the " +
            "screen that opens.",
        schema {
            putJsonObject("properties") {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "Which settings screen to open, e.g. 'wifi' or 'location'.")
                }
            }
            putJsonArray("required") { add("name") }
        }
    )

    val getLocation = tool(
        "get_location",
        "The phone's coarse location from its last known fix, rounded to about a kilometre. Needs " +
            "the Location permission; asks for it when missing. Says plainly when there is no fix yet.",
        schema { putJsonObject("properties") {} }
    )

    val shareFile = tool(
        "share_file",
        "Send a file to the share sheet: the user picks where it goes (a chat, Drive, an email " +
            "draft). Nothing leaves the phone without that tap. Shares anything the file tools can " +
            "read: transcriptions, edited media, PDFs, skill outputs.",
        schema {
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "File path to share.")
                }
            }
            putJsonArray("required") {
                add("path")
            }
        }
    )

    val pdfEdit = tool(
        "pdf_edit",
        "Edit a PDF's page structure on-device: merge several PDFs into one, split one into " +
            "single-page files, extract a page range into a new PDF, delete pages, or rotate pages. " +
            "Also reports page count and page size with operation='info' — call that first when you " +
            "need to know how many pages a file has before choosing a range. Every operation writes a " +
            "NEW file and never modifies the input unless you point 'output' at the same path with " +
            "overwrite=true. It CANNOT edit the text or images inside a page: PDF stores glyphs at " +
            "fixed coordinates, so there is nothing to reflow — to read a PDF's text use read_file " +
            "instead, and tell the user plainly that rewriting page content is not possible. Editing a " +
            "password-protected PDF strips the protection from the copy, so it is refused until you have " +
            "warned the user and pass confirmed=true.",
        schema {
            putJsonObject("properties") {
                putJsonObject("operation") {
                    put("type", "string")
                    putJsonArray("enum") { PdfTool.OPERATIONS.forEach { add(it) } }
                    put(
                        "description",
                        "info = page count and size; merge = join 'inputs' in order; split = one file per " +
                            "page; extract_pages = keep 'pages'; delete_pages = drop 'pages'; " +
                            "rotate_pages = turn 'pages' by 'degrees'."
                    )
                }
                putJsonObject("input") {
                    put("type", "string")
                    put(
                        "description",
                        "Path of the source PDF. Required by every operation except merge, e.g. " +
                            "'/sdcard/Download/statement.pdf'."
                    )
                }
                putJsonObject("inputs") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put(
                        "description",
                        "merge only: two or more PDF paths, in the order they should be joined."
                    )
                }
                putJsonObject("output") {
                    put("type", "string")
                    put(
                        "description",
                        "Path of the PDF to write. Required by every operation except info and split; " +
                            "for split it is the output DIRECTORY and defaults to the input's folder."
                    )
                }
                putJsonObject("pages") {
                    put("type", "string")
                    put(
                        "description",
                        "1-based page selection: '3', '1-3,7', '9-' (9 to the end) or 'all'. Required by " +
                            "extract_pages and delete_pages; optional for rotate_pages (defaults to all)."
                    )
                }
                putJsonObject("degrees") {
                    put("type", "integer")
                    putJsonArray("enum") {
                        add(90)
                        add(180)
                        add(270)
                    }
                    put("description", "rotate_pages only: clockwise rotation to add, 90, 180 or 270.")
                }
                putJsonObject("password") {
                    put("type", "string")
                    put(
                        "description",
                        "Password for an encrypted PDF. Ask the user for it — never guess. The output is " +
                            "written unencrypted."
                    )
                }
                putJsonObject("confirmed") {
                    put("type", "boolean")
                    put(
                        "description",
                        "Set to true ONLY after telling the user that editing their password-protected PDF " +
                            "writes an unprotected copy, and they agreed. Editing an encrypted PDF fails " +
                            "without it. Never set it pre-emptively."
                    )
                }
                putJsonObject("overwrite") {
                    put("type", "boolean")
                    put(
                        "description",
                        "Allow replacing an existing output file. Default false, which fails instead of " +
                            "destroying a file the user may still need."
                    )
                }
            }
            putJsonArray("required") { add("operation") }
        }
    )

    val mediaEdit = tool(
        "media_edit",
        "Edit an audio or video file on-device: trim a window out of it, pull its audio track into a " +
            "separate file, strip the audio from a video, shrink it, change its playback speed, or join " +
            "several files end to end. Also reports duration, resolution, tracks and codecs with " +
            "operation='info' — call that FIRST whenever you need a timecode or a resolution, so you are " +
            "working against real numbers rather than a guess. Works the same on video and on audio-only " +
            "files. Every operation writes a NEW file and never modifies the input; the output cannot be the " +
            "input, because the encoder writes it while still reading from it. trim, extract_audio and " +
            "remove_audio copy the streams rather than re-encoding, so they are fast and lossless; compress, " +
            "speed and concat re-encode, so they take much longer and lose a little quality. It cannot add " +
            "music, overlay text or a watermark, blur or remove anything inside a frame, or open " +
            "DRM-protected media at all — say so plainly instead of attempting a workaround.",
        schema {
            putJsonObject("properties") {
                putJsonObject("operation") {
                    put("type", "string")
                    putJsonArray("enum") { MediaEditTool.OPERATIONS.forEach { add(it) } }
                    put(
                        "description",
                        "info = duration, tracks, resolution and codecs; trim = keep the window between " +
                            "'start' and 'end'; extract_audio = write the audio track as its own file; " +
                            "remove_audio = write the video with its audio dropped; compress = re-encode " +
                            "smaller at 'height'; speed = re-time by 'speed'; concat = join 'inputs' in order."
                    )
                }
                putJsonObject("input") {
                    put("type", "string")
                    put(
                        "description",
                        "Path of the source audio or video file, e.g. '/sdcard/DCIM/Camera/VID_0001.mp4'. " +
                            "Required by every operation except concat, which takes 'inputs' instead."
                    )
                }
                putJsonObject("output") {
                    put("type", "string")
                    put(
                        "description",
                        "Path of the file to write. Required by every operation except info. Must end in .mp4 " +
                            "(video) or .m4a (audio only) — the muxer always writes an MP4 container. Cannot be " +
                            "the same path as 'input'."
                    )
                }
                putJsonObject("start") {
                    put("type", "string")
                    put(
                        "description",
                        "trim only: where the kept window begins. Timecodes are '1:23' (m:ss), '1:02:03' " +
                            "(h:mm:ss), '90s', '1m30s' or plain seconds like '12.5'. Defaults to the start of " +
                            "the file."
                    )
                }
                putJsonObject("end") {
                    put("type", "string")
                    put(
                        "description",
                        "trim only: where the kept window ends, in the same timecode formats, or 'end' for the " +
                            "end of the file. Defaults to the end of the file. Give at least one of 'start' " +
                            "and 'end'."
                    )
                }
                putJsonObject("inputs") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put(
                        "description",
                        "concat only: two or more media paths, in the order they should be joined. They must " +
                            "all be video or all be audio-only."
                    )
                }
                putJsonObject("height") {
                    put("type", "integer")
                    put(
                        "description",
                        "compress only: target height in pixels, e.g. 720 or 480. Defaults to 720. Width " +
                            "follows the original aspect ratio. Compressing something already this small is " +
                            "refused."
                    )
                }
                putJsonObject("speed") {
                    put("type", "number")
                    put(
                        "description",
                        "speed only: playback multiplier between 0.25 and 4.0. 2.0 plays twice as fast and " +
                            "halves the duration; 0.5 plays at half speed. Audio and video are re-timed " +
                            "together."
                    )
                }
                putJsonObject("overwrite") {
                    put("type", "boolean")
                    put(
                        "description",
                        "Allow replacing an existing output file. Default false, which fails instead of " +
                            "destroying a file the user may still need."
                    )
                }
            }
            putJsonArray("required") { add("operation") }
        }
    )

    val transcribeFile = tool(
        "transcribe_file",
        "Transcribe an audio file already on disk to text, using the configured Speech-to-Text API. The " +
            "file is read and uploaded to the STT endpoint but never modified or deleted. Accepts .m4a, " +
            ".mp3, .wav, .ogg, .opus, .flac, .aac, .mp4 and .webm up to 25MB — trim or split longer " +
            "recordings with media_edit first. This is how a saved voice memo becomes text: record with " +
            "start_audio_recording using an explicit output_path (without one the recording lands behind a " +
            "MediaStore URI this tool cannot reach), stop it, then transcribe the file. Needs an API-based " +
            "STT provider (Samosa AI or External API) in Settings → Speech — Android's SpeechRecognizer " +
            "only listens to the microphone.",
        schema {
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put(
                        "description",
                        "Path of the audio file to transcribe, e.g. 'recordings/memo.m4a'."
                    )
                }
                putJsonObject("model") {
                    put("type", "string")
                    put(
                        "description",
                        "STT model id to use. Defaults to the model configured in Settings → Speech, then " +
                            "to the first STT model the API advertises."
                    )
                }
                putJsonObject("language") {
                    put("type", "string")
                    put(
                        "description",
                        "ISO language hint for the recording, e.g. 'en' or 'hi'. Defaults to the " +
                            "configured STT language; leave unset to let the model detect it."
                    )
                }
            }
            putJsonArray("required") { add("path") }
        }
    )

    val startAudioRecording = tool(
        "start_audio_recording",
        "Start recording audio from the microphone to a file. Supports configurable source, " +
            "quality, max duration, and custom output path. Call stop_audio_recording to finish. " +
            "Needs the Microphone permission.",
        schema {
            putJsonObject("properties") {
                putJsonObject("source") {
                    put("type", "string")
                    put(
                        "description",
                        "Audio source: 'mic' (default), 'voice' (optimized for speech), or 'camcorder' (wider range)."
                    )
                }
                putJsonObject("max_duration_seconds") {
                    put("type", "integer")
                    put("description", "Auto-stop after this many seconds. 0 or omit for no limit.")
                }
                putJsonObject("output_path") {
                    put("type", "string")
                    put(
                        "description",
                        "Custom file path to save the recording. Defaults to Recordings/recording_{timestamp}.m4a."
                    )
                }
                putJsonObject("quality") {
                    put("type", "string")
                    put(
                        "description",
                        "Recording quality: 'low' (16kHz, 16kbps), 'medium' (44.1kHz, 64kbps), " +
                            "or 'high' (44.1kHz, 192kbps). Default medium."
                    )
                }
            }
        }
    )

    val stopAudioRecording = tool(
        "stop_audio_recording",
        "Stop the in-progress audio recording and report the saved file path and duration.",
        schema { putJsonObject("properties") {} }
    )

    val getAudioRecordingStatus = tool(
        "get_audio_recording_status",
        "Check whether a recording is active, and if so report its duration and file path.",
        schema { putJsonObject("properties") {} }
    )

    val pauseAudioRecording = tool(
        "pause_audio_recording",
        "Pause the in-progress recording without stopping it. Resume with resume_audio_recording. " +
            "Requires Android 7.0+.",
        schema { putJsonObject("properties") {} }
    )

    val resumeAudioRecording = tool(
        "resume_audio_recording",
        "Resume a paused recording. Only works if pause_audio_recording was called first. " +
            "Requires Android 7.0+.",
        schema { putJsonObject("properties") {} }
    )

    val readNotifications = tool(
        "read_notifications",
        "Read the currently active notifications from all apps (app, time, title/text/expanded " +
            "text, and a dismiss key). Needs Notification access.",
        schema {
            putJsonObject("properties") {
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "How many recent notifications to return (1-50). Default 15.")
                }
                putJsonObject("app") {
                    put("type", "string")
                    put(
                        "description",
                        "Optional filter: only notifications from this app (matches package name " +
                            "or app label, e.g. 'gmail' or 'com.google.android.gm')."
                    )
                }
            }
        }
    )

    val dismissNotifications = tool(
        "dismiss_notifications",
        "Dismiss a notification by its key (from read_notifications), or all notifications if no " +
            "key is given. Needs Notification access.",
        schema {
            putJsonObject("properties") {
                putJsonObject("key") {
                    put("type", "string")
                    put("description", "The notification key to dismiss. Omit to dismiss all.")
                }
            }
        }
    )

    val mediaControl = tool(
        "media_control",
        "Control a media session in whichever app is playing — Spotify, YouTube Music, a " +
            "podcast player, anything with a media notification. Works without any per-service " +
            "API or account. When several apps hold a session, the one actually playing is " +
            "chosen; pass 'app' to target a specific one. Needs Notification access. " +
            "Use get_now_playing first if you need to know what is playing. This does NOT change " +
            "the volume: use set_volume with stream 'music' for that.",
        schema {
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    put(
                        "description",
                        "One of: play, pause, toggle, next, previous, stop, seek, fast_forward, rewind."
                    )
                }
                putJsonObject("app") {
                    put("type", "string")
                    put(
                        "description",
                        "Optional: target this app specifically (matches package name or app " +
                            "label, e.g. 'spotify'). Omit to use whichever app is playing."
                    )
                }
                putJsonObject("position_seconds") {
                    put("type", "integer")
                    put("description", "Required for action='seek': position to jump to, in seconds.")
                }
            }
            putJsonArray("required") { add("action") }
        }
    )

    val getNowPlaying = tool(
        "get_now_playing",
        "Report what each app with an active media session is playing: app, state, title, " +
            "artist, album, position/duration. Also gives the 'app' value for media_control.",
        schema { putJsonObject("properties") {} }
    )

    val getHealthSummary = tool(
        "get_health_summary",
        "Summarise the user's health and fitness data from Health Connect over the last N days: " +
            "steps and sleep. " +
            "Reads on-device data only — nothing is uploaded and nothing is ever written. Metrics " +
            "with no data are omitted rather than reported as zero.",
        schema {
            putJsonObject("properties") {
                putJsonObject("days") {
                    put("type", "integer")
                    put("description", "How many days back to summarise (1-365). Default 7.")
                }
            }
        }
    )

    val getHealthRecords = tool(
        "get_health_records",
        "List individual Health Connect records of one type, for questions the summary cannot " +
            "answer (e.g. 'which days did I walk over 10000 steps?', 'which nights did I sleep under 6 hours?'). " +
            "Read-only.",
        schema {
            putJsonObject("properties") {
                putJsonObject("type") {
                    put("type", "string")
                    put(
                        "description",
                        "Record type: " + HealthTool.RECORD_TYPE_NAMES.joinToString(", ") + "."
                    )
                }
                putJsonObject("days") {
                    put("type", "integer")
                    put("description", "How many days back to read (1-365). Default 7.")
                }
            }
            putJsonArray("required") { add("type") }
        }
    )

    val nextAlarm = tool(
        "next_alarm",
        "Tell when the next alarm on the phone rings, from any app. The clock app's full list isn't " +
            "readable; use show_alarms to open it.",
        schema { putJsonObject("properties") {} }
    )

    val all: List<ToolDefinition> = listOf(
        dialNumber,
        openApp,
        setWallpaper,
        listCalendarEvents,
        createCalendarEvent,
        searchContacts,
        openUrl,
        setAlarm,
        setTimer,
        showAlarms,
        snoozeAlarm,
        toggleTorch,
        setVolume,
        getVolume,
        setRingerMode,
        vibrate,
        setDnd,
        listInstalledApps,
        uninstallApp,
        getAppUsage,
        getClipboard,
        setClipboard,
        webSearch,
        webFetch,
        weather,
        getBatteryInfo,
        toggleWifi,
        nextAlarm,
        pdfEdit,
        mediaEdit,
        transcribeFile,
        startAudioRecording,
        stopAudioRecording,
        getAudioRecordingStatus,
        pauseAudioRecording,
        resumeAudioRecording,
        readNotifications,
        dismissNotifications,
        mediaControl,
        getNowPlaying,
        getHealthSummary,
        getHealthRecords,
        createMiniApp,
        updateMiniApp,
        listMiniApps,
        readMiniApp,
        openMiniAppTool,
        pinMiniAppTool,
        deleteMiniAppTool,
        listWidgets,
        readWidget,
        widgetSchema,
        createWidget,
        updateWidget,
        placeWidget,
        removeWidget,
        deleteWidget,
        listStreamSources,
        addStreamSource,
        testStream,
    )
}
