# Masala

An AI home-screen launcher for Android. Start with the docs:

- [`docs/masala-feature-architecture.md`](docs/masala-feature-architecture.md): features, decisions, architecture
- [`docs/masala-implementation-plan.md`](docs/masala-implementation-plan.md): phases, lanes, risks
- [`docs/launcher-analysis/`](docs/launcher-analysis/): the 17 launcher reports they draw on

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

`build-logic/` holds a Gradle plugin applied to `app` (arch §4, plan 0.4). Before each
`assemble<Variant>` and with `check`, it:

- fails the build if a permission, service, receiver, provider or `<queries>` package in the merged
  manifest comes from outside `pro` and isn't in [`app/manifest-allowlist.txt`](app/manifest-allowlist.txt);
- writes `build/reports/play-readiness-<variant>.md`: everything `pro` adds, described in
  [`pro/play-readiness.txt`](pro/play-readiness.txt), and the core entries with their reasons;
- fails if `app` imports anything from `pro` other than `ProTools`.

Every line in either list needs a reason after `#`.
