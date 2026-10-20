#!/usr/bin/env python3
"""Collect release POM declarations and original bundled notices. Network: public POMs only.

./gradlew -I scripts/dependency_inventory.init.gradle :app:exportAuditRuntime
python3 scripts/dependency_notices.py app/build/audit/runtime-artifacts.json
"""
import argparse
import concurrent.futures
import hashlib
import io
import json
from pathlib import Path
import re
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from html.parser import HTMLParser

ROOT = Path(__file__).resolve().parents[1]
NOTICE_NAME = re.compile(
    r"(?:^|/)(?:(?:LICENSE|NOTICE|COPYING|COPYRIGHT)(?:[._-][^/]*)?|AL2\.0|LGPL2\.1)$", re.I
)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def pom_url(coordinate):
    group, artifact, version = coordinate.split(":")
    google = group.startswith(("androidx.", "com.google.android.gms", "com.google.mlkit",
                               "com.google.android.datatransport", "com.google.android.odml", "com.google.firebase"))
    base = "https://dl.google.com/dl/android/maven2/" if google else "https://repo.maven.apache.org/maven2/"
    return f"{base}{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.pom"


def declarations(coordinate, cache, depth=0):
    if depth > 8:
        raise ValueError("POM parent chain too deep")
    url = pom_url(coordinate)
    path = cache / f"{coordinate}.pom"
    if not path.exists():
        with urllib.request.urlopen(url, timeout=30) as response:
            path.write_bytes(response.read())
    data = path.read_bytes()
    root = ET.fromstring(data)
    for node in root.iter():
        node.tag = node.tag.split("}")[-1]
    licenses = [{child.tag: child.text for child in node} for node in root.findall("licenses/license")]
    result = {"coordinate": coordinate, "pom": url, "pomSha256": digest(data), "licenses": licenses}
    parent = root.find("parent")
    if not licenses and parent is not None:
        parent_coord = ":".join(parent.findtext(tag) for tag in ("groupId", "artifactId", "version"))
        inherited = declarations(parent_coord, cache, depth + 1)
        result["licenses"] = inherited["licenses"]
        result["inheritedFrom"] = inherited
    return result


def google_notices(index_data, text_data):
    """Google offsets are byte offsets, not Unicode character positions."""
    result = []
    for title, span in json.loads(index_data).items():
        start, length = span["start"], span["length"]
        if not isinstance(start, int) or not isinstance(length, int) or start < 0 or length <= 0 or start + length > len(text_data):
            raise ValueError(f"Invalid notice bounds: {title}")
        result.append((title, text_data[start:start + length].decode("utf-8")))
    return result


class PlainHtml(HTMLParser):
    def __init__(self):
        super().__init__()
        self.parts = []
    def handle_data(self, data):
        self.parts.append(data)
    def handle_starttag(self, tag, attrs):
        if tag in ("p", "br", "div", "h1", "h2"):
            self.parts.append("\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifacts", type=Path)
    parser.add_argument("--pom-cache", type=Path, default=ROOT / "app/build/audit/poms")
    args = parser.parse_args()
    args.pom_cache.mkdir(parents=True, exist_ok=True)
    artifacts = json.loads(args.artifacts.read_text())
    coordinates = sorted({row["coordinate"] for row in artifacts})
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
        metadata = list(executor.map(lambda c: declarations(c, args.pom_cache), coordinates))
    notices = {}
    artifact_evidence = []

    def add(title, text, origin):
        if not text.strip():
            return
        key = digest(text.encode())
        entry = notices.setdefault(key, {"titles": set(), "origins": set(), "text": text})
        entry["titles"].add(title)
        entry["origins"].add(origin)

    def scan(z, coordinate, prefix=""):
        names = z.namelist()
        if "third_party_licenses.json" in names:
            for title, text in google_notices(z.read("third_party_licenses.json"), z.read("third_party_licenses.txt")):
                add(title, text, coordinate + "!" + prefix + "third_party_licenses.txt")
        for name in names:
            if NOTICE_NAME.search(name) and not name.endswith(".class"):
                add(coordinate + " — " + name.rsplit("/", 1)[-1], z.read(name).decode("utf-8"), coordinate + "!" + prefix + name)
        for name in names:
            if name == "classes.jar" or name.startswith("libs/") and name.endswith(".jar"):
                with zipfile.ZipFile(io.BytesIO(z.read(name))) as child:
                    scan(child, coordinate, prefix + name + "!")

    for artifact in artifacts:
        file = Path(artifact["file"])
        artifact_evidence.append({"coordinate": artifact["coordinate"], "file": file.name,
                                  "sha256": digest(file.read_bytes())})
        with zipfile.ZipFile(file) as z:
            scan(z, artifact["coordinate"])
    upstream = ROOT / "docs/security-licenses/upstream"
    for source in json.loads((upstream / "sources.json").read_text()):
        text = (upstream / source["file"]).read_text()
        if source["file"].endswith(".html"):
            html = PlainHtml()
            html.feed(text)
            text = "".join(html.parts)
        add(source.get("title", source["file"]), text, source["url"])

    dest = ROOT / "app/src/main/assets/legal/third-party"
    dest.mkdir(parents=True, exist_ok=True)
    index = []
    for key, entry in sorted(notices.items(), key=lambda kv: (sorted(kv[1]["titles"])[0].lower(), kv[0])):
        titles = sorted(entry["titles"])
        title = min(titles, key=lambda value: (len(value), value))
        if len(titles) > 1:
            title += f" (+{len(titles) - 1} attributions)"
        origins = sorted(entry["origins"])
        # Full hash filenames preserve distinct versions of notices with the same title.
        (dest / f"{key}.txt").write_text("\n".join(titles) + "\n\nSource(s):\n" + "\n".join(origins) + "\n\n" + entry["text"])
        index.append({"title": title, "path": f"legal/third-party/{key}.txt", "sha256": key, "origins": origins})
    expected = {entry["path"].split("/")[-1] for entry in index}
    for stale in dest.glob("*.txt"):
        if stale.name not in expected:
            stale.unlink()
    (dest / "index.json").write_text(json.dumps(index, indent=2, ensure_ascii=False) + "\n")
    report = ROOT / "docs/security-licenses"
    report.mkdir(exist_ok=True)
    (report / "runtime-inventory.json").write_text(json.dumps({"dependencies": metadata, "artifacts": artifact_evidence}, indent=2) + "\n")
    lines = ["# Release dependency inventory", "", f"{len(metadata)} resolved Maven coordinates. Generated from releaseRuntimeClasspath by scripts/dependency_notices.py.",
             "POM declarations are obtained from official Maven repositories, including parent declarations.",
             "Original bundled notices (including Google native components) and supplemental upstream LICENSE/NOTICE texts are available offline in Settings → Third-party notices.",
             "This inventory is not a declaration that all third-party terms are Apache/MIT or a legal approval. See docs/security-licenses/README.md for scope and remaining decisions.", "",
             "| Artifact | Declared license | Source |", "| --- | --- | --- |"]
    for row in metadata:
        licenses = "; ".join(f"{x.get('name', 'Unnamed')} ({x.get('url', '')})" for x in row["licenses"]) or "Not declared — review required"
        lines.append(f"| `{row['coordinate']}` | {licenses} | [POM]({row['pom']}) |")
    text = "\n".join(lines) + "\n"
    (ROOT / "docs/dependencies.md").write_text(text)
    (ROOT / "app/src/main/assets/legal/dependencies.txt").write_text(text)
    print(f"{len(metadata)} dependencies, {len(index)} distinct notice texts, {sum(len(x['text'].encode()) for x in notices.values())} notice bytes")


if __name__ == "__main__":
    main()
