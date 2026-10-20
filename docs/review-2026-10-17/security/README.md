# Credential destination and UI state review

Date: 2026-10-17. Base: `09fa9af`. Addresses publication checklist R5 and R9.

## Result

Saving or connecting an existing machine after changing its destination, SSH trust or Cloudflare Access client ID now requires an explicit review. The review identifies the previous and new destinations, pinned host keys, and credentials that will be reused or replaced. It warns that the Access secret is transmitted before SSH host verification. The guard applies even when secure-storage presence checks have not finished.

Approval binds the exact profile, credential drafts and save/connect action and remains in memory. Cancelling changes neither the saved profile nor credentials. A later edit requires a new review. Equivalent hostname casing, Cloudflare's unused SSH port and reordered pins do not prompt.

Unsaved passwords and Access secrets stay only in memory. Compose state restoration clears them and pending approval while retaining non-secret edits. Saving after restoration preserves existing ciphertexts when no replacement is entered. Private-key import and passphrase drafts already used in-memory state.

## Validation

```sh
./gradlew jacocoDebugCoverageVerification paneMetricsCoverageVerification lintDebug assembleDebug assembleDebugAndroidTest assembleRelease --offline --console=plain
./gradlew connectedDebugAndroidTest --offline --console=plain
```

- JVM/Robolectric: 197 total, 190 passed, 7 opt-in skipped, no failures.
- Both coverage gates passed. Lint: 0 errors, 26 warnings, 1 hint.
- Debug, Android test APK and production release with R8 compiled successfully.
- 11 instrumented tests passed on an isolated API 36.1 ARM64 emulator.
- New regressions cover malicious QR replacement, manual destination changes, cancellation, exact approval, benign saves and real Compose state restoration with encrypted secret fixtures.
- Independent static review found no actionable regressions.

Live SSH/Cloudflare integration, a physical phone, production signing and real app updates were not exercised. The skipped opt-in cases do not count as backend verification.

## Visual check

[Trust review](trust-review.png) uses a fictitious Cloudflare profile with no real password or token. Opening the review did not approve or connect to either host.
