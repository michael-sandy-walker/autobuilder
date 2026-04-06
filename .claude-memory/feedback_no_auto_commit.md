---
name: No automatic commits
description: Never commit automatically — always wait for explicit user instruction
type: feedback
---

Never commit code automatically. Always wait for an explicit instruction from the user before running `git commit`.

**Why:** User gave a permanent standing order after I auto-committed T2-AU without being asked.

**How to apply:** After finishing an implementation, show the changes and stop. Do not run `git add` or `git commit` unless the user explicitly asks (e.g. "commit this", "go ahead and commit").
