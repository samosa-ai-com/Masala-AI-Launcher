# AGENTS.md — Masala Development Instructions (ENTRY POINT)

> **Read this file first. It is the mandatory entry point for every agent working on Masala.** Everything an agent needs to navigate, behave, and keep consistent lives behind this file. Do not skip sections.

---

## 1. What Masala is

Masala is an **AI home-screen launcher for Android** (prototype-first; Play decision later). Home, drawer, layouts, rules, modes, widgets, voice and wiki stay Play-safe in `:app` + `:core`; everything Play restricts lives in `:pro` (all-files access, Termux ffmpeg bridge, APK export/install).

Key architectural facts:

- **Modules:** `:core` = plain Kotlin/JVM (contracts, `RuleEvaluator`, `OverlayMerge`, `LayoutChecker`, `Tool`/`FileAccess` interfaces — fast unit tests, no Android types). `:pro` = Android library, depends only on `:core` (restricted tools, own Room DB, one entry point `ProTools.register()`). `:app` = launcher + Play-safe tools, depends on `:core` + `:pro`.
- **Structured layout, not a cell grid:** pages → sections → items, mode patches as overlays, versioned JSON with migrations, 50-version undo history. The model writes layouts/rules; the phone evaluates them.
- **Safety:** every model output is checked (`LayoutChecker`); every state-changing tool goes `ActionDispatcher` / `MasalaToolExecutor` → `ConfirmationGate` → `ActionLog`.
- **Stack:** Kotlin 2.4, AGP 8.13, compileSdk/targetSdk 36, minSdk 30, Compose Material3, Room + KSP, DataStore, WorkManager, Media3, pdfbox-android. OpenAI-compatible LLM endpoint configured via `local.properties` (gitignored).
- **Spec lives in local plan files, no new PRD:** design and sequencing notes live under `docs/plans/` (gitignored, never pushed). If notes conflict, design wins for architecture, sequencing wins for order — flag the conflict, never silently resolve it.

---

## 2. MANDATORY working rules (the contract)

### 2.1 Plan per feature, then implement (lighter than Jalebi)

1. **Every feature/phase gets its own plan file** — `docs/plans/<feature>.md` (e.g. `docs/plans/phase3-layout-generator.md`) — before any code, covering:
   - Files to be created/modified (exact paths).
   - Exact changes in each file.
   - Verification (tests, commands, on-device checks).
   - Risks/unknowns and decisions needing the user.
2. Present the plan file to the user. **Phases and features wait for explicit approval** before implementation. Small bug/test-only fixes may plan inline in chat and proceed.
3. Implement in **small, verified steps** — one change at a time, run the relevant checks before continuing. If scope expands mid-task, stop and update the plan file.
4. Keep the session handoff note + the plan file updated as work proceeds (see §2.2).

### 2.2 Keep the state files updated

- **Keep a session handoff note updated.** Update it **at the end of every working session** (or after any meaningful milestone) with: current phase, last completed work, files touched, verification results, in-progress work, next steps, decisions/blockers. **Scope vs status:** plan docs record scope, the handoff note records status. Never log progress in both — the historical "Done in Phase 2" notes stay as record; new progress goes only in the handoff note + the feature's plan file.
- **Every behavioral or architectural change must update the matching doc in the same change.** No code change ships without its doc update. Plan files live under `docs/plans/` (gitignored, local only); if you add a doc, add it to the index in §4.

### 2.3 Provenance + public-release rule (non-negotiable)

The repo was made public via history rewrite. Private material (non-public checkouts, third-party sources, internal docs and keys) never enters the public tree.

- **Borrow freely, keep private sources unnamed.** Agents can and should reuse logic from private sibling checkouts, upstream launchers, and third-party app ideas. But **never name the source in shippable code, comments, KDoc, commit messages, or shippable docs** — no private repo names, sibling checkout paths, internal hostnames or domains, or absolute local paths.
- **Keep private notes out of the public tree.** Anything that names non-public sources, local paths, or internal systems does not belong in shippable docs, comments, or commit messages — keep those notes outside the repo.
- **Only exception: license-required attribution.** MIT/Apache-2.0/OFL require keeping notices: log them in `THIRD_PARTY.md` with a header in the file (Figtree pattern). GPL code is **study-only, never pasted** — reimplement from the idea with a header note like `core/.../AppSearch.kt:5`. When in doubt: take notes on the idea, close the file, write your own implementation.
- Before each merge, grep the tree for private names, absolute local paths, and internal hostnames; the grep must come back empty.

### 2.4 Test & verify

- Toolchain: **JDK 21** (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`), **Gradle 8.14.4** via the checked-in `./gradlew` wrapper (verified working 2026-10-29; an older note here said the wrapper was broken — it is not), `ANDROID_HOME=$HOME/Android/Sdk`. Without an SDK only `:core` builds (`settings.gradle.kts`).
- After any change: `./gradlew :core:test` at minimum; Android changes add `assembleDebug` + `adb install --user 0 -r` + on-device check. Never bare `adb install` (this phone has three users: the DUAL_APP and Secure Folder clones would get the build too). `connectedDebugAndroidTest` uninstalls the app afterwards — on the phone, install the test APK and run `am instrument` by hand instead (see `McpLanTest`).
- Existing tests take precedence: fix the code, not the test, unless the user explicitly asks otherwise. Never claim a result without exit codes / test output.
- Secrets live only in gitignored `local.properties` (never commit, log, or paste keys).

---

## 3. Documentation index & when to update

| File | Purpose | Update when… |
|------|---------|--------------|
| `docs/plans/<feature>.md` | Per-feature plan (files, changes, verification, risks). One per phase/feature. Local only, gitignored. | Planning and during implementation. |
| `THIRD_PARTY.md` | License attributions for copied-in material. | New MIT/Apache/OFL material added. |
| `CHANGELOG.md` | User-facing release notes; the `[Unreleased]` section becomes the GitHub release text. | Any user-visible change; before every version bump. |
| `docs/technical/` | Public technical documentation (architecture, layout engine, agent runtime, tool catalog, safety, widgets, skills/memory, voice, Meta AI, quality, FAQ). Written to move to the website later; every claim links to source. | Any change to behaviour, limits, tools, permissions or architecture that a page describes. Regenerate the tool catalog tables when tools change. |

**If you add a doc file, add it to this table.**

---

## 4. Project map (current structure)

```
Masala/
├── AGENTS.md                        ← this file (entry point)
├── LICENSE                          ← AGPL-3.0 (Samosa AI)
├── THIRD_PARTY.md                   ← license attributions
├── CHANGELOG.md                     ← release notes ([Unreleased] → next GitHub release)
├── README.md                        ← build + module overview (shippable)
├── settings.gradle.kts              ← builds :core-only without Android SDK
├── build-logic/                     ← manifest-check plugin + Play readiness report
├── core/                            ← JVM-only contracts, rules, overlay, checkers, Tool/FileAccess
├── pro/                             ← restricted tools (files, Termux, ApkInstaller), own Room DB
├── app/                             ← launcher, Room + DataStore, tools, skills, speech, debug screens
│   ├── src/main/                    ← HomeActivity, layouts, context, dispatcher, gate, renderer
│   ├── src/debug/                   ← DebugActivity (tool calls), ContextPanelActivity (fake context)
│   └── src/androidTest/             ← ClassicHomeRenderTest
└── docs/
    ├── technical/                   ← public technical docs (linked from README)
    └── plans/                       ← per-feature plan files (local, gitignored)
```

---

## 5. Development workflow

1. Set env: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`, `ANDROID_HOME=$HOME/Android/Sdk`.
2. JVM-only: `./gradlew :core:test` (seconds, no device).
3. Full app: `./gradlew assembleDebug`, then `adb install --user 0 -r app/build/outputs/apk/debug/app-debug.apk` (never bare `adb install` — the phone has three users).
4. On device: set Masala as home → drawer/search/long-press → `Settings → Debug` (`/tool {json}` direct call or full prompt; `Context` panel fakes time/charging). `local.properties` holds `MASALA_LLM_*` / `MASALA_STT_*` (+ `SAMOSA_API_URL`).
5. Release: bump `versionCode`/`versionName` in `app/build.gradle.kts`, fill `## [Unreleased]` in `CHANGELOG.md`, push to `main` → `.github/workflows/release.yml` publishes the signed APK as a GitHub release (README → Releases). Release builds ship no model endpoint or key.
6. When new tooling/dependencies are needed, include them in the plan file and get approval before installing.

---

## 6. Roadmap (phases — plan is authoritative)

Reference `docs/plans/` for current phase and status.

- **Phase 0 — Groundwork:** contracts v1, `:core`/`:pro`/`:app`, manifest check + readiness report. **Done.**
- **Phase 1 — Launcher base:** home role, catalog, drawer/search, long-press, Room/settings, launcher tools + speech + scheduler into `:app`, restricted tools into `:pro`, debug screen. **Done.**
- **Phase 2 — Layout engine (critical):** rules, overlay merge, `LayoutChecker`, repository + undo, Classic layout, renderer, dispatcher + confirmation gate, context service + debug panel. **Done.**
- **Phase 3 — AI home screens + Pro foundations:** generator + repair, photo→layout, skill sheet, all-files flow, file index, mini-app host, recording service. **Next.**
- **Phase 4 — Agent + skill runtime:** `MasalaAgent`, push-to-talk, type-to-command, assist, wiki, MCP, runner/jobs/scheduled, personas + game maker.
- **Phase 5 — Pro skills + Hub:** 14 skills, APK export, Hub client, price watcher (flights/Amazon/CamelCamelCamel), acapella.
- **Phase 6 — Widgets + modes:** renderer, stacks, reminders, health, modes, quiet nights.
- **Phase 7 — Ship:** benchmark, demo seed, device prep, polish, readiness review.

---

## 7. How to start any task

1. Read this file (§1–§2) first.
2. Read the feature's `docs/plans/<feature>.md` (create it if missing).
3. Check §2.3 before writing: is the source safe to name in the public tree?
4. Produce/update the **plan file** and present it to the user.
5. **Wait for explicit approval** for phases/features; execute in small verified steps; keep the handoff note, the plan file, and `docs/` updated alongside code.
