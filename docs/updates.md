# In-app updates

wooloo mobile updates itself from this repo's GitHub releases, without the Play Store, the same way
wooloo (macOS) does with Sparkle.

## How it works

- **Feed.** Every release carries `update.json` next to the APK. Installed copies read
  `https://github.com/rarce/wooloo-android/releases/latest/download/update.json` (`UPDATE_FEED_URL`,
  overridable with `-PupdateFeedUrl=…`):

  ```json
  {"versionCode": 1001, "versionName": "0.1.1",
   "url": "https://github.com/rarce/wooloo-android/releases/download/v0.1.1/wooloo-mobile-0.1.1.apk",
   "sha256": "…", "size": 28194108, "notes": "…"}
  ```

- **App** (`dev.herdroid.update`). `AppUpdater` checks once a day at launch while automatic checks are
  on, plus **Settings → Check for updates** at any time. An automatic check that finds a newer
  `versionCode` shows a dialog. Installing downloads the APK into the cache, checks its size and
  SHA-256, then checks that it is this package, at the announced version and signed with the
  installed copy's certificate (`PackageUpdateInstaller.verify`). After that it commits the APK to a
  `PackageInstaller` session. Each update requires user confirmation. Android 8+ also requires
  allowing **wooloo mobile** to install unknown apps; permission granted to the browser does not
  grant permission to wooloo. Settings offers **Allow app updates** and resumes after returning
  from Android Settings. Denying permission leaves that action available for a later retry. `UpdateInstallReceiver` gets the outcome; `PackageReplacedReceiver`
  runs in the new version and posts "Updated to X".
- **Which builds update.** Only builds made with `-PappVersion=X.Y.Z` (the release workflow) have
  `BuildConfig.UPDATES`. Debug and local release builds never update themselves: Android would
  refuse a release signed with another key anyway.
- **Versions.** `versionName` is the tag without `v`; `versionCode = major * 1_000_000 +
  minor * 1_000 + patch`. Without `-PappVersion` the build is 0.1.0 (code 1000).
- **Trust.** Android only installs an update signed with the same key as the installed app, which
  plays the role of Sparkle's EdDSA key. HTTPS and the SHA-256 in the feed protect the download.
  Keep the keystore safe: losing it means users have to uninstall and reinstall.

## Recovery

The release, session ID and installation phase are durably saved before committing the session.
The receiver correlates results with that ID, including after process recreation; an old callback
cannot clear a newer installation. Cancellation and failure remain retryable after reopening the app.
A missing Android session offers retry. Confirmation Intents stay in memory because persisting an
Intent cannot preserve all of Android's permission grants. If the process loses that Intent, Settings
offers **restart installation confirmation**, abandons the old session and downloads/verifies again.
A late confirmation callback can still resume the original session before that restart.

All installer queries, staging and preference commits run off the main thread. Each download has its
own filename and only that attempt deletes it; startup prunes stale downloads while excluding the
active attempt. When no attempt is staging, startup also abandons this app's self-update sessions
that have no persisted pending ID, including sessions left by process death during staging. The
registered pending session is preserved. No silent-update permission is requested.

This uses public APIs available since API 21/26, with `USER_ACTION_REQUIRED` set on API 31+.
Android documents recommitting sealed sessions for reboot or data-loader transient failure, not as
a general process-recreation recovery mechanism, so the app does not rely on it. See
[PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller),
[Session](https://developer.android.com/reference/android/content/pm/PackageInstaller.Session) and
[SessionParams](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams).

## Releasing

1. Optionally write `docs/releases/vX.Y.Z.md` (otherwise GitHub generates the notes).
2. Tag a commit of `main` that passed CI and push it: `git tag v0.1.1 && git push origin v0.1.1`.
3. `.github/workflows/release.yml` tests and builds the unsigned APK (no secrets in that job), then
   the publish job signs it with `apksigner`, writes `update.json` and creates the release. A release
   older than the current latest is not marked latest, so it never replaces the feed.

## Pending (temporary plan)

The code and workflow are in place; these steps are still needed before the first release:

- [x] **Make `rarce/wooloo-android` public** (done 2026-10-20; formerly `rarce/herdroid`, which GitHub
      redirects). Release assets of a private repo can't be downloaded without credentials.
- [ ] **Create the release keystore** (once, keep a backup outside the repo):

      keytool -genkeypair -keystore wooloo-mobile-release.jks -alias wooloo-mobile \
        -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12

- [ ] **Add the repo secrets** `HERDROID_KEYSTORE_BASE64` (`base64 -i wooloo-mobile-release.jks`),
      `HERDROID_KEYSTORE_PASSWORD`, `HERDROID_KEY_ALIAS` and optionally `HERDROID_KEY_PASSWORD`
      (defaults to the keystore password).
- [ ] **First release (version pending).** Identify the signer and versionCode of installations
      to migrate. Follow the safe debug migration procedure below before uninstalling anything.
- [ ] **Try a real update end to end** on a phone: install vA, publish vB, check from Settings,
      allow installation from wooloo in Android Settings, return, confirm, and see the "Updated to
      vB" notification. Publish vC and confirm again. Also deny/regrant permission, cancel/retry,
      and background or kill the app during confirmation. These device checks remain required;
      JVM tests cannot prove Android's installer UI behavior.
- [x] README links to `releases/latest`; public availability remains a separate prerequisite.

### Release authorization and rehearsal

The direct GitHub APK channel remains the configured default. The same tag also uploads the Play app
to Play's internal track from separate `build-play` and `play` jobs; see [play.md](play.md).

The `release` environment exists and is protected (the owner is the required reviewer, deployments are
restricted to `v*` tags), but it holds no HERDROID_* signing secrets yet, pending the choice of the
production signing key. Until they are added, a tag publishes to Play only: `publish` still waits
for approval in `release`, then skips with the notice "GitHub APK release skipped: signing secrets
are not configured" and succeeds without creating a GitHub release or update.json. If only some of
HERDROID_KEYSTORE_BASE64, HERDROID_KEYSTORE_PASSWORD and HERDROID_KEY_ALIAS are set, `publish`
fails and names the missing ones.

Current protections: the repository is public, and a ruleset on `main` requires pull requests with
all review threads resolved, squash merges, linear history and two passing checks, "Tests, lint and
release" and "Android 36 instrumentation"; it also blocks deleting and force-pushing `main`. The `release` and `play`
environments both exist and are protected (the owner is the required reviewer, deployments are
restricted to `v*` tags); `play` holds its four secrets and `release` holds none. Merely declaring
`environment: release` in YAML does not protect anything: GitHub can create an unprotected
environment automatically, so keep these environments configured in the repository settings.

Do not enable production signing until the owner decides the APK signing key. That key signs every
future GitHub APK, and Android refuses updates signed with a different one. Once decided, put the
HERDROID_* signing secrets in the `release` environment only, never in repository secrets. The
`main` ruleset does not cover tags: before adding them, also restrict who can create, move or delete
`v*` tags.

Tag builds first run a separate secret-free authorization job: a canonical numeric `vX.Y.Z` tag must
resolve to a commit reachable from fetched `origin/main`. Build and signing consume that exact commit.
This ancestry check establishes source membership; owner-controlled branch/tag/workflow protections
must establish who is authorized to put it there. A compromised or editable workflow can remove its
own checks. Signing runs no Gradle/build scripts. Its repository metadata helper is executable code
from the authorized commit, so reviewing that helper is part of reviewing release workflow changes.

Use **Release → Run workflow**, select `main` and enter a canonical version to rehearse. This path
runs authorization, metadata tests, JVM tests, both coverage gates, lint, debug and unsigned release
builds, and uploads artifacts. It never enters the signing/publishing job and requires no production
signing secret. Download and inspect the unsigned APK, reports and R8 mapping before a real tag.
No release, feed URL or latest-release pointer changes during rehearsal.

Version encoding is `major * 1,000,000 + minor * 1,000 + patch`, calculated using bounded `Long`
arithmetic before conversion to `Int`. Components must be canonical decimal without leading zeros;
minor/patch are at most 999 and the result must be in 1..2147483647. Thus 0.0.0 and 1.1000.0 fail;
2147.483.647 is the last supported encoding for the direct APK. Play's limit is lower: `-Pplay`
builds fail past 2100.0.0 (see [play.md](play.md#version-policy)).

The generated feed checks APK identity `dev.herdroid`, exact version name/code, HTTPS URL, SHA-256
and actual nonzero APK size (at most 256 MiB). The complete serialized JSON must fit 64 KiB of UTF-8,
including metadata and escaped note content. Unicode notes consume their encoded byte size. Long
notes fail before `gh release create`; shorten the notes and retry rather than publishing a feed the
client rejects. The signed APK is verified before metadata is generated.

Unsigned APK artifacts are retained for 30 days; reports and R8 mappings for 90 days. Mapping files
are not public release assets. The owner must download each production mapping and store it in
restricted durable storage indexed by tag, commit and APK SHA-256 before artifact retention expires;
GitHub artifacts alone are not permanent custody. Device, live SSH/Cloudflare and update installation
integration tests remain separate opt-in checks described in testing.md.

Before choosing Latest, publication fetches every page of stable releases; API failures or noncanonical stable tags abort publication, and an older version cannot replace a higher stable version as Latest.

### Moving from a debug installation

Android cannot replace an installed APK with an APK signed by a different key, even
when its versionCode is higher. Do not immediately uninstall a working debug copy.
First record non-secret machine addresses, usernames, sessions and verified server
fingerprints in a safe place. Confirm that you have another working login to each
server. If the phone holds your only private key, authorize another key from that
working session before removing the app; copying the public key is not a backup of
the private key. Never put secrets in a bug report or release comment.

Uninstalling or clearing storage removes local profiles, preferences and encrypted
keys/passwords. There is no automatic secret migration between differently signed
installations. After installing the production APK, recreate profiles, generate or
import an independently backed-up key, authorize its public key on each server and
re-enter credentials. Revoke obsolete public keys on servers after verifying access.
Same-signer updates are intended to preserve data, but the final candidate still
needs the A → B → C retention checks in the release checklist.

### Withdrawing a broken release

The release owner should stop further rollout and remove the defective `update.json`
asset from the affected release, then verify that its feed URL no longer offers that
APK. A missing feed produces a visible check error; it must not claim the app is current.
Record the affected tag, commit, APK hash, signer fingerprint and reason in an incident
note without including private reports or credentials. Do not move or reuse the tag.

Prepare a fixed release with the same production signer and a **higher versionCode**
than every distributed affected build. Re-run the gates, install/update tests and
feed checks, then publish it as Latest and check anonymous access. Do not recommend
uninstalling as a routine recovery method: it loses local data. Android generally
rejects downgrades. If the broken build cannot run its updater, install the fixed,
higher-version, same-signer APK from the release page through Android's installer.
Users already affected by credential exposure also need server-side credential
revocation; replacing the APK alone does not undo exposure.
