# Repository Guidelines

## Project Structure & Module Organization

wooloo mobile is a single-module (`:app`) Android client for herdr, built with Kotlin and Jetpack Compose. Source lives in `app/src/main/java/dev/herdroid/`: `ssh/` handles transport, `herdr/` implements protocols, `connection/` manages lifecycle, `data/` stores profiles and secrets, `terminal/` renders terminal output, `ui/` contains Compose screens, and `update/` handles app updates. Resources and bundled assets live in `app/src/main/res/` and `app/src/main/assets/`. JVM tests mirror packages under `app/src/test/`; device tests use `app/src/androidTest/`. See `docs/` for testing, updates, and screenshots.

## Build, Test, and Development Commands

Use JDK 17+ and Android SDK 36 (minimum supported Android API: 26). Run from the repository root:

- `./gradlew assembleDebug` — build `app/build/outputs/apk/debug/app-debug.apk`.
- `./gradlew installDebug` — install on a connected device or emulator; launch the app there.
- `./gradlew testDebugUnitTest` — run JVM/Robolectric tests.
- `./gradlew testDebugUnitTest --tests '*KeyEncoderTest*'` — run one test class.
- `./gradlew jacocoDebugCoverageVerification paneMetricsCoverageVerification lintDebug` — run coverage gates and Android lint.
- `./gradlew connectedDebugAndroidTest` — run device tests with a booted emulator.
- `./gradlew assembleRelease` — build an unsigned, minified release APK.

## Coding Style & Naming Conventions

Follow official Kotlin style with four-space indentation. Use PascalCase for classes and composables, camelCase for functions and properties, and existing package boundaries. Match nearby code and keep dependencies in `gradle/libs.versions.toml`. Android lint is configured; no dedicated formatter or Kotlin lint plugin is configured.

## Testing Guidelines

Use JUnit 4, Robolectric, and coroutine test utilities for JVM tests; Compose UI tests and Espresso cover device behavior. Name test classes `*Test.kt` and add regression coverage for changed behavior. JaCoCo enforces 70% combined core line coverage; pane quota calculations require 90% lines and 80% branches. Live SSH/Cloudflare and screenshot tests are opt-in; see `docs/testing.md` and `CLAUDE.md`. Report skipped integration tests explicitly.

## Commit & Pull Request Guidelines

Recent commits use concise, imperative subjects, such as “Hide keyboard when navigating to selection screens,” sometimes followed by `(#17)`. Follow that convention. In PRs, describe the behavior change, link relevant issues, list validation results, and include screenshots for UI changes. Run the CI checks above and both debug and release builds before review.

## Architecture & Security

Route remote commands through `RemoteCommand.herdr(...)` and `shellQuote`. Events invalidate state; `session.snapshot` remains authoritative. Keep credentials in Keystore-backed `SecretStore`, never JSON profiles. Preserve the `dev.herdroid` application identity and existing deep-link compatibility.
