# Testing and coverage

Run the JVM/Robolectric suite and the coverage gates:

```sh
./gradlew jacocoDebugCoverageVerification paneMetricsCoverageVerification lintDebug
```

JaCoCo writes HTML to `app/build/reports/jacoco/jacocoDebugReport/html/index.html`
and XML alongside it. The report includes production Kotlin classes, including Compose UI;
Android resource/build constants and generated serialization classes are excluded.
Companion objects remain included because they can contain application logic.

The enforced line-coverage floor for `herdr`, `data`, `ssh`, `connection`, `terminal`
and `update` combined is 70%. The pane quota calculation has a stricter floor:
90% lines and 80% branches. CI runs both gates and uploads coverage, test and lint reports.
These are scoped thresholds, not a claim that the entire UI is covered to 70%.

The suite covers protocol framing and deadlines, reconnect/generation handling,
storage recovery and transactions, SSH key import/export, terminal input and rendering,
update verification, notifications, selection lists and app navigation. Pane metrics
cover provider matching, remaining capacity, expired windows, missing/error data,
consent, disconnected state and invalid percentages.

Run functional tests with a booted Android emulator:

```sh
./gradlew connectedDebugAndroidTest
```

Device tests check the production host/space/agent list components at normal and 200%
font size in both themes, reply actions, SSH Ed25519 signing, retryable key imports,
remaining quota when switching providers, and the real app's keyboard opening in a
host input and hiding when returning to machine selection. The tests do not require
or open a remote SSH connection. Espresso 3.7.0 supports the Android 16 input APIs.

Tests for real SSH/herdr and Cloudflare endpoints are opt-in; configure the variables
in `CLAUDE.md` to include them. Screenshot generation is also opt-in. Their skipped
status must not be interpreted as a successful live backend test.

## Local validation, 2026-10-17

Pixel 9 AVD with the installed Android 16 / API 36.1 ARM64 system image:

| Check | Result |
| --- | --- |
| JVM/Robolectric | 190 total: 183 passed, 7 opt-in skipped |
| Android device tests | 9 passed, 0 skipped |
| Core line coverage | 70.92% |
| Global line coverage, including UI | 53.88% |
| Pane quota model | 100% lines, 97.14% branches |
| Android lint | 0 errors, 51 warnings, 1 hint |

Global UI coverage is still below the core threshold. More coverage is needed for
live terminal/session orchestration and connection lifecycle paths; device component
checks do not replace a functional run against a real herdr server.
