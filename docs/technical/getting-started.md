---
title: Getting Started
sidebar_position: 1
---

# Getting started

Masala ships as a single Android APK. Build it, install it for your user, make it the home app,
point it at a model, and talk to your phone.

---

## 1. Requirements

| | |
|---|---|
| **Phone** | Android 11 (API 30) or newer. Tested on physical devices and a Pixel-class emulator. |
| **JDK** | 21 for local builds (CI uses 17; both work). |
| **Gradle** | The checked-in wrapper (`./gradlew`, Gradle 8.14). |
| **Android SDK** | compileSdk 36. Point to it with `ANDROID_HOME` or `sdk.dir` in `local.properties`. |
| **A model** | Any OpenAI-compatible `/v1/chat/completions` endpoint with **tool calling**. |

> **No Android SDK?** `settings.gradle.kts` notices and builds `:core` only, so the layout
> engine, rule evaluator, checker and their 418 tests run on any machine with a JDK.

---

## 2. Build

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64   # or your JDK 21
export ANDROID_HOME=$HOME/Android/Sdk

./gradlew :core:test        # pure-JVM tests, seconds, no device
./gradlew assembleDebug     # the app
./gradlew check             # everything CI runs: tests, manifest policy, lint
```

The debug APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

## 3. Install

```sh
adb install --user 0 -r app/build/outputs/apk/debug/app-debug.apk
```

> **Always pass `--user 0`.** Many phones have a second user or profile (dual-app clones, a secure
> folder). A bare `adb install` installs Masala into every one of them.

Then press Home, pick **Masala** and choose **Always**. The welcome screen walks through the first
permissions. Every permission is optional, and a feature that lacks one says so and offers a
**Grant** button.

## 4. Configure the model

Masala talks to any OpenAI-compatible endpoint. There are two ways to set it up.

### In the app (recommended)

**Settings → Masala AI → Models & keys.** Pick a provider tile, paste a key, then press **Fetch
list** (`GET /models`) and **Test connection**. Chat, voice in, voice out and image generation are
four separate choices, so you can mix providers. For example, Meta for chat and images with on-device
speech.

| Preset | Base URL | Key | Chat | Voice in | Voice out | Images |
|---|---|---|:-:|:-:|:-:|:-:|
| **Meta** | `https://api.meta.ai/v1/` | yes | ✅ `muse-spark-1.3` | ✅ | — | ✅ `muse-image-1.0` |
| OpenAI | `https://api.openai.com/v1/` | yes | ✅ | ✅ | ✅ `tts-1` | ✅ |
| Anthropic | `https://api.anthropic.com/v1/` | yes | ✅ | — | — | — |
| Gemini | `…/v1beta/openai/` | yes | ✅ | — | — | ✅ |
| Groq | `https://api.groq.com/openai/v1/` | yes | ✅ | ✅ | — | — |
| OpenRouter | `https://openrouter.ai/api/v1/` | yes | ✅ | — | — | ✅ |
| Mistral | `https://api.mistral.ai/v1/` | yes | ✅ | — | — | — |
| LM Studio | `http://<your-pc>:1234/v1/` | no | ✅ | — | — | — |
| Ollama | `http://<your-pc>:11434/v1/` | no | ✅ | — | — | — |
| Speaches | `http://<your-pc>:8000/v1/` | yes | — | ✅ | ✅ | — |
| Custom | anything | optional | ✅ | ✅ | ✅ | ✅ |

The ticks are the presets' defaults
([`ProviderPreset.kt`](../../core/src/main/kotlin/com/samosaai/masala/core/ai/ProviderPreset.kt)).
**Test connection** decides what actually works. Keys are encrypted with AES-256-GCM under an
Android Keystore key ([`KeyCipher`](../../app/src/main/kotlin/com/samosaai/masala/llm/KeyCipher.kt))
and are never written in plain text.

### From `local.properties` (developer seed)

On the first run, a debug build seeds the settings from `local.properties`. The file is gitignored,
and environment variables override it:

```properties
MASALA_LLM_BASE_URL=http://10.0.2.2:1234/v1/   # e.g. LM Studio reached from the emulator
MASALA_LLM_API_KEY=…
MASALA_LLM_MODEL=qwen2.5-14b-instruct
# Optional separate speech-to-text endpoint
MASALA_STT_BASE_URL=…
MASALA_STT_API_KEY=…
MASALA_STT_MODEL=…
```

Release builds compile in no endpoint and no key.

> **Local models.** A server on your own network gets a 900-second read timeout instead of 180 s,
> because replies aren't streamed and a 30B model writing a full mini app can take minutes
> ([`LLMClient.kt`](../../app/src/main/kotlin/com/samosaai/masala/llm/LLMClient.kt)). LM Studio
> rejects tool schemas that have no `properties` key, so Masala always adds one
> ([`AgentModel.kt`](../../app/src/main/kotlin/com/samosaai/masala/agent/AgentModel.kt)).

## 5. Try it

| Say or type | What happens |
|---|---|
| `timer 10 minutes` | Fast path. The model isn't called; the gate asks, and the clock app starts the timer. |
| `open maps` | Fast path: launches the app. |
| `Make my home screen calmer: work apps on page one, a calendar card on top` | `read_layout` → `edit_layout`. The checker runs, you confirm, and a new undoable version is saved. |
| `Make a widget that counts my glasses of water` | Consults the **Widget Maker** expert, which writes a checked widget spec and asks to place it. |
| `Make me a snake game` | Consults the **Software engineer** expert, which writes a sandboxed HTML app and pins it to home. |
| `Find the red bicycle in this photo` | `segment_image` with **SAM 3.1** returns an overlay, one cut-out per object and a JSON summary. |
| `What should I cook with eggs and spinach?` | The **Chef** persona answers inside the chat. |
| `Every morning at 7:30 brief me on my calendar and the weather` | `schedule_agent_task`, approved by you, runs unattended with read-only tools. |

## 6. The debug screen

Debug builds add **Settings → Debug** ([`app/src/debug/`](../../app/src/debug/)):

- Send a full prompt with every registered tool and watch the raw responses, tool calls and results.
- Run a tool directly, without the model: `/pdf_edit {"operation":"info","input":"…"}`.
- **Context panel:** fake the time, charging, Bluetooth, calendar, weather, sleep or steps, and watch
  the rules and modes react live. Every field of the
  [`ContextSnapshot`](../../core/src/main/kotlin/com/samosaai/masala/core/contract/ContextSnapshot.kt)
  can be overridden.

## 7. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| "No model is set up" | Settings → Masala AI → Models & keys: pick a preset, a model and a key. |
| HTTP 400 on every message from a local model | Usually an argument-free tool schema. Masala adds `properties: {}`; update to the latest build. |
| Long replies cut off on a LAN model | The read timeout is 900 s for local hosts. Check that the host really is local (`10.x`, `192.168.x`, `localhost`). |
| A tool says "Not run: … needs …" | A permission or special access is missing. The chat shows a Grant button, and the run finishes without that tool. |
| `media_convert` fails | Install Termux, grant `RUN_COMMAND`, and set `allow-external-apps=true` in `~/.termux/termux.properties`. |
