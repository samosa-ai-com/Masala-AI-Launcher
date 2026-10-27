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
 * ToolDefinitions.kt. Descriptions that named the private prototype-only tools or its in-app alarms are reworded;
 * set_timer lost its `system` flag (timers always go to the clock app) and next_alarm is new.
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
        "Set the home-screen wallpaper from an image URL, or a random image if no URL is given.",
        schema {
            putJsonObject("properties") {
                putJsonObject("url") {
                    put("type", "string")
                    put("description", "Direct image URL. Omit for a random wallpaper.")
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

    val setAlarm = tool(
        "set_alarm",
        "Set an alarm in the system clock app. To change or remove alarms, use show_alarms to open " +
            "the clock app's list.",
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
        "Start a countdown timer in the system clock app; dismiss_timer stops a ringing one.",
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

    val dismissTimer = tool(
        "dismiss_timer",
        "Dismiss a currently ringing system-clock-app timer. Only has an effect if a timer is " +
            "actively ringing there right now; support varies by clock app.",
        schema { putJsonObject("properties") {} }
    )

    val toggleTorch = tool(
        "toggle_torch",
        "Turn the camera flashlight (torch) on or off. If the flashlight is already in the " +
            "requested state, reports that without error.",
        schema {
            putJsonObject("properties") {
                putJsonObject("on") {
                    put("type", "boolean")
                    put("description", "true to turn the flashlight on, false to turn it off.")
                }
                putJsonObject("duration_seconds") {
                    put("type", "integer")
                    put(
                        "description",
                        "Optional: auto-turn off after this many seconds (1-300). Only applies when turning on."
                    )
                }
            }
            putJsonArray("required") { add("on") }
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
        "Fetch content from an HTTP or HTTPS URL and return it as text. " +
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
                        "Optional output format: 'text' (default) or 'markdown'. Currently always returns text."
                    )
                }
            }
            putJsonArray("required") { add("url") }
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
            "rather than claiming the radio changed. Direct toggling only works below Android 10.",
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
        setAlarm,
        setTimer,
        showAlarms,
        snoozeAlarm,
        dismissTimer,
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
        getBatteryInfo,
        toggleWifi,
        nextAlarm,
    )
}
