---
title: FAQ & Known Limitations
sidebar_position: 12
---

# FAQ & known limitations

## FAQ

**Does Masala replace my launcher?**
Yes. It is a full home app with a home screen, drawer, search, folders, widgets, a long-press menu,
icon shapes and styles, and modes. Set it as default under Android's *Home app* setting. The AI is
optional: without a model configured, it is still a launcher.

**Which model do I need?**
Any OpenAI-compatible endpoint that supports **tool calling**. The presets cover Meta, OpenAI,
Anthropic, Gemini, Groq, OpenRouter, Mistral, LM Studio, Ollama and Speaches, and *Custom* covers
anything else. Bigger models write better layouts and apps. The checker makes small models safe,
but it can't make them clever.

**Can the AI do something without asking me?**
Only read-only things: look at your calendar, search the web, read a file, list apps. Anything that
changes something asks first, and 60 seconds of silence counts as *no*. Destructive actions always
ask, even with "Allow all" switched on for that chat. Memory writes are the deliberate exception:
they apply at once with an **Undo** card. See [Safety & permissions](safety-and-permissions.md).

**Can it send messages or make calls for me?**
No. There is no send tool. `compose_message` and `dial_number` open the app pre-filled, and you
press send or call.

**What leaves my phone?**
What you send to the model endpoint you configured (the conversation, the tool schemas, and the
results of the tools it called), web searches and fetches, MCP calls, and uploads you trigger
(transcription, segmentation). The action log, chats, memory, layouts and recordings stay in
app-private storage. Release builds contain no endpoint or key.

**Why is the home screen a document instead of a grid?**
So the model can write it, the phone can check it, modes can patch it, rules can adapt it, and you
can undo it. A pixel grid supports none of that. Masala does also have a cell grid, as one *section
type* inside the document. See [Layout engine](layout-engine.md).

**How do modes work without duplicating my home screen?**
A mode is a patch (add these, hide those) applied on top of the base layout, in a fixed order. Edit
the base, and every mode follows.

**Why are widgets JSON and not HTML?**
A widget sits on home all day and reads live data (battery, calendar, health). A data spec drawn by
native code can't run code, can't read anything outside its bindings, and is checked before it
appears. For full interactivity there are mini apps, which run in a strict sandbox instead.

**Can generated apps reach the internet?**
Not by default. Each app has its own network switch. With it off, requests are blocked at three
layers (WebView, request interception, CSP) and WebRTC is removed.

**What does `:pro` add, and why is it separate?**
All-files access, ffmpeg through Termux, and APK install, which are permissions Google Play
restricts. Keeping them in their own module, with a build check, means the Play-safe app can ship
without them, and every file tool falls back to working on picked files.

---

## Known limitations

| Area | Limitation | Why / workaround |
|---|---|---|
| **Fast path** | English only | It is a deliberately tiny regex matcher; everything else goes to the model, which handles any language. |
| **Alarms** | Can't delete or edit existing alarms | Android has no API for it. `show_alarms` opens the clock app. |
| **Wi-Fi** | `toggle_wifi` opens a panel rather than toggling | Android 10+ forbids apps from toggling the radio. |
| **Scheduled runs** | Inexact timing; read-only tools only; no location | No `SCHEDULE_EXACT_ALARM` (a Play policy). Unattended runs can't ask, and background location needs a Play declaration. |
| **Replies** | Not streamed | One request per round. LAN models get a 900 s timeout to compensate. |
| **Mini-app probe** | Catches errors while loading and in early timers, not ones that only happen during play | The probe WebView is off screen, so frame callbacks may not run. |
| **Video segmentation** | Clips ≤ 12 MB, ≤ 16 sampled frames | The video is inlined as base64 (about three copies in memory). Trim first with `media_edit`. |
| **Meeting audio cleanup** | Level and rumble only, not model-based denoising | `PcmEnhancer` is DSP; the UI says so. |
| **Speaker stitching** | Can be `uncertain` with a thin overlap | Shown in the UI rather than guessed. |
| **MCP** | Streamable HTTP only; no stdio, old SSE transport detected and reported | A phone can't spawn local server processes. |
| **MCP read-only hints** | Not trusted; every MCP tool asks | A server's own annotation isn't proof. |
| **Termux ffmpeg** | Needs Termux, the `RUN_COMMAND` grant and `allow-external-apps=true` | Termux runs under its own uid, so Masala can't exec its binaries directly. |
| **Samosa sign-in** | Not yet; no Samosa preset | Use any preset with your own key, or a local server. |
| **Play Store** | Not published yet | Prototype-first. Signed APKs ship from GitHub Releases and update in-app; the readiness report tracks what `:pro` would need declared. |
