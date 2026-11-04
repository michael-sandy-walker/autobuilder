---
name: commit-notes-by-path
description: commit notes with `git commit <paths>` (or check the index first) -- a plain commit sweeps in whatever is staged
metadata:
  type: feedback
---

Commit notes with `git commit -m ... -- <paths>`, or run `git diff --cached --name-only` first. A bare `git commit` after `git add <note>` takes EVERYTHING staged.

**Why:** 2026-11-04, T4-EG slice 1: `git rm` of `RunResumeService` staged the deletion at once; the next notes commit (`54735241`, "docs(memory): ...") carried it, so that commit deletes a class the code still used and does not compile on its own. The code commit after it (`2acdfe7f`) is whole. Not rewritten (no branch ops without the user's word, [[feedback-no-branch-ops]]).

**How to apply:** with the standing notes-commit rule ([[no-automatic-commits]]) notes are committed often and mid-work, which is exactly when the index holds code. Commit notes by path, every time.
