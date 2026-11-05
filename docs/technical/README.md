---
title: Masala
sidebar_label: Masala Docs
sidebar_position: 0
slug: /masala/docs/
---

# Masala

> **An AI home screen for Android.** You describe the home screen you want, and the model writes it. The phone checks every word the model writes before anything changes.

Masala is a full Android launcher (home, app drawer, search, widgets, folders, modes) with an
agent built in. The agent can lay out the home screen, build widgets and small HTML apps, run
more than a hundred phone tools, consult expert personas, remember things in a file-based
"Second Brain", record and summarise meetings, and run jobs on a schedule.

The design rule behind all of it: **the model writes data, the phone decides.** Layouts, widgets,
modes and rules are versioned JSON documents. Plain Kotlin checks them, evaluates them on the
device, and keeps 50 undoable versions. Every action that changes something goes through one
confirmation gate and one append-only audit log.

---

## Quick links

| | |
|---|---|
| 🚀 **[Getting started](getting-started.md)** | Build, install, point it at a model, try it. |
| 🏗️ **[Architecture](architecture.md)** | Modules, component map, data flow, and the stack. |
| 🧩 **[Layout engine](layout-engine.md)** | Pages → sections → items, rules, mode overlays, the checker, undo. |
| 🤖 **[Agent runtime](agent-runtime.md)** | The tool loop, context budgeting, tool scopes, experts, the LLM client. |
| 🧰 **[Tool catalog](tool-catalog.md)** | All 107 built-in tools with their sensitivity and data flags. |
| 🛡️ **[Safety & permissions](safety-and-permissions.md)** | The gate, the audit log, prompt-injection rules, the Play/Pro split. |
| 🪟 **[Widgets & mini apps](widgets-and-mini-apps.md)** | Generated widgets as data, sandboxed HTML apps, the Pocket Store. |
| 🧠 **[Skills, memory & automation](skills-memory-and-automation.md)** | Personas, Skill Hub policy, Second Brain, scheduled runs, MCP. |
| 🎙️ **[Voice & meetings](voice-and-meetings.md)** | Push-to-talk, TTS, hours-long meeting recording with diarisation. |
| 🦙 **[Built on Meta AI](meta-ai.md)** | The Meta preset, Muse models, SAM 3.1 segmentation, the mask codec port. |
| ✅ **[Engineering quality](engineering-quality.md)** | 1,396 tests, CI guards, the manifest policy, performance work. |
| ❓ **[FAQ & known limitations](faq.md)** | What it doesn't do yet, and why. |

---

## How it works

1. **You ask.** Type, speak (push-to-talk), or tap a widget button. A short command like
   "timer 10 minutes" or "open maps" skips the model altogether
   ([`CommandFastPath`](../../core/src/main/kotlin/com/samosaai/masala/core/agent/CommandFastPath.kt)).
2. **The agent plans.** [`MasalaAgent`](../../app/src/main/kotlin/com/samosaai/masala/agent/MasalaAgent.kt)
   sends the conversation and the tools this run may use to any OpenAI-compatible endpoint, and runs
   the tool calls that come back, for up to 100 rounds.
3. **The phone checks.** A layout or widget goes through
   [`LayoutChecker`](../../core/src/main/kotlin/com/samosaai/masala/core/layout/LayoutChecker.kt) /
   [`WidgetChecker`](../../core/src/main/kotlin/com/samosaai/masala/core/widgets/WidgetChecker.kt).
   Each problem comes back with its JSON path, and the model gets one repair turn.
4. **You confirm.** Anything that isn't read-only stops at the
   [`ConfirmationGate`](../../app/src/main/kotlin/com/samosaai/masala/actions/ConfirmationGate.kt).
   Silence for 60 seconds counts as "no".
5. **It's logged and undoable.** Every call and every answer is written to the
   [`ActionLog`](../../app/src/main/kotlin/com/samosaai/masala/tools/ActionLog.kt). Every layout
   change is a labelled version you can undo.

---

## Highlights

- **Structured home screen, not a cell grid.** A layout is a typed, versioned document
  (`schemaVersion` 4) with migrations, rules and mode overlays. The model can generate a whole home
  from one sentence or a photo.
- **On-device rule engine.** One condition language (time windows, calendar, charging, Bluetooth,
  sleep, steps, weather, modes, urgent notifications) drives modes, widget visibility and size, and
  stack order. It's pure, offline, and tested.
- **107 tools, three sensitivity levels.** Read-only tools run directly, tools that change something
  ask first, and destructive tools ask in a stronger voice. A tool's sensitivity is declared in code;
  the model can't change it.
- **Least-privilege runs.** A typed command sees every tool. A skill run sees only what it declared
  plus harmless read-only tools. An unattended scheduled run gets nothing that would ask or open a
  screen.
- **Generated widgets are data, never code.** There are 16 component types and 13 data bindings,
  web values need per-host approval, and media and HTTP limits are enforced by the checker.
- **Sandboxed HTML mini apps.** No JS bridge, no file access, a per-app origin, and the network off by
  default (requests intercepted, a CSP, WebRTC removed). 317 offline apps and games come bundled.
- **Expert personas.** Doctor, Chef, Researcher, Software engineer, Widget Maker and more. The main
  agent consults them as tools and answers in its own voice.
- **Second Brain.** File-based memory (`SOUL.md`, `MEMORY.md`, areas, people, notes, raw files with
  `[[links]]`). Writes apply at once with undo, and memory from untrusted content is refused by rule.
- **Meetings.** A foreground recorder that runs for hours in 5-minute chunks, with diarised
  transcription and map/reduce notes.
- **Built on Meta AI.** A first-class Meta provider preset (Muse Spark chat, Muse image, Muse
  voice transcription) and **SAM 3.1** image and video segmentation, with a byte-exact Kotlin port of
  Meta's mask codec.
- **Play-safe by construction.** Restricted capabilities (all-files access, Termux ffmpeg, APK
  install) live in `:pro`. A Gradle plugin fails the build if one leaks into the Play-safe modules.

---

## Project status

**v0.1.1, prototype-first.** Phases 0–2 (contracts, launcher base, layout engine) are complete.
Phases 3–4 (AI home screens, agent and skill runtime) are largely built, and later phases add more
widgets, modes and Pro skills. The Play Store decision comes later. Until then the signed APK ships
from GitHub Releases and updates itself in-app. Neither the APK nor this repository contains a model
endpoint or key. Masala is licensed under **AGPL-3.0**.

## At a glance

- **Platform:** Android 11+ (minSdk 30), targetSdk/compileSdk 36
- **Language / UI:** Kotlin 2.4, Jetpack Compose Material 3
- **Modules:** `:core` (pure JVM), `:pro` (restricted tools), `:app` (launcher)
- **Model:** any OpenAI-compatible endpoint; 11 provider presets including Meta, OpenAI, Anthropic, Gemini, Groq, OpenRouter, Mistral, LM Studio, Ollama, Speaches, Custom
- **Persistence:** Room (19 tables, schema v13) in `:app` plus a separate `:pro` database with an FTS4 file index; DataStore; plain files for chats and memory
- **Code size:** ~93k lines of Kotlin; ~7.7k in `:core`, ~3.1k in `:pro`, ~61k in `:app` main
- **Tests:** 1,344 JVM unit tests (`:core` 422, `:pro` 50, `:app` 862, `build-logic` 10) + 52 instrumented tests

> **About these docs.** They describe the code on `main` and link to the source for every claim.
> They are written so they can move to the Samosa AI website later (`/masala/docs/…`) without
> rewriting. Each page has Docusaurus front matter.
