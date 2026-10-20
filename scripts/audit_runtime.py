#!/usr/bin/env python3
"""Query OSV for public Maven coordinates exported by dependency_inventory.init.gradle.

Exit 0: no returned matches. Exit 1: advisories found. Errors/incomplete responses fail.
This does not scan native components, build tools, source code or the Android OS.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('artifacts', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    rows = json.loads(args.artifacts.read_text())
    coordinates = sorted({r['coordinate'] for r in rows})
    if not coordinates:
        raise ValueError('No dependencies exported')
    results = []
    for offset in range(0, len(coordinates), 100):
        batch = coordinates[offset:offset + 100]
        queries = []
        for coordinate in batch:
            group, artifact, version = coordinate.split(':')
            queries.append({'package': {'ecosystem': 'Maven', 'name': f'{group}:{artifact}'}, 'version': version})
        request = urllib.request.Request('https://api.osv.dev/v1/querybatch',
                                         data=json.dumps({'queries': queries}).encode(),
                                         headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=60) as response:
            data = json.load(response)
        if len(data['results']) != len(batch):
            raise ValueError('Incomplete OSV result; do not treat this as a clean audit')
        for coordinate, result in zip(batch, data['results']):
            if result.get('next_page_token'):
                raise ValueError(f'Paginated OSV result for {coordinate}; review all pages before reporting')
            results.append({'coordinate': coordinate, **result})
    report = {'queriedAt': datetime.now(timezone.utc).isoformat(),
              'scope': 'releaseRuntimeClasspath Maven coordinates; excludes toolchain, OS and nested native components',
              'results': results}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    findings = [r for r in results if r.get('vulns')]
    print(f'{len(coordinates)} coordinates queried; {len(findings)} with returned advisories')
    for row in findings:
        print(row['coordinate'], ', '.join(v['id'] for v in row['vulns']))
    return 1 if findings else 0


if __name__ == '__main__':
    raise SystemExit(main())
