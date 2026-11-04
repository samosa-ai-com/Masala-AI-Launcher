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

Release builds ignore all of the above: they compile in no endpoint, model or key, and people choose a
provider and paste their own key in **Settings → Models & keys**.

## Releases

Signed APKs are published as [GitHub releases](../../releases) by `.github/workflows/release.yml`:

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Write the release notes under `## [Unreleased]` in [`CHANGELOG.md`](CHANGELOG.md).
3. Push to `main`. The workflow runs CI's checks, builds and verifies the signed APK, tags
   `v<versionName>`, publishes `Masala-<versionName>.apk` (as a pre-release while the version is
   `0.x`) and commits `update.json` and `CHANGELOG.md` (notes moved under the new version).

Release builds read `update.json` from `main`: **Settings → About → Updates** shows the notes and
downloads, verifies (sha256 from `update.json`, links only to this repository's releases) and
installs the update; a background check every 12 hours posts one notification per new version.
Debug and profile builds are signed with another key, so they don't check.

**Actions → Release → Run workflow** with *dry run* on builds and verifies without publishing, and keeps
the APK as a one-day workflow artifact.

The workflow needs these repository settings:

| Name | Kind | Value |
|---|---|---|
| `RELEASE_KEYSTORE_BASE64` | secret | `base64 -w0 <keystore>.jks` |
| `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` | secrets | the keystore's credentials |
| `RELEASE_CERT_SHA256` | variable | the key's certificate digest (`apksigner verify --print-certs`, or the first dry run prints it) |

Keep the keystore backed up: an APK signed with any other key can't update an installed Masala.
Locally, the same `RELEASE_*` names in `local.properties` sign `./gradlew :app:assembleRelease`;
without them the release APK is unsigned.
