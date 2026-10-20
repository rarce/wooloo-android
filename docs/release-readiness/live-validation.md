# Isolated live validation — 2026-10-17

Real SSH/Herdr and Cloudflare checks passed. Two sequential in-app updates passed
with normal minification, a temporary signer and a local-only HTTPS CA overlay.
The update lifecycle run covers recorded installation phases; it does not test a crash
between Android createSession and persisting its ID. That separate ownership-recovery
case is addressed by subsequent code review and targeted regression checks.
These results do **not** certify the production signer, production APK network
configuration, physical phones, API 26, Cloudflare Access or 16 KB runtime behavior.
Source baseline: `9e9363ff35b9e8d7c885ef73bcbb296a6c639203` plus this branch's changes.
Artifact hashes identify the tested binaries, not a future published release.

## Current results

| Check | Result and evidence |
| --- | --- |
| Full JVM/Robolectric suite | **212 total, 205 passed, 7 opt-in skipped, 0 failed**; [summary](evidence/full-jvm-summary.json) |
| Coverage gates, lint, debug/test APK and unsigned normal release builds | Passed in parent validation; report details remain in the final review |
| Debug instrumentation | **11 passed, 0 skipped** after final explicit source sets; [XML](evidence/debug-final.xml), [log](evidence/herdroid-final-debug.log) |
| Minified release crypto smoke | **1 passed, 0 skipped**, final dedicated crypto-only source set; [XML](evidence/release-crypto-final.xml), [log](evidence/herdroid-final-release-crypto.log) |
| Real loopback SSH + Herdr fullRoundTrip | **1 passed, 0 skipped** after owned-exec close fix; [XML](evidence/e2e-pass.xml), [log](evidence/herdroid-close-term-check.log) |
| SSH channel cleanup regressions | **6 passed, 0 skipped**; [XML](evidence/channel-cleanup-pass.xml) |
| Live Cloudflare SSH greeting and sshj transport | **2 passed, 0 skipped**, ephemeral tunnel to isolated fixture; [XML](evidence/cloudflare-live.xml) |
| Real updater A→B→C | **Passed**, 0.91.1→0.91.2→0.91.3, app download/verification and Android PackageInstaller UI; [artifacts](evidence/updater/artifacts.json) |
| Update permission, cancel/retry, confirmed process death | **Passed** on local-CA overlay; details below |
| Fake profile and encrypted key retained | **Passed** across both updates; B/C authenticated again with the same app-generated key; [B](evidence/updater/b-connected-ui.txt), [C](evidence/updater/c-connected-ui.txt) |
| Static 16 KB ZIP/ELF alignment | ZIP check passed for captured debug/release APKs; all arm64-v8a/x86_64 ELF LOAD alignment >=16384. 32-bit barhopper libs use 4096; [hashes/ELF data](evidence/apk-native-alignment.json) |

Still open: **16 KB runtime** (actual page size 4096; no 16 KB image download
attempted), **API 26**, **physical phone**, **final production signer and production
network configuration**, and **Cloudflare Access service-token authentication**
(no Access fixture/token supplied). Seven skipped JVM opt-in tests are not live
backend passes. Default UI preferences stayed visible during updates, but no changed
preference was exercised, so arbitrary preference migration is not claimed.

## Environment and isolation

- AVD `herdroid_release_readiness`, serial **emulator-5556**, API 36.1, ARM64 Google
  APIs image revision 4 / extension 20. Fingerprint:
  `google/sdk_gphone64_arm64/emu64a:16/BE4B.251210.005/14574095:userdebug/dev-keys`.
  `getconf PAGE_SIZE` returned **4096**; ABI `arm64-v8a`.
- Gradle checks used offline mode after dependency preparation. The parent downloaded
  the aligned AndroidX concurrent-futures 1.2.0 dependency online during remediation;
  this was not an entirely cache-only session.
- sshd bound **127.0.0.1:22346**, temporary Ed25519 host key and authorized_keys under
  `/private/tmp/herdroid-release-ssh`, password/PAM/keyboard-interactive disabled.
  No personal device, authorized_keys, production secret or default Herdr session
  was used. Herdr **0.9.5 / protocol 22**, session **herdroid-release-fixture**.
  Config was temporary; Herdr still places named-session sockets under its own
  sessions directory. The fixture pane's HISTFILE was redirected to the temporary
  directory, and future fixture shells were configured as `/bin/sh`, non-login.

## Failed attempts and their disposition

These failures remain in immutable evidence and are not counted as passes.

| Attempt | Cause, retest and evidence |
| --- | --- |
| Initial debug suite: 10/11 | Keyboard test could not find Add machine with installed fake data. Installing then clearing only disposable app data yielded focused 1/1 and full 11/11; [initial](evidence/debug-initial.xml), [clean](evidence/debug-clean.xml) |
| E2E resize: repeated failure | Observer close exceeded SSH channel-close deadline and aborted shared transport. Diagnostic assertion exposed `Closed(reason=Socket closed)`; cancelling reader first did not fix it. Owned-exec TERM + EOF enabled graceful acknowledgement; final E2E 1/1 and cleanup 6/6 passed. [diagnostic](evidence/e2e-close-origin.xml), [pass](evidence/e2e-pass.xml) |
| Release test APK R8 compilation | Missing SuspendToFutureAdapter and annotation-only Modifier. Aligning concurrent-futures dependency and test-only rules allowed compilation; [first](evidence/herdroid-release-device.log), [rerun](evidence/herdroid-release-rerun.log) |
| Broad release Compose suite | 1 crypto pass, 10 test initialization failures: removed coroutine facade, then InfiniteAnimationPolicy. This is separately compiled Compose test-runtime ABI incompatibility with optimized target. Broad coroutine keeps were removed; releaseSmoke now contains only its documented crypto test. Debug retains full UI tests; ordinary-minified updater APKs supplied independent real-app checks. [first runtime](evidence/release-aligned.xml), [later runtime](evidence/release-clean-final.xml) |
| Release install ordering | A higher-version disposable updater APK caused INSTALL_FAILED_VERSION_DOWNGRADE before tests; cleanup removed it. No pass claimed; [log](evidence/herdroid-release-final.log) |
| First local-CA overlay build | Lint required explicit includeSubdomains; fixed to false, normal lint/build rerun passed. Production source was unchanged |
| am kill process-death attempt | PID 7864 stayed alive, so not counted. Actual kill -9 on isolated userdebug AVD was verified with absent PID, then fresh PID 8683 |

## Local updater acceptance

Normal minified test APKs A **0.91.1 / 91001**, B **0.91.2 / 91002**, C
**0.91.3 / 91003** used one disposable signer, SHA-256
`32f9b0ff53b226a83d45a80603f1c59788473a83cb472ec92251580901442226`.
Only A was installed with adb. **Both upgrades used the app's HTTPS, size/hash,
package/version/signer verification and Android confirmation UI**; dumpsys confirmed
91002 and 91003. No adb install-r was used as an updater substitute.

The test-only detached checkout added an ephemeral CA resource trusted solely for
**10.0.2.2**, kept cleartext disabled, and used feed
`https://10.0.2.2:22347/update.json`. The HTTPS server bound host loopback only and
its certificate SAN was IP 10.0.2.2. CA and hostname validation passed without an
insecure flag. Production manifest/network policy were never modified. See
[manifest overlay](evidence/updater/manifest-overlay.patch),
[XML](evidence/updater/local_fixture_network.xml),
[source patch hash](evidence/updater/source-patch-sha256.txt) and
[build log](evidence/updater/build.log). No private key, keystore or APK binary is
included in repository evidence.

Observed lifecycle checks:

- Missing initial feed displayed **Update check failed (HTTP 404)**, not UpToDate.
  Failed state replaces last-checked text; visible Never is not claimed.
- Android Settings identified wooloo mobile 0.91.1 as the install source. Returning
  without granting retained **Allow app updates**. Reopening and enabling the switch
  resumed B download.
- Cancelling Android installer displayed **Update cancelled / Retry update**.
- With confirmation pending, isolated-AVD `adb root` permitted `kill -9 7864`.
  pidof then returned no process. Cancelling and reopening created **8683**, recovered
  **Update cancelled / Retry update**, and retry completed A→B. No force-stop used.
- Fake host **Disposable Local Update** and public-key fingerprint
  `SHA256:/wDyRXbF0cG/Y2rcU/LKfUs/xO12OQWIPqZ/++w3yhk` survived both upgrades.
  B and C authenticated to SSH again and displayed Herdr 0.9.5, proving the preserved
  encrypted private key was usable without extracting it.

Fake-data screenshots were visually inspected:
[404](evidence/updater/a-404-settings.png),
[Android source permission](evidence/updater/android-permission-granted.png),
[installer confirmation](evidence/updater/installer-pending.png),
[version C SSH workspace](evidence/updater/c-connected.png).

An earlier ephemeral **public** HTTPS fixture was stopped after automatic approval
review rejected the feed write for that destination. A/B APKs had been written to
its served directory before rejection and were potentially reachable; the captured
[access log](evidence/public-fixture-http-access.log) shows only GET /, with no APK
GET observed. The rejected feed command was not executed or retried; no GitHub
release was created. The accepted replacement used local HTTPS exclusively.

## Reproduce and cleanup

Use only emulator-5556 and temporary credentials. Before connected tests, uninstall
only disposable dev.herdroid if it has a higher version/different test signer.
Run `connectedDebugAndroidTest` and `connectedReleaseAndroidTest -PreleaseSmoke`
sequentially, with `ANDROID_SERIAL=emulator-5556`. releaseSmoke uses extra test entry
point keeps and a test signer; it is not the byte-identical production APK.

For real backend tests:

```sh
HERDROID_E2E_HOST=127.0.0.1 HERDROID_E2E_PORT=22346 HERDROID_E2E_USER="$USER" \
HERDROID_E2E_AUTHORIZED_KEYS=/private/tmp/herdroid-release-ssh/authorized_keys \
HERDROID_E2E_SESSION=herdroid-release-fixture \
./gradlew --offline testDebugUnitTest --tests '*HerdrEndToEndTest*'
# Use a new ephemeral SSH fixture tunnel hostname; old URLs expire after cleanup.
HERDROID_CF_TUNNEL_HOST=<fixture-hostname> ./gradlew --offline testDebugUnitTest \
  --tests '*CloudflareTunnelSocketTest.liveTunnel*'
```

For a 16 KB rerun, obtain an isolated 16 KB ARM64 image, verify getconf prints
**16384**, then repeat runtime, crypto, QR decoder and terminal checks. Static
alignment is insufficient. [Primary Android documentation](https://developer.android.com/guide/practices/page-sizes).

Updater cleanup completed: public tunnel PID 57600, HTTP PID 57590 and local HTTPS
PID 63639 stopped; no listener on 22347. The detached overlay checkout was removed.
Disposable C was uninstalled only from emulator-5556 and adb restored to non-root.
Temporary APKs/certificates remain under /private/tmp for session inspection.

At handoff to the next security retest, owned SSH fixtures remain: sshd **51773**,
named Herdr server **51952**, SSH tunnel **56129**. After that retest, TERM only
56129 and 51773, then explicitly stop **herdroid-release-fixture** with its temporary
config; never signal the main/default server.
