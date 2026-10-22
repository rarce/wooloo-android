#!/usr/bin/env python3
"""Turns release notes (Markdown) into Google Play's "What's new" text.

Play shows plain text and accepts at most 500 characters per language, so links, emphasis and
headings are reduced to their text and longer notes are cut on a word boundary with "…".
"""
import argparse
import re
from pathlib import Path

PLAY_LIMIT = 500
FALLBACK = 'Bug fixes and improvements.'


def plain_text(markdown):
    text = re.sub(r'<!--.*?-->', '', markdown, flags=re.S)
    lines = []
    for line in text.splitlines():
        line = line.rstrip()
        if re.match(r'^\s*(```|~~~)', line):
            continue
        # GitHub's generated notes: the compare link and "by @user in <PR URL>" add nothing on Play.
        if re.match(r'^\s*\**Full Changelog\**:', line):
            continue
        line = re.sub(r' by @[\w-]+(?:\[bot\])? in https?://\S+', '', line)
        line = re.sub(r'^\s*#{1,6}\s+', '', line)
        line = re.sub(r'^\s*>\s?', '', line)
        line = re.sub(r'^(\s*)[-*+]\s+', r'\1• ', line)
        line = re.sub(r'!\[([^\]]*)\]\([^)]*\)', r'\1', line)
        line = re.sub(r'\[([^\]]+)\]\([^)]*\)', r'\1', line)
        line = re.sub(r'<[^>]+>', '', line)
        line = re.sub(r'`([^`]*)`', r'\1', line)
        line = re.sub(r'(\*\*|__)(.+?)\1', r'\2', line)
        line = re.sub(r'(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])', r'\1', line)
        line = re.sub(r'(?<!\w)_(?!\s)(.+?)(?<!\s)_(?!\w)', r'\1', line)
        if re.fullmatch(r'\s*([-*_]\s*){3,}', line):
            continue
        lines.append(line)
    text = '\n'.join(lines)
    text = re.sub(r'\n{3,}', '\n\n', text).strip()
    return text


def truncate(text, limit=PLAY_LIMIT):
    if len(text) <= limit:
        return text
    cut = text[:limit - 1]
    # Prefer the last whitespace, so no word is split; a single huge word is cut anyway.
    boundary = max(cut.rfind(' '), cut.rfind('\n'))
    if boundary > 0:
        cut = cut[:boundary]
    return cut.rstrip(' \n\t.,;:-•') + '…'


def play_notes(markdown, limit=PLAY_LIMIT):
    return truncate(plain_text(markdown), limit) or FALLBACK


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, help='Markdown release notes; missing or empty uses a generic line')
    parser.add_argument('--output', type=Path, required=True, help='e.g. whatsnew/whatsnew-en-US')
    args = parser.parse_args()
    markdown = args.input.read_text(encoding='utf-8') if args.input and args.input.is_file() else ''
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(play_notes(markdown), encoding='utf-8')


if __name__ == '__main__':
    main()
