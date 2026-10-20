# Update recovery review — 17 October 2026

This change persists the release, installer session ID and phase before committing to Android. It accepts callbacks after process recreation, ignores obsolete sessions, preserves a retry after cancellation/failure, and restarts a lost confirmation explicitly. It handles wooloo's own install-source permission and requests user confirmation for every update. HTTP 404 is a failed check and never records a successful check time.

The focused JVM run passed 25 tests with no omissions. It covers immediate and late confirmation, missing sessions, cancellation after recreation, obsolete callbacks during a paused download, permission denial/return, successful replacement, durable-write failures before and after commit, and safe download cleanup. An independent read-only adversarial review found no actionable defect in the diff.

The complete JVM suite contains 209 tests: 202 passed and seven opt-in integration/screenshot tests were skipped. The coverage gates, lint and debug/release builds are recorded separately in the release checklist after completion.

These are JVM/Robolectric results, using the locally available API 35 runtime. They do not establish Android's actual installer UI, background activity launch behavior, API 26–36 device coverage, or a production-signed A → B update. Those checks remain open. No release was published and no production signing key was used.
