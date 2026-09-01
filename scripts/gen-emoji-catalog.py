#!/usr/bin/env python3
"""Generate app/src/main/assets/emoji/emoji_en.tsv -- the reaction picker's emoji catalog -- from the
vendored Unicode `emoji-test.txt`.

The output is a committed, GENERATED file. Regenerate it with:

    python3 scripts/gen-emoji-catalog.py            # rewrite the asset from the vendored source
    python3 scripts/gen-emoji-catalog.py --check    # exit 1 if the committed asset is stale (CI)
    python3 scripts/gen-emoji-catalog.py --update 18.0   # re-vendor a newer Unicode emoji version, then regenerate

Source: third_party/unicode-emoji/emoji-test.txt (Unicode License v3, (c) Unicode, Inc.), pinned at the
version/date recorded in the file's own header and in third_party/unicode-emoji/PROVENANCE.md. This is a
developer tool run on a workstation; it never runs in the app, so the app's no-INTERNET-permission design is
unaffected. Nothing here is hard-coded to a Unicode version: the version, date and sha256 stamped into the
asset header are read off the vendored file at run time, so a bump touches no constants.

Transform (one emoji-test line -> zero or one catalog line):
  * `# group:` headers select the current group, mapped through GROUPS (Unicode's own order) to the small
    integer the app's `EmojiGroup` enum mirrors ordinal-for-ordinal. The `Component` group (bare skin-tone
    swatches, hair components) is skipped. Any OTHER unknown group is a hard error, so a future Unicode
    group fails loudly here and forces the Kotlin enum to grow in step -- never a silent drop;
  * keep only `fully-qualified` lines. `minimally-qualified` and `unqualified` forms are the same emoji
    minus a VS16 the keyboard should add, and `component` entries are not emoji on their own -- the picker
    must only ever emit the one canonical form, or two users' identical reactions tally as two chips;
  * the emoji string is rebuilt from the code-point column and asserted equal to the glyph column;
  * an entry carrying a skin-tone modifier (U+1F3FB..U+1F3FF) is flagged `tone = 1`: the browse grid hides
    those ~1,900 variants under their base, while search still reaches them by name;
  * file order is preserved (Unicode's curated order is what every picker shows); the emoji version column
    is NOT emitted -- nothing consumes it, `Paint.hasGlyph` is the real capability check on the device.

Output line format (tab-separated; tabs never occur in an emoji or a CLDR name, colons and commas do):

    <emoji>\\t<group 0-8>\\t<tone 0|1>\\t<CLDR short name>
"""

from __future__ import annotations

import argparse
import hashlib
import re
import sys
import urllib.request
from collections import Counter
from pathlib import Path
from typing import Iterable, NamedTuple

REPO_ROOT = Path(__file__).resolve().parent.parent
VENDOR_DIR = REPO_ROOT / "third_party" / "unicode-emoji"
SOURCE_TXT = VENDOR_DIR / "emoji-test.txt"
SOURCE_LICENSE = VENDOR_DIR / "LICENSE"
OUTPUT_TSV = REPO_ROOT / "app" / "src" / "main" / "assets" / "emoji" / "emoji_en.tsv"

# From Emoji 17.0 the data files live under the UCD version directory (the old /Public/emoji/<ver>/ path 404s).
SOURCE_URL_TEMPLATE = "https://www.unicode.org/Public/{version}.0/emoji/emoji-test.txt"
LICENSE_URL = "https://www.unicode.org/license.txt"

# Unicode's own group order. Index == app.getknit.knit.data.emoji.EmojiGroup ordinal -- grow both together.
GROUPS = [
    "Smileys & Emotion",
    "People & Body",
    "Animals & Nature",
    "Food & Drink",
    "Travel & Places",
    "Activities",
    "Objects",
    "Symbols",
    "Flags",
]
SKIPPED_GROUPS = {"Component"}
SKIN_TONES = range(0x1F3FB, 0x1F3FF + 1)

# `1F44D 1F3FB ; fully-qualified # 👍🏻 E1.0 thumbs up: light skin tone`
LINE = re.compile(r"^([0-9A-F]{4,6}(?: [0-9A-F]{4,6})*)\s*;\s*([a-z-]+)\s*#\s*(\S+)\s+E(\d+\.\d+)\s+(.+?)\s*$")
VERSION = re.compile(r"^# Version:\s*(\S+)")
DATE = re.compile(r"^# Date:\s*(\d{4}-\d{2}-\d{2})")


class Entry(NamedTuple):
    emoji: str
    group: int
    tone: int
    name: str


class Source(NamedTuple):
    version: str
    date: str
    sha256: str


def parse(lines: Iterable[str]) -> tuple[list[Entry], Counter]:
    """Parse emoji-test lines into catalog entries (see the module docstring for the rules)."""
    group: int | None = None
    skipping = False
    entries: list[Entry] = []
    stats: Counter = Counter()
    for raw in lines:
        line = raw.rstrip("\r\n")
        if line.startswith("# group:"):
            name = line[len("# group:"):].strip()
            if name in SKIPPED_GROUPS:
                skipping, group = True, None
            elif name in GROUPS:
                skipping, group = False, GROUPS.index(name)
            else:
                raise ValueError(f"unknown emoji group {name!r}: add it to GROUPS here and to EmojiGroup in the app")
            continue
        if not line or line.startswith("#"):
            continue
        m = LINE.match(line)
        if not m:
            raise ValueError(f"unparseable emoji-test line: {line!r}")
        cps, status, glyph, _version, name = m.groups()
        stats[status] += 1
        if status != "fully-qualified":
            continue
        if skipping or group is None:
            stats["skipped-group"] += 1
            continue
        emoji = "".join(chr(int(cp, 16)) for cp in cps.split())
        if emoji != glyph:
            raise ValueError(f"code points {cps} do not rebuild the glyph column for {name!r}")
        tone = int(any(ord(c) in SKIN_TONES for c in emoji))
        entries.append(Entry(emoji, group, tone, name))
    return entries, stats


def source_info(text: str) -> Source:
    version = next((m.group(1) for m in map(VERSION.match, text.splitlines()) if m), None)
    date = next((m.group(1) for m in map(DATE.match, text.splitlines()) if m), None)
    if not version or not date:
        raise ValueError("emoji-test.txt has no '# Version:' / '# Date:' header")
    return Source(version, date, hashlib.sha256(text.encode("utf-8")).hexdigest())


def header(src: Source) -> str:
    groups = ", ".join(f"{i} {g}" for i, g in enumerate(GROUPS))
    return f"""\
# Emoji catalog for the reaction picker -- one fully-qualified RGI emoji per line, in Unicode's own order.
# Columns (tab-separated): emoji, group id, skin-tone variant (0|1), CLDR short name (English).
# Group ids: {groups}
# (mirrors app.getknit.knit.data.emoji.EmojiGroup ordinal-for-ordinal). Blank lines and '#' lines are ignored.
#
# GENERATED FILE -- DO NOT EDIT BY HAND. Regenerate with:  python3 scripts/gen-emoji-catalog.py
#
# Source:  Unicode emoji-test.txt -- {SOURCE_URL_TEMPLATE.format(version=src.version)}
# Version: Emoji {src.version} ({src.date})  sha256 {src.sha256}
# License: Unicode License v3 (c) Unicode, Inc. -- full text: app/src/main/assets/emoji/README.md
#
# Transform: keep only `fully-qualified` lines (drops minimally-/unqualified forms and `component`
# swatches); skip the Component group; flag entries carrying a skin-tone modifier (U+1F3FB..U+1F3FF);
# preserve file order. Full detail: scripts/gen-emoji-catalog.py.
"""


def render(text: str) -> tuple[str, list[Entry], Counter]:
    entries, stats = parse(text.splitlines())
    body = "\n".join(f"{e.emoji}\t{e.group}\t{e.tone}\t{e.name}" for e in entries)
    return header(source_info(text)) + "\n" + body + "\n", entries, stats


def report(entries: list[Entry], stats: Counter) -> None:
    tones = sum(e.tone for e in entries)
    per_group = Counter(GROUPS[e.group] for e in entries)
    print(f"wrote {len(entries)} emoji -> {OUTPUT_TSV.relative_to(REPO_ROOT)}", file=sys.stderr)
    print(
        f"  statuses: " + "  ".join(f"{k}={v}" for k, v in sorted(stats.items())) + f"  |  tone variants kept: {tones}",
        file=sys.stderr,
    )
    print("  per group: " + "  ".join(f"{g}={per_group[g]}" for g in GROUPS), file=sys.stderr)


def update(version: str) -> None:
    VENDOR_DIR.mkdir(parents=True, exist_ok=True)
    for url, dest in ((SOURCE_URL_TEMPLATE.format(version=version), SOURCE_TXT), (LICENSE_URL, SOURCE_LICENSE)):
        print(f"fetching {url}", file=sys.stderr)
        with urllib.request.urlopen(url) as resp:  # noqa: S310 - fixed https hosts
            dest.write_bytes(resp.read())
    src = source_info(SOURCE_TXT.read_text(encoding="utf-8"))
    print(
        f"vendored Emoji {src.version} ({src.date}) sha256 {src.sha256}\n"
        f"  now update third_party/unicode-emoji/PROVENANCE.md (version, date, sha256, retrieved) and the\n"
        f"  version line in app/src/main/assets/emoji/README.md + THIRD-PARTY-NOTICES.md",
        file=sys.stderr,
    )


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--check", action="store_true", help="verify the committed asset matches the vendored source; exit 1 on drift")
    ap.add_argument("--update", metavar="VERSION", help="download emoji-test.txt for this Unicode emoji version (e.g. 18.0) and regenerate")
    args = ap.parse_args()
    if args.update:
        update(args.update)
    if not SOURCE_TXT.exists():
        print(f"error: missing {SOURCE_TXT} (see third_party/unicode-emoji/PROVENANCE.md)", file=sys.stderr)
        return 1
    rendered, entries, stats = render(SOURCE_TXT.read_text(encoding="utf-8"))
    if args.check:
        current = OUTPUT_TSV.read_text(encoding="utf-8") if OUTPUT_TSV.exists() else ""
        if current != rendered:
            print(f"error: {OUTPUT_TSV.relative_to(REPO_ROOT)} is stale -- run: python3 scripts/gen-emoji-catalog.py", file=sys.stderr)
            return 1
        print(f"ok: {OUTPUT_TSV.relative_to(REPO_ROOT)} matches the vendored source ({len(entries)} emoji)", file=sys.stderr)
        return 0
    OUTPUT_TSV.parent.mkdir(parents=True, exist_ok=True)
    with OUTPUT_TSV.open("w", encoding="utf-8", newline="\n") as f:
        f.write(rendered)
    report(entries, stats)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
