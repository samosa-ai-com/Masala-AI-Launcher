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
            "Use get_now_playing first if you need to know what is playing.",
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
    )
}
