# Google Play

The Google Play app is a separate build of wooloo mobile: package `dev.wooloo.mobile`, made with
`-Pplay`, which never updates itself (`src/play/AndroidManifest.xml` removes
`REQUEST_INSTALL_PACKAGES` and the update receivers). The GitHub APK (`dev.herdroid`, see
[updates.md](updates.md)) stays the default channel; both are built from the same `vX.Y.Z` tag.

## How a release reaches Play

Pushing a `vX.Y.Z` tag runs `.github/workflows/release.yml`:

1. `authorize` checks the tag is canonical and points at a commit on `main`.
2. `build-play` runs with a read-only token and no secrets. It writes "What's new" from
   `docs/releases/vX.Y.Z.md` if present, otherwise from GitHub's generated notes, as plain text cut to
   Play's 500 characters (`scripts/play_notes.py`), then runs
   `./gradlew bundleRelease -Pplay -PappVersion=X.Y.Z`. It uploads the unsigned AAB (`unsigned-aab`),
   the R8 mapping (`play-mapping`) and the notes (`play-notes`).
3. `play` waits for `build` (tests, coverage gates, lint) as well as `build-play`, so a tag that fails
   them never uses up its version code on Play. It runs in the protected `play` environment, so it
   also waits for a required reviewer. It checks out nothing and runs no Gradle. It:
   - signs the AAB with the upload key (`jarsigner`, keystore in `$RUNNER_TEMP`, deleted on exit) and
     verifies it against that key, failing on unsigned entries or entries signed by another key;
   - checks that the tag still points at the built commit;
   - uploads the AAB, the mapping file and the notes to the **internal** track with status
     `completed` and release name `X.Y.Z`.

If any required Play secret is missing, `play` posts a notice ("Google Play upload skipped") and
succeeds without signing or uploading. `play` and `publish` (the GitHub release) are independent: one
failing or being skipped doesn't affect the other. Both are gated on `build`.

**Release → Run workflow** (rehearsal) runs `build-play` too, so the unsigned AAB can be downloaded and
inspected, but `play` never runs.

## Tracks and testers

- **Internal testing**: every tag lands here automatically. Available to the internal testers list
  within minutes, without review.
- **Closed testing (Alpha)**: testers join the Google Group linked from <https://wooloo.dev/mobile/>,
  then opt in from the link on that page.
- **Production**: needs at least 12 testers opted in to closed testing for 14 days before Google
  allows applying for production access.

Promoting a release from internal to closed testing or production stays manual in Play Console
(**Test and release → the track → Promote release**). The workflow never touches those tracks.

Store reviewers use the offline demo machine ("Try the demo"), so it must keep working without a
network. The privacy policy is <https://wooloo.dev/mobile/privacy/>. The foreground service
declaration video is [play/foreground-service.mp4](play/foreground-service.mp4).

## Version policy

Play uses the same version code as the APK: `major * 1,000,000 + minor * 1,000 + patch` (0.1.2 is
1002). Play rejects version codes above 2,100,000,000, so with `-Pplay` the build fails for anything
past **2100.0.0**. Each upload needs a version code higher than every previous one on Play, so a
tag can be uploaded only once; fix a bad release with a new patch version.

## Owner setup

All done; nothing is pending:

- Google Cloud project `wooloo-mobile` with the Google Play Android Developer API
  (`androidpublisher`) enabled, and the service account
  `play-publisher@wooloo-mobile.iam.gserviceaccount.com`.
- In Play Console, **Users and permissions**: the service account is invited with access to this app
  only, holding "Release apps to testing tracks" plus the default read-only permissions. It has no
  production rights, so promoting to production stays manual.
- GitHub environment `play`, with the owner as required reviewer and deployments restricted to tags
  matching `v*`. It holds these secrets (not repository secrets):
  - `PLAY_SERVICE_ACCOUNT_JSON`: the service account's JSON key.
  - `PLAY_UPLOAD_KEYSTORE_BASE64`: the upload keystore (PKCS12), `base64 < upload.p12` on one line.
  - `PLAY_UPLOAD_KEYSTORE_PASSWORD`: its password.
  - `PLAY_UPLOAD_KEY_ALIAS`: the upload key's alias.
  - `PLAY_UPLOAD_KEY_PASSWORD` is deliberately unset: a PKCS12 key uses the store password, which the
    workflow falls back to.

The upload key is the one registered in Play Console under **App integrity** (Play App Signing).
Google keeps the app signing key; losing the upload key means requesting an upload key reset there.

## Manual fallback

When the workflow can't upload (secrets missing, Play API errors), release by hand from the tag:

```bash
git checkout vX.Y.Z
./gradlew bundleRelease -Pplay -PappVersion=X.Y.Z
jarsigner -keystore upload.p12 -signedjar wooloo-mobile-X.Y.Z.aab \
  app/build/outputs/bundle/release/app-release.aab <alias>
jarsigner -verify wooloo-mobile-X.Y.Z.aab
```

Or download `unsigned-aab` and `play-mapping` from the workflow run and sign that. Then in Play
Console, **Test and release → Internal testing → Create new release**: drag in the signed AAB, upload
`app/build/outputs/mapping/release/mapping.txt` as the deobfuscation file, and paste the notes.

## Known warnings

- "This App Bundle contains native code, and you've not uploaded debug symbols": the native code comes
  from third-party libraries without symbols. Accepted; it only affects crash symbolication.
- Automatic protection is on: Play adds an integrity check to installs, so the app may refuse to run
  when sideloaded from Play-signed APKs or on devices without Google Play services (such as microG).
  Those users can use the GitHub APK instead.
