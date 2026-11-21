#!/usr/bin/env python3
"""
Release notes for a new version: the entries of the [Unreleased] section of CHANGELOG.md that the changelog did not have at
the previous release.

The release workflow patches CHANGELOG.md after a release on a branch whose pull request is not always merged, so
[Unreleased] can still hold entries that earlier releases already shipped. Comparing with the changelog as it was at the
previous release tag keeps each release's notes to what is new, whether or not the changelog was patched.

    scripts/release-notes.py [--since <git ref>] [--changelog CHANGELOG.md] [--output notes.md]

Without --since, the newest version tag that isn't on HEAD is used; with no earlier tag, all of [Unreleased] is printed.
"""
import argparse
import re
import subprocess
import sys

VERSION_TAG = re.compile(r"^v?\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$")


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True, check=False).stdout


def previous_tag():
    on_head = set(git("tag", "--points-at", "HEAD").split())
    for tag in git("tag", "--sort=-v:refname").split():
        if VERSION_TAG.match(tag) and tag not in on_head:
            return tag
    return None


def unreleased(text):
    """[(heading, [entry, ...])] of the [Unreleased] section; an entry is a top-level bullet with its continuation lines."""
    sections, heading, entry, inside = [], None, None, False
    for line in text.splitlines():
        if line.startswith("## "):
            if inside:
                break
            inside = line.strip().lower().startswith("## [unreleased]")
            continue
        if not inside:
            continue
        if line.startswith("### "):
            heading = line[4:].strip()
            sections.append((heading, []))
            entry = None
        elif line.startswith("- ") or line.startswith("* "):
            if not sections:
                sections.append((None, []))
            entry = [line]
            sections[-1][1].append(entry)
        elif entry is not None and (line.startswith(" ") or line.startswith("\t")) and line.strip():
            entry.append(line)
        elif not line.strip():
            entry = None
    return [(h, ["\n".join(e) for e in entries]) for h, entries in sections]


def key(entry):
    """An entry's identity: its text without bullet and whitespace differences."""
    return re.sub(r"\s+", " ", re.sub(r"^[-*]\s+", "", entry)).strip().lower()


def known_entries(text):
    """Every bullet anywhere in a changelog (all sections, released or not)."""
    keys, current = set(), None
    for line in text.splitlines() + [""]:
        if line.startswith("- ") or line.startswith("* ") or line.startswith("#") or not line.strip():
            if current:
                keys.add(key("\n".join(current)))
            current = [line] if line.startswith(("- ", "* ")) else None
        elif current is not None:
            current.append(line)
    return keys


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--since")
    parser.add_argument("--changelog", default="CHANGELOG.md")
    parser.add_argument("--output")
    args = parser.parse_args()

    with open(args.changelog, encoding="utf-8") as f:
        current = f.read()
    since = args.since or previous_tag()
    old = git("show", f"{since}:{args.changelog}") if since else ""
    known = known_entries(old)

    out = []
    for heading, entries in unreleased(current):
        new = [e for e in entries if key(e) not in known]
        if not new:
            continue
        if heading:
            out.append(f"### {heading}\n")
        out.extend(new)
        out.append("")
    notes = "\n".join(out).strip() or "No user-visible changes."
    notes += "\n"

    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(notes)
    else:
        sys.stdout.write(notes)


if __name__ == "__main__":
    main()
