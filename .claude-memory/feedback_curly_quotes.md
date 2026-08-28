---
name: feedback-curly-quotes
description: Stray non-Latin characters and curly quotes in source — now enforced by scripts/charset_guard.py via a PostToolUse hook, not by remembering
metadata:
  type: feedback
---

Fix curly/smart quotes in Java immediately with sed, no confirmation. As of
2026-08-28 this rule is **mechanically enforced** and no longer depends on my
noticing: `scripts/charset_guard.py` runs as a `PostToolUse` hook on
`Edit|Write|MultiEdit` (project `.claude/settings.json`) and exits 2 — a blocking
error — the moment an offending character is written.

**Why:** the rule existed only as a note, so nothing would have caught a violation
if one had landed. It was prompted by a CJK ideograph I produced in a hand-written
code sketch. Worth recording precisely: that character never reached a file — `git
grep` over every tracked text file finds none and no commit ever added one — so the
guard was built against a near miss, not damage. This is
[[feedback-instrument-needs-an-actuator]] applied to my own output.

**How to apply:**
- Don't re-derive the policy — run `python3 scripts/charset_guard.py` (whole tree)
  or let the hook fire. It is a **blocklist**, not an allowlist, and that was decided
  by inventory: Java sources legitimately use 4684 box-drawing characters, 3429 em
  dashes, 572 arrows, plus maths symbols and check marks. Banning "non-ASCII" would
  fire on all of it and get switched off. Greek is deliberately allowed (`Δ`, 29 uses).
- Banned: Cyrillic, Hebrew, Arabic, Devanagari, Thai, CJK, Hiragana, Katakana,
  Hangul, fullwidth forms. Add ranges there rather than inventing a second check
  ([[feedback-one-invariant-one-home]]).
- Curly quotes are flagged **only in Java CODE**, never in comments — the codebase
  has 16 in comments on purpose. The failing case is a curly that has REPLACED an
  ASCII delimiter, which does not compile. Detecting that needs the state scanner in
  the script, not a regex: a regex over well-formed literals misses malformed ones
  precisely because they are malformed.
- `jq` is **not installed** on this machine. Hook commands must use `python3`.
