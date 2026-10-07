# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Herdroid is a single-module (`:app`) native Android client (Kotlin + Jetpack Compose) for
[herdr](https://github.com/herdrdev/herdr), a terminal multiplexer for coding agents. It talks to herdr
entirely over one SSH connection (sshj) by running herdr's own CLI subcommands on exec channels. Nothing is
installed server-side. Tested against herdr 0.9.3.

Requires JDK 17+ and Android SDK 36 (minSdk 26).

## Commands

```bash
./gradlew assembleDebug                                    # APK → app/build/outputs/apk/debug/
./gradlew testDebugUnitTest                                # all JVM/Robolectric unit tests
./gradlew testDebugUnitTest --tests '*KeyEncoderTest*'     # single test class
./gradlew testDebugUnitTest --tests 'dev.herdroid.terminal.TerminalEmulatorTest.someMethod'

# Live Cloudflare tunnel check (skipped unless set): cloudflared tunnel --url ssh://localhost:22
HERDROID_CF_TUNNEL_HOST=name.trycloudflare.com ./gradlew testDebugUnitTest --tests '*CloudflareTunnelSocketTest*'

# Re-render docs/screenshots/*.png (Robolectric native graphics; skipped unless -Pscreenshots)
./gradlew testDebugUnitTest -Pscreenshots --tests '*ScreenshotsTest*'

# End-to-end against a real herdr over SSH (skipped unless HERDROID_E2E_HOST is set).
# Appends a generated test key to the given authorized_keys file.
HERDROID_E2E_HOST=127.0.0.1 HERDROID_E2E_PORT=2222 HERDROID_E2E_USER=herd \
HERDROID_E2E_AUTHORIZED_KEYS=/home/herd/.ssh/authorized_keys \
./gradlew testDebugUnitTest --tests '*HerdrEndToEndTest*'
```

`-ProbolectricDeps=<dir>` points Robolectric at pre-downloaded android-all jars for offline runs. There is no
lint/format tooling configured beyond `kotlin.code.style=official`. Dependencies are in `gradle/libs.versions.toml`.

## Architecture

### Protocol layer (`herdr/`, `ssh/`)

- `SshSession` wraps one sshj transport (TOFU host keys, key/password/keyboard-interactive auth) and hands out
  exec channels. Everything else multiplexes over it.
- `HostProfile.transport` is `SSH` (TCP) or `CLOUDFLARE`: then `CloudflareTunnelSocketFactory` gives sshj a
  `CloudflareTunnelSocket`, an OkHttp WebSocket to `wss://<hostname>/` whose binary messages are the raw SSH
  stream (what `cloudflared access ssh` does). The port is ignored (known hosts use 443). Optional Cloudflare
  Access service token: `accessClientId` in the profile, secret under `SecretStore.accessSecretKey(hostId)`.
  `hostKeyFingerprints` are pinned keys trusted without the TOFU prompt.
- `RemoteCommand` builds every remote command line. It wraps herdr invocations in `/bin/sh -c` and probes a list
  of install locations (non-interactive SSH often lacks `~/.local/bin` on PATH), exiting 127 with
  `NOT_FOUND_MESSAGE` if herdr isn't found — that's how `HerdrNotFoundException` / `FailureKind.HERDR_MISSING`
  are detected. Any new remote invocation should go through `RemoteCommand.herdr(...)` and `shellQuote`.
- `HerdrApi` speaks herdr's newline-delimited JSON socket API via `herdr --session <s> remote-api-bridge`.
  **herdr serves exactly one request per socket connection**, so each `request()` opens a fresh exec channel
  (concurrency-limited by a semaphore). `subscribe`/`subscribeRaw` keep a channel open for `events.subscribe`.
- `TerminalStream` runs `herdr terminal session control|observe <pane>`; herdr renders the pane server-side and
  streams NDJSON `terminal.frame` records (base64 ANSI). Input/resize/scroll/mouse/release go back as NDJSON on
  stdin. `TerminalEvent.Closed` reasons are parsed to detect "needs `--takeover`" / "was taken over".
- `Models.kt` holds the kotlinx.serialization models; decode with the shared lenient `herdrJson` instance.

### Connection layer (`connection/`)

- `HerdrConnection` owns one host: SSH lifecycle, reconnect with backoff, `ConnectionStatus` state machine,
  `FailureKind` classification of errors, and interactive `UserPrompt`s (host-key confirmation, password) that
  suspend on a `CompletableDeferred` until the UI calls `answer()`.
- **State model (follows herdr's recommendation): events only invalidate; `session.snapshot` is authoritative.**
  Lifecycle events and per-pane `pane.agent_status_changed` subscriptions just `requestRefresh()` into a
  conflated channel; a refresh worker fetches a snapshot and `applySnapshot` diffs old vs. new to emit
  `AgentAlert`s (blocked/finished) and update which panes are subscribed. Mutations go through
  `connection.action { ... }`, which refreshes afterwards.
- `ConnectionManager` holds a `StateFlow<Map<hostId, HerdrConnection>>` (multiple hosts can be live at once) and
  syncs `service/ConnectionService` (foreground service keeping connections alive). `HerdroidApp` triggers
  `onNetworkAvailable()` to skip pending backoff. `Notifier` posts agent alerts; `AppForeground` tracks visibility.

### Terminal rendering (`terminal/`, `ui/terminal/`)

- `TerminalEmulator` parses ANSI from frames into a cell grid (`TerminalModel`); `CharWidth` handles wide chars.
- `TerminalView` is a Canvas renderer plus the IME bridge (turns keyboard composition into keystrokes) and
  gestures (scroll → herdr scrollback, pinch → resize remote pane, tap → mouse click).
- `KeyEncoder` maps keys/modifiers to terminal byte sequences. `TerminalController` ties a `TerminalStream`
  to the emulator and exposes `PaneState` (busy/closed/takeover) to `TerminalScreen`.

### App wiring, data, UI

- No DI framework and no ViewModels: `HerdroidApp` constructs singletons (`HostRepository`, `SshKeyManager`,
  `SecretStore`, `KnownHostsStore`, `Notifier`, `UiPreferences`, `ConnectionManager`) and composables reach them
  via `LocalContext.current.app`.
- `HerdroidApp` replaces Android's stripped "BC" provider with full BouncyCastle at startup — sshj needs it for
  Ed25519/X25519. Tests that touch SSH crypto do the same in setup.
- Persistence is JSON files under `filesDir/store` (written via `writeAtomically`). Private keys and saved
  passwords live only in `SecretStore` (Android Keystore–encrypted), keyed by `SecretStore.privateKey(id)` /
  `passwordKey(hostId)` — never in the JSON profiles.
- Navigation is a single Compose `NavHost` in `ui/HerdroidRoot.kt` and mirrors herdr's hierarchy: hosts → session
  (the host's spaces, i.e. herdr workspaces) → space (its agents, tabs, shells) → terminal, plus edit/keys/settings.
  Spaces are the top-level unit, as in herdr's sidebar: don't surface agents across spaces outside of alerts. The
  terminal's switcher and swipe stay within the current space; jumping to another space's agent goes through that
  space so back walks up the hierarchy. Notification taps arrive as `OpenRequest`s (host, space, pane) from
  `MainActivity` and deep-link the same way.
- `herdroid://add-host?…` links (xherdr's QR code) are parsed by `data/HostLink` and open the host editor
  prefilled (the raw link travels base64url-encoded in the `edit` route). A Cloudflare link matching a saved
  machine's label + user + session updates that machine. Links never connect by themselves.
