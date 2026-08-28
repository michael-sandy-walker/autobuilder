4---
name: feedback-verify-diff-is-substantive
description: "Before reporting a change as an achievement, read the diff and prove it changes behaviour — type-threading that preserves behaviour is not progress."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 2195ba13-260a-4a1a-8cd2-96d513d6e58e
---

On 2026-06-07 I "shipped" T4-AN-7 and reported it as a meaningful capability ship. The user pushed back: *"I don't see anything meaningful in the code."* They were right — the diff was ~90% renaming a resolver SPI param from `Kind` to `Classification` and then immediately calling `classification.kind()`, plus dispatching on `category()` which on that code path is a pure function of `kind` (`defaultCategoryFor`). Behaviour was identical; the one "new logic" branch (`prefer missingSymbol()`) was dead code because the adaptive classifier isn't wired to that path. I had framed it as meaningful **twice** before actually reading the diff critically.

**Why:** "behaviour-preserving migration", "latent capability", and "completes the partition" can all be true sentences while the change accomplishes nothing observable. A green test suite confirms *nothing broke*, not that *anything was achieved*. Decoupling claims especially need checking: renaming a type at a call boundary does not decouple anything if both sides still read the same underlying field.

**How to apply:** Before reporting a change as an achievement, run `git diff` and ask: *what input now produces a different output than before?* If the honest answer is "none on any path that actually runs," say that plainly — it's churn or a no-op refactor, not a ship. Trace whether new fields/branches are ever populated/reached on the real execution path (e.g. is the adaptive classifier actually wired in, or just the static one?) before claiming they do work. Pairs with [[feedback-no-defensive-recast]] and [[feedback-verify-population-before-claim]].

**Reinforcing instance, 2026-08-28 (T4-DF).** A new compile-fix resolver had 12 green unit tests and worked on nothing: driving the real production classes over real `javac` stderr produced **NO PROPOSAL**. The fixtures encoded `location: class org.lwjgl.opengl.GL11` — the shape the Maven log happened to carry — while `javac` reports an IMPORTED type by simple name (`location: class GL11`), so the code read a null package and abstained silently. **A fixture encodes an assumed INPUT shape, and a suite built from fixtures can only re-confirm the assumption.** Extend the question above to the input side: *does the real producer actually emit what my fixture claims?* — and prefer one end-to-end run over any number of fixture tests for answering it. Same discipline as [[feedback-llm-probe-realistic-context]], one layer down.
