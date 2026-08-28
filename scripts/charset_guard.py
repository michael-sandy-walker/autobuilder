#!/usr/bin/env python3
"""Refuse source characters from scripts this project does not write in.

Why a blocklist and not an allowlist: the codebase deliberately uses a wide
typographic set — 4684 box-drawing, 3429 em dashes, arrows, maths, check marks.
Banning "non-ASCII" would fire on all of it and be turned off within a day.
What actually goes wrong is a generation slip dropping a character from a
script nobody here types in (a CJK ideograph mid-comment), and that is a small,
precise set with no legitimate use in this repo.

Curly quotes are checked separately and only inside Java string literals and
character literals, where they stop being cosmetic and break the build.

Exit 1 on any hit, naming file, line, column and the offending character.
"""
import re
import sys
import unicodedata
from pathlib import Path

# Scripts with no legitimate use here. Greek is deliberately absent: Δ appears
# 29 times as a maths symbol and is intended.
BANNED_RANGES = [
    (0x0400, 0x04FF, "Cyrillic"),
    (0x0530, 0x058F, "Armenian"),
    (0x0590, 0x05FF, "Hebrew"),
    (0x0600, 0x06FF, "Arabic"),
    (0x0900, 0x097F, "Devanagari"),
    (0x0E00, 0x0E7F, "Thai"),
    (0x3000, 0x303F, "CJK punctuation"),
    (0x3040, 0x309F, "Hiragana"),
    (0x30A0, 0x30FF, "Katakana"),
    (0x3400, 0x4DBF, "CJK ideograph (ext A)"),
    (0x4E00, 0x9FFF, "CJK ideograph"),
    (0xAC00, 0xD7AF, "Hangul"),
    (0xF900, 0xFAFF, "CJK compatibility ideograph"),
    (0xFF00, 0xFFEF, "fullwidth/halfwidth form"),
]

CURLY = {"‘": "'", "’": "'", "“": '"', "”": '"'}

SOURCE_SUFFIXES = {".java", ".py", ".sh", ".xml", ".yml", ".yaml", ".json", ".sql"}

def java_code_spans(text):
    """Yield (offset, char) for characters in Java CODE — outside comments and literals.

    A curly quote inside a comment is cosmetic and the codebase has 16 of them on
    purpose. A curly quote in CODE has usually REPLACED an ASCII delimiter, and that
    does not compile — which is the case worth failing a build over. Telling the two
    apart needs the scanner; a regex over well-formed literals misses the broken ones
    precisely because they are broken.
    """
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            i = text.find("\n", i)
            if i < 0:
                return
        elif c == "/" and nxt == "*":
            end = text.find("*/", i + 2)
            i = n if end < 0 else end + 2
        elif text.startswith('"""', i):
            end = text.find('"""', i + 3)
            i = n if end < 0 else end + 3
        elif c == '"':
            i += 1
            while i < n and text[i] != '"':
                i += 2 if text[i] == "\\" else 1
            i += 1
        elif c == "'":
            i += 1
            while i < n and text[i] != "'":
                i += 2 if text[i] == "\\" else 1
            i += 1
        else:
            yield i, c
            i += 1


def banned_script(ch):
    cp = ord(ch)
    for lo, hi, name in BANNED_RANGES:
        if lo <= cp <= hi:
            return name
    return None


def check(path):
    problems = []
    try:
        text = path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        return problems  # binary or unreadable: not our business
    for lineno, line in enumerate(text.splitlines(), 1):
        for col, ch in enumerate(line, 1):
            script = banned_script(ch)
            if script:
                try:
                    name = unicodedata.name(ch)
                except ValueError:
                    name = "unnamed"
                problems.append(
                    f"{path}:{lineno}:{col}: {script} character {ch!r} "
                    f"(U+{ord(ch):04X} {name}) — no script outside Latin belongs in source"
                )
    if path.suffix == ".java":
        starts = [0]
        for line in text.splitlines(True):
            starts.append(starts[-1] + len(line))
        for off, ch in java_code_spans(text):
            if ch in CURLY:
                idx = max(i for i, st in enumerate(starts) if st <= off)
                lineno, col = idx + 1, off - starts[idx] + 1
                problems.append(
                    f"{path}:{lineno}:{col}: curly {ch!r} in Java CODE — use {CURLY[ch]!r}; "
                    f"a curly delimiter does not compile"
                )
    return problems


def hook_mode():
    """Read a Claude Code PostToolUse payload on stdin and check the edited file.

    Exit 2 on a hit: that is the blocking code, so the writer is told at once
    instead of the character surviving to a commit. Anything unreadable or
    unrelated exits 0 — a guard that fails closed on its own plumbing would be
    turned off within a day, which is the failure mode this is trying to avoid.
    """
    import json
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        return 0
    target = (payload.get("tool_response") or {}).get("filePath") \
        or (payload.get("tool_input") or {}).get("file_path")
    if not target:
        return 0
    path = Path(target)
    if not path.is_file() or path.suffix not in SOURCE_SUFFIXES:
        return 0
    problems = check(path)
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(
            "\ncharset guard: rewrite these characters before continuing. "
            "A CJK or Cyrillic character in source is a generation slip, and a curly "
            "quote in Java CODE does not compile.",
            file=sys.stderr,
        )
        return 2
    return 0


def main(argv):
    if "--hook" in argv:
        return hook_mode()
    paths = [Path(a) for a in argv[1:]]
    if not paths:
        paths = [p for p in Path("src").rglob("*") if p.suffix in SOURCE_SUFFIXES]
    problems = []
    for p in paths:
        if p.is_file() and p.suffix in SOURCE_SUFFIXES:
            problems.extend(check(p))
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(f"\ncharset guard: {len(problems)} problem(s)", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
