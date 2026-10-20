# Contributing to wooloo mobile

Thanks for helping. Bug reports, ideas and pull requests are all welcome. For a larger change, open an issue
first so we can agree on the approach. [todo.md](todo.md) lists the work that is already planned for the first
release.

## Set up

You need JDK 17 or later and the Android SDK 36 (the app runs on Android 8.0, API 26, and later). Android Studio
works, but everything also runs from the command line:

```sh
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug       # install on a connected device or emulator
```

You don't need a server to try a change: on the first screen, **Try the demo machine** opens a simulated herdr
session that runs on the phone. To test against a real one, install [herdr](https://github.com/herdrdev/herdr) 0.9
or later on a machine you can reach over SSH, and use a separate session for testing, never the one your agents
work in.

## Tests

```sh
./gradlew testDebugUnitTest                                          # JVM and Robolectric tests
./gradlew testDebugUnitTest --tests '*KeyEncoderTest*'               # one class
./gradlew jacocoDebugCoverageVerification paneMetricsCoverageVerification lintDebug
./gradlew connectedDebugAndroidTest                                  # device tests, with an emulator running
```

Add a regression test for changed behavior. Live SSH, Cloudflare Tunnel and screenshot tests are opt-in; see
[docs/testing.md](docs/testing.md) and the commands in [CLAUDE.md](CLAUDE.md). Say in the pull request which
opt-in tests you ran and which you skipped.

CI runs the unit tests, coverage gates, lint and both builds on every pull request, and a separate job runs the
device tests on a small-screen Android 36 emulator. Both must pass before a merge.

## Code

- Official Kotlin style with four-space indentation, matching the code around your change. Dependencies go in
  `gradle/libs.versions.toml`.
- Build every remote command with `RemoteCommand.herdr(...)` and quote arguments with `shellQuote`.
- Keep private keys, passwords and other secrets in the Keystore-backed `SecretStore`, never in the JSON profiles
  or logs.
- Events only invalidate state; `session.snapshot` stays authoritative.
- Keep the `dev.herdroid` application identity and the existing `herdroid://` deep links working.
- The demo machine is what store reviewers use: keep it working offline when you change a screen or a herdr
  command.

[CLAUDE.md](CLAUDE.md) describes the architecture in more detail.

## Pull requests

- Use a short, imperative title, such as "Hide keyboard when navigating to selection screens". Pull requests are
  squash-merged with that title.
- Describe what changes for the user, link the issue and include screenshots for UI changes.
- Don't include real hostnames, keys, tokens or terminal content from your own machines in screenshots, logs or
  test fixtures.

## Security

Don't report vulnerabilities in a public issue. See [SECURITY.md](SECURITY.md).

## Conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md).

By contributing, you agree that your contributions are licensed under the [MIT License](LICENSE).
