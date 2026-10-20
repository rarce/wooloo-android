"""Validate release identity and generate a feed within the Android client's limits."""
import argparse
import hashlib
import json
import re
from pathlib import Path
from urllib.parse import urlsplit

MAX_FEED = 64 * 1024
MAX_APK = 256 * 1024 * 1024


def version_code(version):
    if not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', version):
        raise ValueError('Version must be canonical X.Y.Z (no leading zeros)')
    major, minor, patch = map(int, version.split('.'))
    code = major * 1_000_000 + minor * 1_000 + patch
    if minor > 999 or patch > 999 or not 1 <= code <= 2_147_483_647:
        raise ValueError('Version components exceed minor/patch 999 or positive Int versionCode')
    return code


def should_mark_latest(version, tags):
    candidate = version_code(version)
    stable_codes = []
    for tag in tags:
        if not tag.startswith('v'):
            raise ValueError('Stable release tag must be canonical vX.Y.Z: ' + repr(tag))
        try:
            stable_codes.append(version_code(tag[1:]))
        except ValueError as error:
            raise ValueError('Invalid stable release tag; resolve release history before publishing: ' + repr(tag)) from error
    return candidate >= max(stable_codes, default=0)


def feed(apk, notes, version, url, badging):
    code = version_code(version)
    identity = re.match(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging)
    if not identity or identity.groups() != ('dev.herdroid', str(code), version):
        raise ValueError('APK package/version does not match dev.herdroid and requested release')
    parsed = urlsplit(url)
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.fragment:
        raise ValueError('APK URL must be HTTPS without credentials or fragment')
    size = apk.stat().st_size
    if not 1 <= size <= MAX_APK:
        raise ValueError('APK must be nonempty and at most 256 MiB')
    digest = hashlib.sha256()
    with apk.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    payload = dict(versionCode=code, versionName=version, url=url,
                   sha256=digest.hexdigest(), size=size, notes=notes)
    encoded = (json.dumps(payload, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    if len(encoded) > MAX_FEED:
        raise ValueError('update.json exceeds 64 KiB UTF-8; shorten release notes before publishing')
    return encoded


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--notes', type=Path)
    parser.add_argument('--badging', type=Path)
    parser.add_argument('--url')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--stable-tags', type=Path)
    parser.add_argument('--make-latest-output', type=Path)
    args = parser.parse_args()
    if bool(args.stable_tags) != bool(args.make_latest_output):
        parser.error('--stable-tags and --make-latest-output must be provided together')
    version_code(args.version)
    if args.stable_tags:
        latest = should_mark_latest(args.version, args.stable_tags.read_text(encoding='utf-8').splitlines())
        args.make_latest_output.write_text('true\n' if latest else 'false\n', encoding='utf-8')
    if args.apk:
        args.output.write_bytes(feed(args.apk, args.notes.read_text(encoding='utf-8'), args.version,
                                    args.url, args.badging.read_text(encoding='utf-8')))


if __name__ == '__main__':
    try:
        main()
    except ValueError as error:
        raise SystemExit(str(error)) from error
