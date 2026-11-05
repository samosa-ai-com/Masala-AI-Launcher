---
title: Tool Catalog
sidebar_position: 5
---

# Tool catalog

Masala's agent can call **107 built-in tools**: 99 Play-safe ones registered by `:app` and 8
restricted ones registered by `:pro`. Connected MCP servers add more. Every tool is defined **in
code**: its name, its JSON-Schema parameters, its sensitivity, and its data flags. The model can't
add a tool or change any of these properties.

> These tables are generated from the tool registrations
> ([`MasalaTools.kt`](../../app/src/main/kotlin/com/samosaai/masala/tools/MasalaTools.kt),
> [`MasalaToolDefinitions.kt`](../../app/src/main/kotlin/com/samosaai/masala/tools/MasalaToolDefinitions.kt),
> [`AgentTools.kt`](../../app/src/main/kotlin/com/samosaai/masala/agent/AgentTools.kt),
> [`WidgetTools.kt`](../../app/src/main/kotlin/com/samosaai/masala/widgets/WidgetTools.kt),
> [`MemoryTools.kt`](../../app/src/main/kotlin/com/samosaai/masala/agent/MemoryTools.kt),
> [`ProTools.kt`](../../pro/src/main/kotlin/com/samosaai/masala/pro/ProTools.kt)).
> The "What it does" column is the first sentence of the description the model sees.

## Legend

| Sensitivity | Count | Meaning |
|---|---:|---|
| 🟢 **read-only** | 53 | Runs without asking. These are the only tools an unattended (scheduled) run may get. |
| 🟡 **asks** | 48 | `CHANGES_SOMETHING`. The confirmation sheet asks first, unless the tool narrows this per call. |
| 🔴 **destructive** | 6 | Always asks in a stronger voice and says what is lost. **"Allow all" never skips these.** |

| Flag | Meaning | Used by |
|---|---|---|
| 🔒 **private** | `readsPrivateData`: files, memory, notifications, calendar, contacts, health, location | `ToolScope.skill` (a skill doesn't get these unless it declares them); the Skill Hub policy |
| 🌐 **network** | `usesNetwork`: sends something off the phone | the Skill Hub policy (private + network = user-only install) |
| 📱 **screen** | `opensScreen`: brings up another app or a sheet | excluded from consulted experts and from unattended runs (Android blocks background activity starts) |
| **per-call** | `confirmWhen` narrows the gate for some calls | see below |

### Per-call confirmation

Sensitivity decides what an **unattended** run may get. Whether a given **call** asks can be
narrowed per tool:

- `memory_write` never asks, and `memory_edit` asks only for a dated note. Memory writes apply at once
  with **Undo**, because asking for every remembered fact would make memory unusable.
- `create_widget` / `update_widget` never ask, because saving to the widget **library** changes
  nothing on home. `place_widget` always asks, naming the widget.
- `todowrite` is answered inside the run and never reaches the executor.

### MCP tools

Each tool from a connected MCP server joins the registry as `mcp_<server>__<tool>` (capped at
**64 tools per server**, descriptions at 1,024 chars, prefixed `[MCP server "name"]`). An MCP tool is
**always `CHANGES_SOMETHING` and always 🌐**. A server's `readOnlyHint` is reported but not trusted to
skip the gate. See [Skills, memory & automation § 5](skills-memory-and-automation.md#5-mcp-connectors).

---

### Home & layout

| Tool | Sensitivity | Flags | What it does |
|---|---|---|---|
| `read_layout` | 🟢 read-only |  | The current home screen layout document as JSON (pages → sections → items and widgets). |
| `edit_layout` | 🟡 asks |  | Replace the home screen layout with a new one. |
| `set_mode` | 🟡 asks |  | Turn one of the user's modes on or off. Modes are named layouts such as Work or Sleep. |
| `about_masala` | 🟢 read-only |  | What Masala is and does: the launcher's features, where its settings live, and which permissions each part needs. |
| `set_wallpaper` | 🟡 asks | 🌐 network | Set the phone's wallpaper from an image. Accepts a web link, a local file path (/sdcard/Download/photo.jpg), a file:// path, or a content:// URI from a picker. |
| `generate_wallpaper` | 🟡 asks | 🌐 network | Generate a portrait phone wallpaper from a text description with the configured image model. |
| `set_wallpaper_routine` | 🟡 asks |  | Save a wallpaper routine: a named, ordered list of rules that changes the home wallpaper by itself. |
| `wallpaper_routine_status` | 🟢 read-only |  | What the wallpaper routines are showing right now, which rule won, why the last attempt failed if it did, and when the next change is due. |

### Widgets & streams

| Tool | Sensitivity | Flags | What it does |
|---|---|---|---|
| `list_widgets` | 🟢 read-only |  | List the widget library: id, title, where each widget sits on home. |
| `read_widget` | 🟢 read-only |  | Read one library widget's full spec JSON, to change it with update_widget. |
| `widget_schema` | 🟢 read-only |  | How a widget spec is shaped: components, bindings, sources and limits. |
| `create_widget` | 🟡 asks | per-call | Make a home-screen widget: either the full spec JSON, or one sentence and the maker writes the spec (checked, one repair turn). |
| `update_widget` | 🟡 asks | per-call | Change a library widget: either the complete replacement spec JSON (same id), or a sentence and the maker revises it (checked, one repair turn). |
| `place_widget` | 🟡 asks |  | Put a library widget on home. Always asks the user first, naming the widget. |
| `remove_widget` | 🟡 asks |  | Take a widget off home wherever it sits, keeping it in the library. |
| `delete_widget` | 🔴 destructive |  | Delete a widget from the library and take it off home everywhere. |
| `list_stream_sources` | 🟢 read-only |  | The cameras and streams the phone knows: name, kind and whether it plays. |
| `add_stream_source` | 🟡 asks |  | Add a camera or stream by name and URL (rtsp, hls, dash, progressive mp4, mjpeg or snapshot jpeg). |
| `test_stream` | 🟢 read-only |  | Connect to a stream source for up to 8 seconds and report the codec, the resolution and the latency, or the error. |

### Mini apps

| Tool | Sensitivity | Flags | What it does |
|---|---|---|---|
| `list_mini_apps` | 🟢 read-only |  | List the HTML mini-apps stored on this phone: id, title, last update, whether it may use the internet, and whether it has an icon. |
| `read_mini_app` | 🟢 read-only |  | Read a stored mini-app's full HTML, title and settings, to change it with update_mini_app. |
| `create_mini_app` | 🟡 asks |  | Save a self-contained HTML mini-app (single file: inline CSS and JS, no external requests unless the user enabled network for it). |
| `update_mini_app` | 🟡 asks |  | Replace a mini-app's HTML, keeping its title and settings. |
| `pin_mini_app` | 🟡 asks |  | Put a stored mini-app on the home screen so the user can tap it. |
| `open_mini_app` | 🟢 read-only | 📱 screen | Open a stored mini-app on screen. |
| `delete_mini_app` | 🔴 destructive |  | Delete a stored mini-app. Home items pointing at it stop working. |

### Agent control

| Tool | Sensitivity | Flags | What it does |
|---|---|---|---|
| `run_skill` | 🟢 read-only | 📱 screen | Open an installed skill (a persona or helper) and start it on a prompt. |
| `search_skills` | 🟢 read-only |  | Find installed skills (personas and helpers) by keywords, e.g. |
| `consult_expert` | 🟢 read-only | 🔒 private | Consult a specialist (a persona such as Doctor or Chef) and get its answer. |
| `question` | 🟢 read-only | 📱 screen | Ask the user something mid-run, with up to 10 options. |
| `todowrite` | 🟡 asks | per-call | Keep the run's task list: what is done, what is next. |
| `sleep` | 🟢 read-only |  | Pause the run for a few seconds, e.g. waiting for something else to finish. |
| `schedule_agent_task` | 🟡 asks |  | Remind yourself of something later: Masala runs the prompt at the set time, even with the app closed. |
| `list_scheduled_tasks` | 🟢 read-only |  | List the scheduled tasks with their ids, next runs and repeats. |
| `edit_scheduled_task` | 🟡 asks |  | Change a scheduled task; only the arguments passed are touched. |
| `delete_scheduled_task` | 🔴 destructive |  | Delete a scheduled task by id; its future runs are cancelled. |

### Second Brain

| Tool | Sensitivity | Flags | What it does |
|---|---|---|---|
| `memory_read` | 🟢 read-only | 🔒 private | Read the user's Second Brain. Pass path to open one file (paths come from MEMORY.md, links and memory_search), path "PROTOCOL" for the rules to follow before writing, or query to get the three entries that best match… |
| `memory_search` | 🟢 read-only | 🔒 private | Search every Second Brain file for text, like grep: returns path:line: text for matching lines. |
| `memory_write` | 🟡 asks | per-call | Write a whole Second Brain file, or append to it. Applies at once with Undo; never asks. |
| `memory_edit` | 🟡 asks | per-call | Replace exact text in a Second Brain file. Applies at once with Undo; changing a **dated note** asks. |
| `memory_delete` | 🔴 destructive |  | Delete a file from areas/, people/, notes/ or raw/. |
| `memory_file_raw` | 🟡 asks | 🔒 private | Keep an original file (recording, PDF, photo) in the Second Brain's `raw/` folder, copied or linked in place. |
| `memory_link_task` | 🟡 asks | 🔒 private | Link a scheduled task and the Second Brain note it belongs to, both ways: the task remembers the note and the note's task_ref names the task. |
| `update_user_profile` | 🟡 asks | 🔒 private | Update what Masala knows about the user: their occupation and background. |

### Apps & phone

| Tool | Sensitivity | Flags | What it does |
|---|---|---|---|
| `open_app` | 🟢 read-only | 📱 screen | Launch an installed app by its package name (e.g. |
| `list_installed_apps` | 🟢 read-only | 🔒 private | List installed apps optionally filtered by name or package. |
| `get_app_usage` | 🟢 read-only | 🔒 private | Report per-app screen time (top apps) over a recent window. |
| `uninstall_app` | 🔴 destructive |  | Uninstall an app by its name or package name. |
| `open_setting` | 🟢 read-only | 📱 screen | Open a system settings screen by name — wifi, internet, volume, nfc, bluetooth, location, notifications, battery, display, sound, apps, storage, language, date, airplane or accessibility. |
| `open_url` | 🟢 read-only | 📱 screen | Open a web link in the phone's browser. |
| `dial_number` | 🟢 read-only | 📱 screen | Open the dialer pre-filled with a number WITHOUT calling — the user must tap call themselves. |
| `compose_message` | 🟢 read-only | 📱 screen | Open the messaging app with a draft. Masala never sends: the user reads the draft and presses send. |
| `search_contacts` | 🟢 read-only | 🔒 private | Look up phone numbers and email addresses in the phone's contacts by name or number. |
| `add_contact` | 🟡 asks | 📱 screen | Add a contact through the Contacts app: it opens prefilled and the user reviews and saves it there, so nothing is added silently. |
| `share_file` | 🟢 read-only | 🔒 private 📱 screen | Send a file to the share sheet: the user picks where it goes (a chat, Drive, an email draft). |
| `get_clipboard` | 🟢 read-only | 🔒 private | Read the current text on the clipboard. |
| `set_clipboard` | 🟡 asks |  | Copy text onto the clipboard. |
| `take_photo` | 🟢 read-only | 📱 screen | Take a photo through the camera app: it opens, the user takes the picture, and the file lands in Masala's work folder. |
| `get_location` | 🟢 read-only | 🔒 private | The phone's coarse location from its last known fix, rounded to about a kilometre. |

### Clock & calendar

| Tool | Sensitivity | Flags | What it does |
|---|---|---|---|
| `set_alarm` | 🟡 asks |  | Set an alarm in the system clock app. Android has no API to delete a scheduled alarm: to remove or change one, open the clock app with show_alarms and tell the user to tap it there. |
| `set_timer` | 🟡 asks |  | Start a countdown timer in the system clock app. |
| `next_alarm` | 🟢 read-only |  | Tell when the next alarm on the phone rings, from any app. |
| `show_alarms` | 🟢 read-only | 📱 screen | Open the system clock app's alarms list so the user can see, change or delete alarms. |
| `snooze_alarm` | 🟡 asks |  | Snooze the currently ringing alarm. Only has an effect if an alarm is actively ringing in the clock app right now. |
| `list_calendar_events` | 🟢 read-only | 🔒 private | List events from the phone's calendars in a date range, with optional title search. |
| `create_calendar_event` | 🟡 asks |  | Add an event to the phone's calendar. The answer names the event id for later edits. |
| `edit_calendar_event` | 🟡 asks |  | Change an event's fields; only the arguments passed are touched. |
| `delete_calendar_event` | 🔴 destructive |  | Delete one event by id. The user is asked first, and reminders and attendees go with it. |

### Device controls

| Tool | Sensitivity | Flags | What it does |
|---|---|---|---|
| `get_battery_info` | 🟢 read-only |  | Report battery percentage and charging state. |
| `get_storage_info` | 🟢 read-only |  | Report free and total space: internal storage, plus the app's folder on shared storage. |
| `get_volume` | 🟢 read-only |  | Read a stream's current volume percentage, or every stream's if none is given. |
| `set_volume` | 🟡 asks |  | Set a volume stream to a percentage, reporting the previous and new levels. |
| `set_ringer_mode` | 🟡 asks |  | Set the ringer mode: normal, vibrate, or silent. |
| `set_dnd` | 🟡 asks |  | Turn Do Not Disturb on or off. Needs Do Not Disturb (notification-policy) access. |
| `toggle_torch` | 🟡 asks |  | Turn the phone's flashlight on or off. Pass on=true or on=false to set it; omit 'on' to flip whatever it is doing now. |
| `toggle_wifi` | 🟡 asks |  | Turn Wi-Fi on or off. Android 10 and newer forbid apps from toggling the radio, so on any current device this opens the Wi-Fi panel for the user to flip instead — say so rather than claiming the radio changed. |
| `vibrate` | 🟡 asks |  | Vibrate the device. Supports custom intensity and predefined patterns. |
| `read_notifications` | 🟢 read-only | 🔒 private | Read the currently active notifications from all apps (app, time, title/text/expanded text, and a dismiss key). |
| `dismiss_notifications` | 🟡 asks |  | Dismiss a notification by its key (from read_notifications), or all notifications if no key is given. |
| `get_now_playing` | 🟢 read-only | 🔒 private | Report what each app with an active media session is playing: app, state, title, artist, album, position/duration. |
| `media_control` | 🟡 asks |  | Control a media session in whichever app is playing — Spotify, YouTube Music, a podcast player, anything with a media notification. |

### Health & weather

| Tool | Sensitivity | Flags | What it does |
|---|---|---|---|
| `get_health_summary` | 🟢 read-only | 🔒 private | Summarise the user's health and fitness data from Health Connect over the last N days: steps and sleep. |
| `get_health_records` | 🟢 read-only | 🔒 private | List individual Health Connect records of one type, for questions the summary cannot answer (e.g. |
| `weather` | 🟢 read-only | 🔒 private 🌐 network | Today's weather: conditions, temperature now, the high and low in °C, the chance of rain, and the coming hours. |

### Web

| Tool | Sensitivity | Flags | What it does |
|---|---|---|---|
| `websearch` | 🟢 read-only | 🌐 network | Search the web (DuckDuckGo), returning up to 10 titles, URLs and snippets. |
| `webfetch` | 🟢 read-only | 🌐 network | Fetch content from an HTTP or HTTPS URL and return it as text or Markdown. |

### Media, documents & vision

| Tool | Sensitivity | Flags | What it does |
|---|---|---|---|
| `generate_image` | 🟡 asks | 🌐 network | Generate a general image from a text description with the configured image model. |
| `edit_image` | 🟡 asks |  | Small on-device edits to an image file already on disk: rotate (arg degrees, default 90), crop_square, resize (arg max side px, default 1024) or grayscale. |
| `segment_image` | 🟢 read-only | 🌐 network | Find one thing in an image with SAM 3.1 (configured image endpoint, no extra setup): pass an image file path and a short noun phrase like 'red bicycle'. |
| `segment_video` | 🟢 read-only | 🌐 network | Track one thing through a VIDEO file with SAM 3.1 (configured image endpoint, no extra setup): pass a video file path (e.g. |
| `media_edit` | 🟡 asks | 🔒 private | Edit an audio or video file on-device: trim a window out of it, pull its audio track into a separate file, strip the audio from a video, shrink it, change its playback speed, or join several files end to end. |
| `pdf_edit` | 🟡 asks | 🔒 private | Edit a PDF's page structure on-device: merge several PDFs into one, split one into single-page files, extract a page range into a new PDF, delete pages, or rotate pages. |
| `transcribe_file` | 🟢 read-only | 🔒 private 🌐 network | Transcribe an audio file already on disk to text, using the configured Speech-to-Text API. |
| `speak_text` | 🟡 asks |  | Read text aloud with the configured voice and save the audio file. |
| `start_audio_recording` | 🟡 asks |  | Start recording audio from the microphone to a file. |
| `pause_audio_recording` | 🟡 asks |  | Pause the in-progress recording without stopping it. |
| `resume_audio_recording` | 🟡 asks |  | Resume a paused recording. Only works if pause_audio_recording was called first. |
| `stop_audio_recording` | 🟡 asks |  | Stop the in-progress audio recording and report the saved file path and duration. |
| `get_audio_recording_status` | 🟢 read-only |  | Check whether a recording is active, and if so report its duration and file path. |

### Pro (`:pro`, Play-restricted)

| Tool | Sensitivity | Flags | What it does |
|---|---|---|---|
| `list_files` | 🟢 read-only | 🔒 private | List files and directories at a path, optionally recursive, sorted and filtered. |
| `read_file` | 🟢 read-only | 🔒 private | Read any file, detecting its type: text line-by-line (offset/limit), images fed to the vision model so you can see them, archives (zip/apk/jar) listed and readable entry-wise via 'archive.zip/path', other binaries as… |
| `write_file` | 🟡 asks |  | Write or append text or binary content to a file. |
| `edit` | 🟡 asks | 🔒 private | Replace one section of a text file, so the call only carries the changed span instead of the whole file. |
| `glob` | 🟢 read-only | 🔒 private | Find files by glob pattern (* non-/, ** recursive, ? |
| `grep` | 🟢 read-only | 🔒 private | Search file contents by case-insensitive regex, returning path, line number and line content. |
| `file_search` | 🟢 read-only | 🔒 private | Find files anywhere in the phone's storage by words in their name or text (PDF, Word, Excel, PowerPoint, text and code files), using the file index. |
| `media_convert` | 🟡 asks | 🔒 private | Convert an audio file from one format to another on-device, using Termux's ffmpeg. |

---

## Tools Masala deliberately doesn't have

| Missing on purpose | Why |
|---|---|
| Sending SMS, email or chat messages | `compose_message` and `share_file` only pre-fill; the user presses send. |
| Placing calls | `dial_number` opens the dialer; the user presses call. |
| Accessibility "tap/swipe/read screen" | No accessibility service. Hub skills whose instructions use these tools are rejected ([`HubSkillPolicy`](../../core/src/main/kotlin/com/samosaai/masala/core/tool/HubSkillPolicy.kt)). |
| A general shell | The Termux bridge runs **one** command built from a fixed ffmpeg table ([`FfmpegCommand`](../../pro/src/main/kotlin/com/samosaai/masala/pro/media/FfmpegCommand.kt)); there is no `run_command`. |
| Deleting alarms | Android has no API for it. `show_alarms` opens the clock app instead. |
| Toggling Wi-Fi silently | Android 10+ forbids it. `toggle_wifi` opens the Wi-Fi panel. |
