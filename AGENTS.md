# AGENTS.md — Masala Development Instructions (ENTRY POINT)

> **Read this file first. It is the mandatory entry point for every agent working on Masala.** Everything an agent needs to navigate, behave, and keep consistent lives behind this file. Do not skip sections.

---

## 1. What Masala is

Masala is a **private AI home-screen launcher for Android** (prototype-first; Play decision later). Home, drawer, layouts, rules, modes, widgets, voice and wiki stay Play-safe in `:app` + `:core`; everything Play restricts lives in `:pro` (all-files access, Termux ffmpeg bridge, APK export/install).

Key architectural facts:

- **Modules:** `:core` = plain Kotlin/JVM (contracts, `RuleEvaluator`, `OverlayMerge`, `LayoutChecker`, `Tool`/`FileAccess` interfaces — fast unit tests, no Android types). `:pro` = Android library, depends only on `:core` (restricted tools, own Room DB, one entry point `ProTools.register()`). `:app` = launcher + Play-safe tools, depends on `:core` + `:pro`.
- **Structured layout, not a cell grid:** pages → sections → items, mode patches as overlays, versioned JSON with migrations, 50-version undo history. The model writes layouts/rules; the phone evaluates them.
- **Safety:** every model output is checked (`LayoutChecker`); every state-changing tool goes `ActionDispatcher` / `MasalaToolExecutor` → `ConfirmationGate` → `ActionLog`.
- **Stack:** Kotlin 2.4, AGP 8.13, compileSdk/targetSdk 36, minSdk 30, Compose Material3, Room + KSP, DataStore, WorkManager, Media3, pdfbox-android. OpenAI-compatible LLM endpoint configured via `local.properties` (gitignored).
- **Authoritative spec = existing docs, no new PRD:** `docs/masala-feature-architecture.md` (what/why) + `docs/masala-implementation-plan.md` (phases/lanes/risks). If they conflict, architecture wins for design, plan wins for sequencing — flag the conflict, never silently resolve it.

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
4. Keep `HANDOFF.md` + the plan file updated as work proceeds (see §2.2).

### 2.2 Keep the state files updated

- **`HANDOFF.md` is the live development journal.** Update it **at the end of every working session** (or after any meaningful milestone) with: current phase, last completed work, files touched, verification results, in-progress work, next steps, decisions/blockers. **Scope vs status:** plan docs record scope, `HANDOFF.md` records status. Never log progress in both — the historical "Done in Phase 2" notes stay as record; new progress goes only in `HANDOFF.md` + the feature's plan file.
- **Every behavioral or architectural change must update the matching doc in the same change.** No code change ships without its doc update. Plan files live under `docs/plans/`; if you add a doc, add it to the index in §4.

### 2.3 Provenance + public-release rule (non-negotiable)

The repo goes public once via history rewrite. The strip list is authoritative: `docs/public-release-strip-list.md`.

- **Borrow freely, mention only in strip paths.** Agents can and should reuse logic from the private prototype, upstream launchers, and reversed-app ideas. But **never name the source in shippable code, comments, KDoc, commit messages, or shippable docs** — no `the private prototype`, `REDACTED`, `REDACTED`, `REDACTED`, `../the private prototype`, `example.com` internals, `LOCAL_LAN_HOST`, or `file:///local-path/...` paths.
- **Free-talk zones (stripped before public):** `reference/`, `docs/launcher-analysis/`, `docs/internal/` (create when needed), `docs/public-release-strip-list.md`, and per-feature plan files under `docs/plans/` **only if** they are added to the strip list. Anything said there is removed by `git filter-repo` — see the strip list §1.
- **Only exception: license-required attribution.** MIT/Apache-2.0/OFL require keeping notices: log them in `THIRD_PARTY.md` with a header in the file (Figtree pattern). GPL code is **study-only, never pasted** — reimplement from the idea with a header note like `core/.../AppSearch.kt:5`. When in doubt: take notes on the idea, close the file, write your own implementation.
- Before each merge, run the three verify commands in the strip list §1.

### 2.4 Test & verify

- Toolchain: **JDK 21** (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`), **Gradle 8.14.4** via the checked-in `./gradlew` wrapper (verified working 2026-10-29; an older note here said the wrapper was broken — it is not), `ANDROID_HOME=$HOME/Android/Sdk`. Without an SDK only `:core` builds (`settings.gradle.kts`).
- After any change: `./gradlew :core:test` at minimum; Android changes add `assembleDebug` + `adb install --user 0 -r` + on-device check. Never bare `adb install` (this phone has three users: the DUAL_APP and Secure Folder clones would get the build too). `connectedDebugAndroidTest` uninstalls the app afterwards — on the phone, install the test APK and run `am instrument` by hand instead (see `McpLanTest`).
- Existing tests take precedence: fix the code, not the test, unless the user explicitly asks otherwise. Never claim a result without exit codes / test output.
- Secrets live only in gitignored `local.properties` (never commit, log, or paste keys).

---

## 3. Documentation index & when to update

| File | Purpose | Update when… |
|------|---------|--------------|
| `HANDOFF.md` | Live dev state: phase, last work, next steps, decisions. | **End of every working session.** |
| `docs/masala-feature-architecture.md` | Authoritative design (features, §2.3 skills, §3 arch, §9 Play split). | Design/architecture changes. |
| `docs/masala-implementation-plan.md` | Authoritative sequencing (phases, lanes, risks, exits). | Phase scope/exit changes. |
| `docs/public-release-strip-list.md` | What `git filter-repo` removes at public release + verify commands. | Strip paths or rules change; new private folder added. |
| `docs/plans/<feature>.md` | Per-feature plan (files, changes, verification, risks). One per phase/feature. | Planning and during implementation. |
| `docs/internal/` | Private-only notes (phase status, Tier-3 decisions, demo/device prep). Create when needed; stripped at release. | Any private collaboration note. |
| `docs/launcher-analysis/` | Study-only launcher reports (strip path — never link from shippable docs). | Do not extend with REDACTED detail. |
| `THIRD_PARTY.md` | License attributions for copied-in material. | New MIT/Apache/OFL material added. |
| `reference/` | Study-only reference library (strip path). | Never add shippable code here. |

**If you add a doc file, add it to this table and to the strip list if it is private.**

---

## 4. Project map (current structure)

```
Masala-Private/
├── AGENTS.md                        ← this file (entry point)
├── HANDOFF.md                       ← live dev state (update every session)
├── THIRD_PARTY.md                   ← license attributions
├── README.md                        ← build + module overview (shippable)
├── settings.gradle.kts              ← builds :core-only without Android SDK
├── build-logic/                     ← manifest-check plugin + Play readiness report
├── core/                            ← JVM-only contracts, rules, overlay, checkers, Tool/FileAccess
├── pro/                             ← restricted tools (files, Termux, ApkInstaller), own Room DB
├── app/                             ← launcher, Room + DataStore, tools, skills, speech, debug screens
│   ├── src/main/                    ← HomeActivity, layouts, context, dispatcher, gate, renderer
│   ├── src/debug/                   ← DebugActivity (tool calls), ContextPanelActivity (fake context)
│   └── src/androidTest/             ← ClassicHomeRenderTest
├── docs/
│   ├── masala-feature-architecture.md
│   ├── masala-implementation-plan.md
│   ├── public-release-strip-list.md ← filter-repo paths + verify commands
│   ├── plans/                       ← per-feature plan files (create as needed)
│   ├── internal/                    ← private notes (create when needed; stripped)
│   └── launcher-analysis/           ← study-only (strip path)
└── reference/                       ← study-only (strip path; bulk gitignored)
```

---

## 5. Development workflow

1. Set env: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`, `ANDROID_HOME=$HOME/Android/Sdk`.
2. JVM-only: `./gradlew :core:test` (seconds, no device).
3. Full app: `./gradlew assembleDebug`, then `adb install --user 0 -r app/build/outputs/apk/debug/app-debug.apk` (never bare `adb install` — the phone has three users).
4. On device: set Masala as home → drawer/search/long-press → `Settings → Debug` (`/tool {json}` direct call or full prompt; `Context` panel fakes time/charging). `local.properties` holds `MASALA_LLM_*` / `MASALA_STT_*` (+ `SAMOSA_API_URL`).
5. When new tooling/dependencies are needed, include them in the plan file and get approval before installing.

---

## 6. Roadmap (phases — plan is authoritative)

Reference `docs/masala-implementation-plan.md` and `HANDOFF.md` for current phase and status.

- **Phase 0 — Groundwork:** contracts v1, `:core`/`:pro`/`:app`, manifest check + readiness report. **Done.**
- **Phase 1 — Launcher base:** home role, catalog, drawer/search, long-press, Room/settings, the private prototype tools + speech + scheduler into `:app`, restricted tools into `:pro`, debug screen. **Done.**
- **Phase 2 — Layout engine (critical):** rules, overlay merge, `LayoutChecker`, repository + undo, Classic layout, renderer, dispatcher + confirmation gate, context service + debug panel. **Done.**
- **Phase 3 — AI home screens + Pro foundations:** generator + repair, photo→layout, skill sheet, all-files flow, file index, mini-app host, recording service. **Next.**
- **Phase 4 — Agent + skill runtime:** `MasalaAgent`, push-to-talk, type-to-command, assist, wiki, MCP, runner/jobs/scheduled, personas + game maker.
- **Phase 5 — Pro skills + Hub:** 14 skills, APK export, Hub client, price watcher (flights/Amazon/CamelCamelCamel), acapella.
- **Phase 6 — Widgets + modes:** renderer, stacks, reminders, health, modes, quiet nights.
- **Phase 7 — Ship:** benchmark, demo seed, device prep, polish, readiness review.

---

## 7. How to start any task

1. Read this file (§1–§2) and `HANDOFF.md` first.
2. Read the relevant arch/plan sections plus the feature's `docs/plans/<feature>.md` (create it if missing).
3. Check the strip list before writing: is the source mentionable only in a strip path?
4. Produce/update the **plan file** and present it to the user.
5. **Wait for explicit approval** for phases/features; execute in small verified steps; keep `HANDOFF.md`, the plan file, and `docs/` updated alongside code.
