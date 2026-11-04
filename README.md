# Masala

An AI home-screen launcher for Android. Start with `AGENTS.md` (entry point).

## Modules

| Module | What | Builds without the Android SDK |
|---|---|---|
| `core/` | Plain Kotlin: contracts (layout, actions, rules, widgets, modes, skills), rule evaluator, mode overlay merge, layout resolver, migrations, tool and file-access interfaces, Skill Hub install policy, app keys and drawer search. Example files and `layout.schema.json` are in `core/src/main/resources/masala/` | Yes |
| `pro/` | Android library: Play-restricted tools and manifest entries. Depends on `core` only | No |
| `app/` | Android app: the launcher (home, drawer, long-press menu, settings), Room database (schemas in `app/schemas/`) and DataStore settings. Depends on `core` and `pro` | No |

## Build

```sh
./gradlew :core:test     # JVM tests, seconds; works anywhere with a JDK 17+
./gradlew assembleDebug  # needs the Android SDK (local.properties sdk.dir or ANDROID_HOME)
./gradlew check          # what CI runs: all tests, the manifest check, lint
```

Without an Android SDK, `settings.gradle.kts` builds `core` only.

## Manifest check and Play readiness report

`build-logic/` holds a Gradle plugin applied to `app`. Before each
`assemble<Variant>` and with `check`, it:

- fails the build if a permission, service, receiver, provider or `<queries>` package in the merged
  manifest comes from outside `pro` and isn't in [`app/manifest-allowlist.txt`](app/manifest-allowlist.txt);
- writes `build/reports/play-readiness-<variant>.md`: everything `pro` adds, described in
  [`pro/play-readiness.txt`](pro/play-readiness.txt), and the core entries with their reasons;
- fails if `app` imports anything from `pro` other than `ProTools`.

Every line in either list needs a reason after `#`.

## Model endpoint and the debug screen

Model calls go to any OpenAI-compatible endpoint, set in `local.properties` (gitignored,
following a local-config pattern, environment variables win):

```properties
SAMOSA_API_URL=https://…                 # default base URL is $SAMOSA_API_URL/v1/
MASALA_LLM_BASE_URL=http://10.0.2.2:1234/v1/   # optional override, e.g. LM Studio from the emulator
MASALA_LLM_API_KEY=…
MASALA_LLM_MODEL=qwen2.5-14b-instruct
```

Samosa's proxy expects a signed-in user's token as the key, and Masala has no sign-in yet, so a local
server is the easy way to test. Debug builds allow plain HTTP to `10.0.2.2` and `localhost` only.

In debug builds, **Settings → Debug** sends a prompt with every registered tool and shows the raw
responses, tool calls and results. Tools that change something ask first; missing permissions and
special access get a Grant button. A prompt like `/pdf_edit {"operation":"info","input":"…"}` runs one
tool directly, without the model.

Speech-to-text uses the same endpoint unless `MASALA_STT_BASE_URL`, `MASALA_STT_API_KEY` and
`MASALA_STT_MODEL` say otherwise.
