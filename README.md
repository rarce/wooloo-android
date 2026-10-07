# Herdroid

A native Android client (Kotlin + Jetpack Compose) for [herdr](https://github.com/herdrdev/herdr), the terminal
multiplexer for coding agents. Herdroid connects over SSH and uses herdr's own protocols to show your agents and
workspaces, let you reply to them, and drive any pane from your phone.

| Your spaces | Inside a space | Live pane | Connection states |
| --- | --- | --- | --- |
| ![spaces](docs/screenshots/session.png) | ![space](docs/screenshots/space.png) | ![terminal](docs/screenshots/terminal.png) | ![banners](docs/screenshots/banners.png) |

## How it talks to herdr

Everything runs over **one SSH connection** (sshj). Nothing needs to be installed on the server besides herdr, and
no ports are opened:

| Purpose | Remote command (exec channel) | Protocol |
| --- | --- | --- |
| API calls (snapshot, create/rename/close, split, zoom, prompt…) | `herdr --session <s> remote-api-bridge` | herdr socket API: newline-delimited JSON, one request per connection |
| Live updates | same bridge, kept open with `events.subscribe` | lifecycle events + per-pane `pane.agent_status_changed` |
| Live terminal | `herdr --session <s> terminal session control <pane> --cols C --rows R [--takeover]` | NDJSON `terminal.frame` records (base64 ANSI) out; `terminal.input` / `resize` / `scroll` / `mouse` / `release` in |
| Watch-only | `herdr terminal session observe <pane>` | same frames, read-only |

The SSH connection is either a direct TCP port or a **Cloudflare Tunnel** (e.g. xherdr's Remote Access, or
`cloudflared tunnel --url ssh://localhost:22`). For a tunnel, herdroid opens a WebSocket to `wss://<hostname>/` and
carries the raw SSH stream in binary messages, exactly like `cloudflared access ssh`, so no `cloudflared` is needed
on the phone. Hostnames behind Cloudflare Access take a service token (`CF-Access-Client-Id` / `-Secret`).

`remote-api-bridge` is the stdio bridge herdr uses for its own saved SSH machines. As herdr's docs recommend,
events only *invalidate* state, and the authoritative view always comes from `session.snapshot`.
Non-interactive SSH sessions often lack `~/.local/bin` on `PATH`, so the app looks for herdr in the usual install
locations, or uses the path you set per host.

Requires a herdr server with `terminal session control` and `remote-api-bridge`. Tested end-to-end against
**herdr 0.9.3**.

## UX highlights

- **Spaces first, like herdr's sidebar.** A machine opens on its spaces (herdr workspaces), with the ones whose
  agents need you on top. Go into a space to see its agents: blocked (amber) and done (blue) ones first, with
  *Reply* and *Open pane*, then its tabs and shells.
- **Layout miniature.** Each tab is drawn with herdr's real pane geometry, colored by agent state. It identifies
  every space and doubles as the pane switcher. Tap a pane to open it.
- **Composer for agents.** A real multi-line text field (autocorrect, swipe typing, voice) that sends with
  `agent.prompt`, plus one-tap quick replies (`Enter`, `1/2/3`, `y/n`, `Esc`, `⇧Tab`, `Ctrl-C`) for permission prompts.
- **Terminal built for touch.** Server-rendered frames drawn on a Canvas. Swipe scrolls herdr's scrollback with
  fling, pinch zooms (and resizes the remote pane), and tap clicks for mouse-aware TUIs. The IME bridge turns
  Gboard/SwiftKey composition into correct keystrokes. Long-press to copy a line, the screen, or 400 lines of
  history, or to open OSC 8 links.
- **Extra keys.** Sticky `ctrl`/`alt` (double-tap locks), auto-repeating arrows, `esc`, `tab`, `^C`, symbols,
  `pgup`/`pgdn`, and bracketed paste.
- **Moving between agents.** Inside a pane, swipe the title sideways or tap the miniature to move between the
  space's agents. When an agent in another space needs you, a snackbar offers to jump to it through its space.
- **Shared control.** If your desktop herdr already controls a pane, you can choose **Take control** or
  **Watch only**. If someone takes control from you, you can take it back.
- **Resilient connections.** Automatic reconnect with backoff, plus an immediate retry when the network returns.
  While offline, the last state stays on screen, dimmed. Errors name their fix (auth → check `authorized_keys`,
  herdr missing → set its path).
- **Notifications.** A foreground service keeps connections alive and posts a notification when an agent blocks
  or finishes. Tapping it opens that agent's pane.
- **Keys and security.** Generate an Ed25519 key on the phone (with a copyable `authorized_keys` one-liner) or
  import OpenSSH/PEM/PKCS#8 keys. Private keys and saved passwords are encrypted with the Android Keystore. Host
  keys are trust-on-first-use, with a loud warning if a key changes.
- **Cloudflare Tunnel and QR import.** Scanning xherdr's QR code (a `herdroid://add-host?…` link) opens the host
  editor prefilled with the tunnel hostname, user, session, herdr path and the machine's host key fingerprints,
  which are then trusted without a prompt. You still pick the key and save; a link never connects by itself.
  Quick tunnel hostnames change on every restart, so scanning a new code updates the matching machine (same
  name, user and session) instead of adding a duplicate.
- Pastes like `user@host:2222` (or a tunnel's `https://` URL) are split into separate fields automatically.
  Several machines can be connected at once.

## Build

```bash
./gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest        # unit tests (emulator, key encoding, protocol, models)
```

Optional checks:

```bash
# End-to-end against a real herdr over SSH (appends a generated test key to the given authorized_keys)
HERDROID_E2E_HOST=127.0.0.1 HERDROID_E2E_PORT=2222 HERDROID_E2E_USER=herd \
HERDROID_E2E_AUTHORIZED_KEYS=/home/herd/.ssh/authorized_keys ./gradlew testDebugUnitTest --tests '*HerdrEndToEndTest*'

# Through a real Cloudflare quick tunnel (`cloudflared tunnel --url ssh://localhost:22`)
HERDROID_CF_TUNNEL_HOST=name.trycloudflare.com ./gradlew testDebugUnitTest --tests '*CloudflareTunnelSocketTest*'

# Re-render the screenshots above (Robolectric native graphics, no device needed)
./gradlew testDebugUnitTest -Pscreenshots --tests '*ScreenshotsTest*'
```

Requires JDK 17+ and Android SDK 36. minSdk 26.

## Layout

```
app/src/main/java/dev/herdroid/
  ssh/          SshSession: sshj transport, TOFU host keys, key/password/keyboard-interactive auth;
                CloudflareTunnelSocket: SSH over a Cloudflare Tunnel WebSocket
  herdr/        HerdrApi (socket API over remote-api-bridge), TerminalStream, models, RemoteCommand
  connection/   HerdrConnection (lifecycle, reconnect, event-driven refresh, alerts), ConnectionManager, Notifier
  terminal/     TerminalEmulator (ANSI → grid), TerminalView (Canvas renderer + IME + gestures), KeyEncoder
  data/         host profiles, Keystore-backed secrets, SSH key manager, known hosts
  ui/           Compose screens: hosts, host editor, keys, session, terminal, settings
```
