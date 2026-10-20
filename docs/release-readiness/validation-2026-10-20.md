# Local readiness fixes — October 20, 2026

Base `09fa9af`, working-tree changes only. No commit, push, tag, public release or
production signature was created. Implementation: Codex. Release ownership, initial
version and production signing remain owner decisions. `0.92.0` / `92000` is only a
validation version. [Artifact hashes and test totals](2026-10-20/summary.json) identify
the debug and unsigned release builds before the separate smoke build; neither is a
production distribution artifact. There is no production certificate fingerprint.

## Incorporated changes

- Local fixes from `31f0ead`, `5121d18`, `9e9363f`, plus the later uncommitted
  `test-release-readiness` changes: QR trust review, secret draft restoration,
  update lifecycle/session ownership, permission recovery, feed errors/validation,
  release authorization/version checks, conservative Latest selection and SSH close.
  Original branches and their working files were preserved.
- AndroidX ExifInterface 1.4.2 replaces platform EXIF, with reflection and rotation
  handling. Tests cover all eight EXIF metadata orientations and malformed input.
  [Upstream release reference](https://developer.android.com/jetpack/androidx/releases/exifinterface).
- Quota HTTP redirects disabled, with a regression preventing even same-origin
  redirects carrying authorization to an unintended path.
- Offline privacy, MIT, OFL and third-party inventory in Settings; README installation
  and onboarding, sanitized bug template, explicit security-contact limitation,
  draft first-release notes and recovery/debug migration instructions.
- Legacy backup exclusions added alongside existing Android 12+ data extraction rules.
- API 36 emulator CI job added for debug instrumentation and the dedicated minified
  crypto smoke suite. Hosted execution is not yet verified.

## Validation

Using local JDK 17 and Android SDK 36:

```sh
./gradlew testDebugUnitTest jacocoDebugCoverageVerification paneMetricsCoverageVerification \
  lintDebug assembleDebug assembleRelease -PappVersion=0.92.0 --offline --console=plain
python3 -m unittest discover -s scripts -p 'test_*.py' -v
./gradlew testDebugUnitTest --tests '*SettingsPrivacyTest*' -Pscreenshots \
  -PappVersion=0.92.0 --offline --console=plain
```

All passed. **219 JVM tests: 212 passed, 7 opt-in skipped, 0 failures/errors.**
Both coverage gates passed. Python: **3 passed**, including version boundaries,
feed size/identity and selection of Latest over multiple pages of release tags.
Debug and unsigned minified release builds passed. UI test opened/dismissed the
privacy, MIT and OFL documents offline. Screenshots visually inspected:
[Settings](2026-10-20/settings-privacy.png), [privacy dialog](2026-10-20/privacy-dialog.png).
`git diff --check` passed. The separate smoke compilation also passed:

```sh
./gradlew assembleRelease assembleReleaseAndroidTest -PreleaseSmoke --offline --console=plain
```

This creates a test-signed APK and its instrumentation APK, not a distributable release.
No connected test was run. The smoke artifacts must not be confused with the unsigned
production-configuration APK whose hash was captured before this step.

Lint: **0 errors, 38 warnings, 1 hint**. Remaining categories: newer dependency/plugin
versions (18), UseKtx (15), ApplySharedPref (2), ModifierParameter (1), UseTomlInstead
(1), and TrustAllX509TrustManager in the BouncyCastle dependency (1), plus one
AutoboxingStateCreation hint. The updater's synchronous preference commits intentionally
make install identity durable before proceeding. The BouncyCastle warning is in a
third-party JAR; this run does not certify its reachability or complete a vulnerability
audit. No warning category was globally suppressed. The old ExifInterface and legacy
backup-configuration warnings are resolved.

The resolved runtime inventory lists **118 artifacts** and available upstream POM
license declarations. It is not a completed notice or security audit. Original
third-party LICENSE/NOTICE material, ML Kit terms, native dependencies and brand rights
remain release prerequisites.

## External state and unexecuted checks

Read-only GitHub queries confirmed `rarce/herdroid` is **PRIVATE**. wooloo PR #65
was merged as `48306bdab7430e7f321515b932f09743d370c5a0` on October 17 at
18:11:01 UTC; its [CI passed](https://github.com/rarce/wooloo/actions/runs/39054675782/job/117460117813).
This does not establish hosted validation of the current Android changes. Secrets,
protection configuration and billing were not changed or reverified in this run.

`adb devices` returned no connected devices. **No instrumented tests, real SSH or
Cloudflare integrations, production updates, physical-phone tests, 16 KB runtime,
API 26 matrix or Access service-token tests ran in this execution.** Historical
fixture evidence remains separately identified in [live-validation.md](live-validation.md).
The seven opt-in omissions are not integration passes. No server credentials,
authorized_keys or personal device data were modified.

The repository remains unsuitable for declaring production readiness until the open
items in [todo.md](../../todo.md) have candidate-specific evidence. In particular,
public availability of the policy/feed, definitive signing and key custody, hosted CI,
private security reporting, physical pilot and complete dependency notices remain open.
