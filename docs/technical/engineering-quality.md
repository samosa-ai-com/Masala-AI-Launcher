---
title: Engineering Quality
sidebar_position: 11
---

# Engineering quality

Masala's safety and correctness claims are backed by tests and build checks, not by convention.
This page covers what is tested, what the build enforces, and the performance work behind a home
screen that has to draw instantly.

---

## 1. Tests

| Suite | Where | Tests | Runs on |
|---|---|---:|---|
| `:core` unit | [`core/src/test/`](../../core/src/test/) | **422** | Any JVM, seconds, no Android SDK needed |
| `:pro` unit | [`pro/src/test/`](../../pro/src/test/) | **50** | JVM |
| `:app` unit | [`app/src/test/`](../../app/src/test/) + `testDebug` | **862** | JVM, without Robolectric (fakes behind interfaces) |
| `build-logic` | [`build-logic/src/test/`](../../build-logic/src/test/) | **10** | JVM |
| Instrumented | [`app/src/androidTest/`](../../app/src/androidTest/) | **52** | Device or emulator |
| **Total** | | **1,396** | |

### Strategy

- **The deciding logic is pure.** Rules, overlay merge, resolution, checkers, history, migrations,
  tool scopes, the fetch allowance, the Skill Hub policy, the schedule planner, speaker stitching,
  audio enhancement and WAV splitting all live in `:core` with no Android types, so they are tested
  exhaustively and fast.
- **Seams are `fun interface`s.** `AgentModel`, `AgentToolRunner`, `ToolGate`, `ChatCaller`,
  `CapabilityPrompt` and `TranscriptCleaner` let the agent loop, the generators and the executor run
  against scripted fakes: no server, no device, deterministic.
- **Golden vectors where there is a reference.** `MaskCodecTest` must match Meta's Python decoder
  byte for byte.
- **Regression tests carry their story.** Many tests start with the bug they pin, for example
  `AudioModelClassifyTest` (a transcriber misclassified as TTS broke `transcribe_file`) and the agent
  tests for context overflow, orphaned tool calls and LM Studio's `properties` requirement.

### Notable instrumented tests

| Test | What it proves |
|---|---|
| `MiniAppNetworkTest` | Opens real local TCP/UDP listeners and has a page try every way out: `fetch`, XHR, `<img>`, WebSocket, `sendBeacon`, and WebRTC from the page, from nested, `srcdoc` and `data:` iframes. With network **on** every way arrives; with it **off**, none does. |
| `ToolSweepTest` | Runs **every registered tool once** with minimal valid arguments and prints `SWEEP\|status\|name\|detail`, so a whole sweep can be reviewed without a phone in hand. |
| `PermissionGateTest`, `PermissionWaitTest` | The executor waits for the permission dialog instead of failing while it is open. |
| `McpLanTest`, `McpOnDeviceTest` | A real MCP server over the LAN: session, list, call. |
| `ClassicHomeRenderTest`, `WidgetRenderTest`, `HtmlWidgetTest`, `SkillWidgetRenderTest` | The renderer draws what the resolver resolved. |
| `RecordingServiceTest`, `StreamMediaTest`, `FileIndexTest`, `CalendarCreateTest`, `WallpaperToolTest` | The device-dependent tools on a real OS. |

> **On a phone with several users**, `connectedDebugAndroidTest` uninstalls the app when it finishes.
> Install the test APK and run `am instrument` by hand instead.

---

## 2. What the build enforces

[`build-logic/`](../../build-logic/) applies a plugin to `:app`. Its checks run before every
`assemble<Variant>` and with `check`:

| Task | Fails the build when… |
|---|---|
| `check<Variant>ManifestPolicy` | A permission, service, receiver, provider or `<queries>` package from outside `:pro` isn't in [`app/manifest-allowlist.txt`](../../app/manifest-allowlist.txt) with a reason, or `SCHEDULE_EXACT_ALARM` / `QUERY_ALL_PACKAGES` appears. It also writes `build/reports/play-readiness-<variant>.md`. |
| `checkProImports` | `:app` imports anything from `:pro` other than `ProTools`. |
| `checkListCalls` | Device code uses JDK 21 list calls (`getFirst`, `removeLast`, …) that **crash below API 35** even though they compile against a newer JDK. |

The Room schema is exported to [`app/schemas/`](../../app/schemas/), so every database migration is
reviewable and testable.

## 3. CI

[`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) runs on every push to `main` and on every
PR (a PR that only touches docs skips it):

```sh
./gradlew --parallel :core:test :app:testDebugUnitTest :app:lintDebug \
  :app:checkDebugManifestPolicy :app:checkProfileManifestPolicy \
  :app:checkProImports :app:checkListCalls
```

The Play readiness report is posted to the **job summary** on every run, so a reviewer sees what the
restricted module adds without downloading anything. In-progress runs on the same ref are
cancelled. Jobs run on the project's self-hosted runner; **pull requests from forks run on a
GitHub-hosted runner instead**, so outside code never runs on project hardware.

### Releases

[`.github/workflows/release.yml`](../../.github/workflows/release.yml) cuts a release when
`versionCode`/`versionName` change on `main`. It runs CI's checks, builds the signed APK, and
**verifies** it. Sentinel values passed for every endpoint and key input must not appear in the APK,
the signing certificate must match a pinned SHA-256, and the debug key is rejected. It then tags
`v<versionName>`, publishes the GitHub release with the APK, renames `## [Unreleased]` in
[`CHANGELOG.md`](../../CHANGELOG.md) to the version, and commits `update.json` for in-app updates.
A manual run defaults to a **dry run** that builds and verifies without publishing.

---

## 4. Performance

A launcher is judged by its first frame and by how smoothly it scrolls.

| Technique | Where | Effect |
|---|---|---|
| **Cached first frame** | `LayoutRepository` reads `resolved_layout.json` before Room opens | Home draws immediately on cold start; Room and the app catalog catch up behind it |
| **Memoised resolution** | [`ResolveCache`](../../core/src/main/kotlin/com/samosaai/masala/core/layout/ResolveCache.kt), an LRU keyed on (version, modes, snapshot, widget-library version) | A recomposition or context tick that changes nothing costs nothing |
| **Resolution off the main thread** | `flowOn(Dispatchers.Default)` | Merging and rule evaluation never block a frame |
| **Minute tick only while visible** | `ContextService.setHomeVisible` | No wake-ups while you use other apps |
| **Bounded live content** | ≤ 2 HTML widgets per page, ≤ 2 live video players (LRU, the rest fall back to snapshots), ≤ 8 widget rows per page | Frame time stays predictable however much the model adds |
| **Icon cache** | [`IconCache`](../../app/src/main/kotlin/com/samosaai/masala/launcher/IconCache.kt) with mask, style and tint applied once | Drawer and home don't re-render icons |
| **Profile build type** | `profile` = release R8 + resource shrinking, debug-signed | Frame times measured on what ships, installed over debug without losing data |
| **Baseline profiles** | `androidx.profileinstaller` | Compose and AndroidX start-up profiles are installed |
| **Streaming media** | `WavSplitter` streams; one decoded bitmap at a time in video segmentation; 5-minute meeting chunks | Hours of audio and video without large heap spikes |
| **Token-aware context** | Schema tokens subtracted, a 48k-char per-message cap, image culling, one overflow retry | Long chats keep working instead of failing at the model's limit |

## 5. Code health

- **~93k lines of Kotlin** across three modules, with KDoc on almost every class explaining *why*,
  not just what.
- **One registry, one executor, one gate, one log.** Every tool call has a single path, so a
  guarantee made there holds everywhere.
- **Fail closed.** Unknown tool → refused. Unknown action tool → destructive. Missing signal →
  condition false. Unparseable model output → rejected with a path. A newer schema → refused rather
  than misread.
- **Third-party material is tracked** in [`THIRD_PARTY.md`](../../THIRD_PARTY.md) (OFL fonts,
  Apache-2.0 libraries, the SAM License mask decoder). Ideas from GPL launchers are reimplemented
  from their description, never copied.
