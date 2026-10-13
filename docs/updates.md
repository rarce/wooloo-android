# In-app updates

wooloo mobile updates itself from this repo's GitHub releases, without the Play Store, the same way
wooloo (macOS) does with Sparkle.

## How it works

- **Feed.** Every release carries `update.json` next to the APK. Installed copies read
  `https://github.com/rarce/herdroid/releases/latest/download/update.json` (`UPDATE_FEED_URL`,
  overridable with `-PupdateFeedUrl=…`):

  ```json
  {"versionCode": 1001, "versionName": "0.1.1",
   "url": "https://github.com/rarce/herdroid/releases/download/v0.1.1/wooloo-mobile-0.1.1.apk",
   "sha256": "…", "size": 28194108, "notes": "…"}
  ```

- **App** (`dev.herdroid.update`). `AppUpdater` checks once a day at launch while automatic checks are
  on, plus **Settings → Check for updates** at any time. An automatic check that finds a newer
  `versionCode` shows a dialog. Installing downloads the APK into the cache, checks its size and
  SHA-256, then checks that it is this package, at the announced version and signed with the
  installed copy's certificate (`PackageUpdateInstaller.verify`). After that it commits the APK to a
  `PackageInstaller` session. Android asks the user to confirm the first time and, on Android 12+,
  updates silently from then on. `UpdateInstallReceiver` gets the outcome; `PackageReplacedReceiver`
  runs in the new version and posts "Updated to X".
- **Which builds update.** Only builds made with `-PappVersion=X.Y.Z` (the release workflow) have
  `BuildConfig.UPDATES`. Debug and local release builds never update themselves: Android would
  refuse a release signed with another key anyway.
- **Versions.** `versionName` is the tag without `v`; `versionCode = major * 1_000_000 +
  minor * 1_000 + patch`. Without `-PappVersion` the build is 0.1.0 (code 1000).
- **Trust.** Android only installs an update signed with the same key as the installed app, which
  plays the role of Sparkle's EdDSA key. HTTPS and the SHA-256 in the feed protect the download.
  Keep the keystore safe: losing it means users have to uninstall and reinstall.

## Releasing

1. Optionally write `docs/releases/vX.Y.Z.md` (otherwise GitHub generates the notes).
2. Tag a commit of `main` that passed CI and push it: `git tag v0.1.1 && git push origin v0.1.1`.
3. `.github/workflows/release.yml` tests and builds the unsigned APK (no secrets in that job), then
   the publish job signs it with `apksigner`, writes `update.json` and creates the release. A release
   older than the current latest is not marked latest, so it never replaces the feed.

## Pending (temporary plan)

The code and workflow are in place; these steps are still needed before the first release:

- [ ] **Make `rarce/herdroid` public.** Release assets of a private repo can't be downloaded without
      credentials, so until then every check fails (the feed URL answers 404 and reads as "no
      release yet").
- [ ] **Create the release keystore** (once, keep a backup outside the repo):

      keytool -genkeypair -keystore wooloo-mobile-release.jks -alias wooloo-mobile \
        -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12

- [ ] **Add the repo secrets** `HERDROID_KEYSTORE_BASE64` (`base64 -i wooloo-mobile-release.jks`),
      `HERDROID_KEYSTORE_PASSWORD`, `HERDROID_KEY_ALIAS` and optionally `HERDROID_KEY_PASSWORD`
      (defaults to the keystore password).
- [ ] **First release (`v0.1.1` or later).** Current installs are debug-signed and don't update
      themselves; uninstall them once and install the released APK. From then on updates arrive
      in the app.
- [ ] **Try a real update end to end** on a phone: install vA, publish vB, check from Settings,
      confirm the first install, and see the "Updated to vB" notification. Then publish vC to see
      the silent update on Android 12+. The unit tests cover the feed, download and state machine,
      but not `PackageInstaller` itself.
- [ ] Optional: a "Download APK" link on the site or README pointing at
      `releases/latest/download/…` once the APK name is stable.
