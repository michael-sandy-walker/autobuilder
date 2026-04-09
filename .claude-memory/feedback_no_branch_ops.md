---
name: No autonomous branch operations
description: Never run branch manipulation commands (merge, reset, rebase, delete, push) without explicit user instruction
type: feedback
---

Never run git branch manipulation commands autonomously — this includes `git merge`, `git rebase`, `git reset`, `git branch -d/-D`, `git push`, or any command that moves branch pointers.

**Why:** User explicitly corrected this after I offered to run `git merge --ff-only` + `git branch -d` without being asked. Branch ops are destructive/irreversible and must always be initiated by the user.

**How to apply:** When a branch operation is needed, describe the commands and wait for the user to explicitly say "do it" or run them manually. Do not pre-emptively execute even seemingly safe operations like fast-forward merges.
