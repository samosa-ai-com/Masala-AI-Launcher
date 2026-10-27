# Masala

An AI home-screen launcher for Android. Start with the docs:

- [`docs/masala-feature-architecture.md`](docs/masala-feature-architecture.md): features, decisions, architecture
- [`docs/masala-implementation-plan.md`](docs/masala-implementation-plan.md): phases, lanes, risks
- [`docs/launcher-analysis/`](docs/launcher-analysis/): the 17 launcher reports they draw on

## Modules

| Module | What | Builds without the Android SDK |
|---|---|---|
| `core/` | Plain Kotlin: contracts (layout, actions, rules, widgets, modes, skills), rule evaluator, mode overlay merge, layout resolver, migrations, tool and file-access interfaces, Skill Hub install policy. Example files and `layout.schema.json` are in `core/src/main/resources/masala/` | Yes |
| `pro/` | Android library: Play-restricted tools and manifest entries. Depends on `core` only | No |
| `app/` | Android app: the launcher. Depends on `core` and `pro` | No |

## Build

```sh
./gradlew :core:test     # JVM tests, seconds; works anywhere with a JDK 17+
./gradlew assembleDebug  # needs the Android SDK (local.properties sdk.dir or ANDROID_HOME)
```

Without an Android SDK, `settings.gradle.kts` builds `core` only.
