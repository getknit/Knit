#!/usr/bin/env python3
"""Mint and index architecture decision records.

    python3 scripts/adr.py new "<title>" [--topics a,b,c]
    python3 scripts/adr.py index [--check]

Decisions live one-per-file in `.agents/memory/decisions/`, and
`.agents/memory/decisions.md` is a **generated** router over them.

WHY THE SPLIT. Every ADR used to be a `## NNN.` section appended to the tail of one
3,600-line file, and the number was chosen by reading the current maximum. This repo
is worked in four-plus git worktrees at once, so two branches adding a decision on the
same afternoon collided twice over: once on the number (both read "067", both took
"068") and once on the append itself, in a file where the conflict hunk is thousands of
lines from anything that explains it. Nobody picks a number now, so there is nothing to
collide; the router's only churn is one table row, and `.gitattributes` marks it
`merge=union` so even that auto-resolves.

TWO ID FORMS COEXIST, PERMANENTLY. ADRs 001-067 predate the split and keep their
sequence numbers, because `ADR NNN` is cited ~815 times across Kotlin comments, `docs/`
and — immutably — commit messages that F-Droid pins. Everything new is date-bucketed
with a random suffix (`2026-08.k3f9`). Cite whichever form an ADR carries; never
renumber an old one.

PYTHON, NOT GRADLE. This has nothing to do with the build, and a Gradle task would drag
a configuration phase (and the JDK-21 daemon) into "write down a decision". Python 3 is
already a build-adjacent dependency here — see `scripts/gen-profanity-list.py`. Stdlib
only, including the frontmatter parser: it is five known keys, and `rules/coding.md`
asks for a compatibility check before any new dependency, which a 30-line parser is
plainly the cheaper side of.
"""

from __future__ import annotations

import argparse
import re
import secrets
import sys
from dataclasses import dataclass
from datetime import date as Date
from pathlib import Path

# Repo-relative, so the script works from any cwd inside any worktree.
DECISIONS_DIR = ".agents/memory/decisions"
ROUTER_PATH = ".agents/memory/decisions.md"

# Suffix alphabet: Crockford-ish base32 minus the characters that are misread aloud or
# mistyped out of a code comment — 0/O, 1/l/I. 31^4 ~= 923k, so two worktrees minting on
# the same day collide with probability ~1e-6 (and `new` refuses to overwrite anyway).
SUFFIX_ALPHABET = "23456789abcdefghjkmnpqrstuvwxyz"
SUFFIX_LENGTH = 4

# `001`-`067`: the pre-split sequence. Never minted again.
LEGACY_ID = re.compile(r"^\d{3}$")
# `2026-08.k3f9`: what `adr.py new` mints.
DATED_ID = re.compile(rf"^\d{{4}}-\d{{2}}\.[{SUFFIX_ALPHABET}]{{{SUFFIX_LENGTH}}}$")

FRONTMATTER_KEYS = ("id", "slug", "title", "date", "topics")


class AdrError(Exception):
    """A malformed ADR or a refused mint. Reported as `✗ <message>`, never a traceback."""


@dataclass(frozen=True)
class Adr:
    id: str
    slug: str
    title: str
    date: str  # ISO, YYYY-MM-DD
    topics: list[str]
    file: str  # basename, e.g. `060-the-fast-planes-carry-a.md`


def find_repo_root(start: Path | None = None) -> Path:
    """Walk up to the directory holding `settings.gradle.kts`.

    Not `Path.cwd()`: running this from `app/` would otherwise read a non-existent
    decisions directory and report "0 decisions", which for a generator means happily
    truncating the router instead of failing.
    """
    directory = (start or Path.cwd()).resolve()
    for candidate in (directory, *directory.parents):
        if (candidate / "settings.gradle.kts").is_file():
            return candidate
    raise AdrError(f"no settings.gradle.kts above {directory}")


def is_legacy_id(adr_id: str) -> bool:
    return bool(LEGACY_ID.match(adr_id))


def is_valid_id(adr_id: str) -> bool:
    return bool(LEGACY_ID.match(adr_id) or DATED_ID.match(adr_id))


def mint_id(today: Date) -> str:
    """Mint a fresh id. `today` is injected so callers and tests agree on the bucket."""
    suffix = "".join(secrets.choice(SUFFIX_ALPHABET) for _ in range(SUFFIX_LENGTH))
    return f"{today:%Y-%m}.{suffix}"


def file_name_for(adr_id: str, slug: str) -> str:
    """The filename an id + slug must have.

    The `.` in a dated id becomes `-`, so the name has exactly one extension:
    `2026-08.k3f9-foo.md` reads to plenty of tooling as a file called `2026-08` with a
    very long extension.
    """
    return f"{adr_id.replace('.', '-')}-{slug}.md"


def slug_for(title: str) -> str:
    """Kebab slug from a title: first clause, <=6 words, ASCII only."""
    first_clause = re.split(r"[:;—–,(]", title)[0]
    words = re.sub(r"[^a-z0-9]+", "-", first_clause.replace("`", "").lower())
    return "-".join(w for w in words.strip("-").split("-") if w)[:80].strip("-")


def citation(adr_id: str) -> str:
    """How an ADR is cited in prose and in code comments."""
    return f"ADR {adr_id}"


def _parse_frontmatter(text: str, file: str) -> dict[str, str]:
    """Parse the five-key frontmatter block.

    Deliberately strict: a typo in a key name has to be an error here, or it becomes a
    silently missing router row.
    """
    if not text.startswith("---\n"):
        raise AdrError(f"{file}: no frontmatter block")
    end = text.find("\n---\n", 3)
    if end == -1:
        raise AdrError(f"{file}: unterminated frontmatter")

    fields: dict[str, str] = {}
    for line in text[4:end].split("\n"):
        if not line.strip():
            continue
        key, colon, value = line.partition(":")
        if not colon:
            raise AdrError(f'{file}: unparseable frontmatter line "{line}"')
        fields[key.strip()] = value.strip()
    return fields


def _unquote(value: str) -> str:
    """Strip the quoting `new` applies to ids and titles (titles carry `:` and `"`)."""
    if not value.startswith('"'):
        return value
    return value[1:-1].replace('\\"', '"').replace("\\\\", "\\")


def _parse_topics(value: str, file: str) -> list[str]:
    if not (value.startswith("[") and value.endswith("]")):
        raise AdrError(f'{file}: topics must be an inline list, got "{value}"')
    return [t.strip() for t in value[1:-1].split(",") if t.strip()]


def read_adrs(repo_root: Path) -> list[Adr]:
    """Read and validate every ADR file, sorted the way the router presents them:
    the legacy sequence first, then dated ids chronologically.

    Every check here exists because its absence would produce a *plausible* router
    rather than a loud failure — a duplicate id silently shadows a decision, and a
    heading that disagrees with its frontmatter breaks the grep anchor that all ~815
    `ADR NNN` citations in the tree rely on.
    """
    directory = repo_root / DECISIONS_DIR
    if not directory.is_dir():
        raise AdrError(f"{DECISIONS_DIR}/ does not exist")

    adrs: list[Adr] = []
    seen: dict[str, str] = {}

    for path in sorted(directory.glob("*.md")):
        file = path.name
        text = path.read_text(encoding="utf-8")
        fields = _parse_frontmatter(text, file)

        for key in FRONTMATTER_KEYS:
            if key not in fields:
                raise AdrError(f'{file}: missing frontmatter key "{key}"')

        adr_id = _unquote(fields["id"])
        title = _unquote(fields["title"])
        slug, date = fields["slug"], fields["date"]

        if not is_valid_id(adr_id):
            raise AdrError(f'{file}: "{adr_id}" is not a valid ADR id')

        if adr_id in seen:
            raise AdrError(f'duplicate ADR id "{adr_id}": {seen[adr_id]} and {file}')
        seen[adr_id] = file

        expected = file_name_for(adr_id, slug)
        if file != expected:
            raise AdrError(f"{file}: id + slug say the filename should be {expected}")

        if not re.match(r"^\d{4}-\d{2}-\d{2}$", date):
            raise AdrError(f'{file}: date "{date}" is not YYYY-MM-DD')

        # The grep anchor. Every `ADR NNN` citation in the tree resolves by finding this
        # line, so it must match the frontmatter exactly.
        heading = f"# {citation(adr_id)} — {title}"
        if f"\n{heading}\n" not in text:
            raise AdrError(f'{file}: expected heading "{heading}"')

        adrs.append(
            Adr(adr_id, slug, title, date, _parse_topics(fields["topics"], file), file)
        )

    return sorted(adrs, key=lambda a: (not is_legacy_id(a.id), a.id))


# --------------------------------------------------------------------------- new

SCAFFOLD = """---
id: "{id}"
slug: {slug}
title: "{title}"
date: {date}
topics: [{topics}]
---

# {citation} — {title_plain}

Status: Accepted ({date})

**What was observed**, and what it was mistaken for. Numbers where there are numbers.

**What changed**, and what the alternative was — including the one a reader would
reach for first, and why it does not work here.

**What it costs**, what it does not cover, and the trap the next person will hit. Name
the test, gate or invariant that keeps this true.
"""


def command_new(args: argparse.Namespace) -> int:
    title = " ".join(args.title).strip()
    if not title:
        raise AdrError('usage: python3 scripts/adr.py new "<title>" [--topics a,b,c]')

    slug = slug_for(title)
    if not slug:
        raise AdrError(f'could not derive a slug from "{title}"')

    today = Date.today()
    adr_id = mint_id(today)
    file = file_name_for(adr_id, slug)
    path = find_repo_root() / DECISIONS_DIR / file

    # Only reachable on a ~1e-6 suffix collision, but a silent overwrite here destroys
    # another worktree's decision.
    if path.exists():
        raise AdrError(f"{file} already exists — rerun to mint a different suffix")

    topics = [t.strip() for t in (args.topics or "").split(",") if t.strip()]
    path.write_text(
        SCAFFOLD.format(
            id=adr_id,
            slug=slug,
            title=title.replace('"', '\\"'),
            title_plain=title,
            date=f"{today:%Y-%m-%d}",
            topics=", ".join(topics),
            citation=citation(adr_id),
        ),
        encoding="utf-8",
    )

    print(f"✓ {DECISIONS_DIR}/{file}")
    print(f"  cite as:  ({citation(adr_id)})")
    print("  then run: python3 scripts/adr.py index")
    return 0


# ------------------------------------------------------------------------- index

PREAMBLE = """# Architecture decision records

The load-bearing decisions and *why* they hold, so future work stays consistent. One
file each, in [`decisions/`](decisions/) — this page is the router. Open the file whose
row matches; don't guess from the title alone. Supersede by writing a new ADR and
changing the old one's `Status:` — never by deleting it.

**This file is generated. Do not edit it by hand.** Add a decision with
`python3 scripts/adr.py new "<title>" --topics a,b`, write the body, then
`python3 scripts/adr.py index`.

**Two id forms, and both are permanent.** ADRs `001`-`067` predate the split into
one-file-per-decision and keep their sequence numbers, because `ADR NNN` is cited ~815
times in code comments, `docs/` and commit messages — including commits F-Droid pins,
which cannot be rewritten. Everything since is `YYYY-MM.suffix` (`ADR 2026-08.k3f9`),
minted at random, because the sequence number was itself the merge conflict: parallel
worktrees all read the same "next number" and all took it. Cite whichever form an ADR
carries; never renumber an old one."""


def _row(adr: Adr) -> str:
    # No title carries a `|` today, but one would silently break the table.
    title = adr.title.replace("|", "\\|")
    folder = DECISIONS_DIR.rsplit("/", 1)[-1]
    return f"| [{adr.id}]({folder}/{adr.file}) | {title} | {', '.join(adr.topics)} |"


def render(adrs: list[Adr]) -> str:
    return "\n".join(
        [
            PREAMBLE,
            "",
            f"{len(adrs)} decisions.",
            "",
            "| ADR | Decision | Topics |",
            "| --- | --- | --- |",
            *(_row(a) for a in adrs),
            "",
        ]
    )


def command_index(args: argparse.Namespace) -> int:
    repo_root = find_repo_root()
    adrs = read_adrs(repo_root)
    rendered = render(adrs)

    path = repo_root / ROUTER_PATH
    current = path.read_text(encoding="utf-8") if path.exists() else None

    if current == rendered:
        print(f"✓ {ROUTER_PATH} is up to date ({len(adrs)} decisions)")
        return 0

    if args.check:
        print(
            f"✗ {ROUTER_PATH} is stale — run `python3 scripts/adr.py index`",
            file=sys.stderr,
        )
        return 1

    path.write_text(rendered, encoding="utf-8")
    print(f"✓ wrote {ROUTER_PATH} ({len(adrs)} decisions)")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(prog="adr.py", description=__doc__.split("\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)

    new = sub.add_parser("new", help="scaffold a decision with a collision-free id")
    new.add_argument("title", nargs="+", help="the decision, as a sentence")
    new.add_argument("--topics", default="", help="comma-separated router topics")
    new.set_defaults(func=command_new)

    index = sub.add_parser("index", help=f"regenerate {ROUTER_PATH}")
    index.add_argument(
        "--check", action="store_true", help="fail instead of writing (CI / hooks)"
    )
    index.set_defaults(func=command_index)

    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except AdrError as error:
        print(f"✗ {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
