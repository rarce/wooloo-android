# First release notes — draft, not published

Version, source SHA, production signer fingerprint and APK SHA-256 must be filled
from the approved candidate before these notes become a versioned `vX.Y.Z.md` file.
The validation version 0.92.0 is not a release decision.

wooloo-mobile (launcher: wooloo mobile) brings herdr spaces, agents and terminal panes
to Android 8+. Requires SSH access and herdr with `remote-api-bridge` and terminal
control. Historical isolated integration passed with herdr 0.9.3 and 0.9.5;
repeat interoperability with the chosen release pair before publication.

Features: direct SSH or Cloudflare transport, local Ed25519 keys, QR machine import,
agent notifications, prompt attachments, terminal input and optional experimental quotas.
QR imports require destination/fingerprint review; changed trust or credential reuse
requires confirmation. Quotas require per-identity consent and contact providers
from the phone using credentials read from the host.

Download only from the project's GitHub Releases. Compare the artifact checksum and
signer fingerprint, install the APK, then follow the [onboarding](../../README.md).
Every in-app update needs confirmation; Android may also require permission for wooloo
to install packages. See [recovery and debug migration](../updates.md).

Known release prerequisites remain in [todo.md](../../todo.md): final signing,
public channel access, device matrix, 16 KB runtime, private security reporting and
complete third-party notices/security review. Accessibility, third-party quota endpoint
availability and mobile-network reliability must not be described as fully validated.

[Privacy](../../app/src/main/assets/legal/privacy.txt) · [License](../../LICENSE) ·
[Sanitized bug reports](https://github.com/rarce/herdroid/issues)
