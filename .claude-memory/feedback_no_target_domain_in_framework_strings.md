---
name: feedback-no-target-domain-in-framework-strings
description: "Don't hard-code target-project class names (BookService, MemberController, LoanService, etc.) into autobuilder's framework-level production strings — hints, prompt blocks, error messages, log lines. Target-domain names belong in tests and fixtures only."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 77ecc664-cc74-4221-984d-96915254e1e3
---

When writing user-facing strings in autobuilder's framework code — LLM prompt blocks, hint maps, error messages, log lines — **do not use class names from the target project being evolved**. The autobuilder runs against many target projects; today it happens to be `firestarter` (a Spring Boot library / loan / member domain with `BookService`, `MemberController`, `LoanService`, etc.), but the framework code must stay domain-neutral.

**Why:** Caught 2026-05-25 — `CompileDiagnosticPromptBlock.HINTS` shipped with `"BookService is instance-scoped — inject it via constructor and call on the instance."` for the `non-static method ... static context` shape. The hint text was lifted verbatim from the run-2026-05-23_03-45-48 example that motivated T2-DT. The user corrected it to `"class is instance-scoped — inject it via constructor and call on the instance."` because (a) the hint fires for *any* static-context error, not just BookService calls, and (b) future target projects won't have a BookService at all. The over-specific hint would mislead the LLM into reasoning about the wrong class.

**How to apply:**
- Hints, prompt blocks, behaviour-event messages, log strings: use generic placeholders (`class`, `method`, `field`) or no concrete name at all. Let the structured diagnostic (file/line/column/message) carry the specifics.
- Test fixtures, ticket bodies in the roadmap, run-analysis narratives: target-domain names are fine and often necessary (they're the concrete observation).
- When porting a real-run example into production code (motivating-example → hint text), strip the target-domain names in the port, even if it makes the hint slightly less vivid. Concreteness in the test fixture; abstraction in the production string.
- The same rule applies to package prefixes — `com.example.library`, `com.example.demo`, etc. are firestarter-specific. Use them in tests; never bake them into framework code.

Trigger: any new entry in a static map keyed on a diagnostic shape (hint maps, classification tables, normalisation tables) where the value text mentions a class or package by name. Re-read it from a fresh-eyes perspective: if `BookService` were replaced by `OrderRepository` (or any name from a different target project), would the text still make sense? If not, abstract it.

**2026-09-21 — the rule extends past STRINGS to any target-shaped CONSTANT, and the analysis scripts are where it hides.** A target's own **check count** is the same kind of fact as its class names, and `scripts/failure_family.py` had firestarter's baked into a regex: `outcome()` parsed the smoke score as `(\d+)/23`. Clitarget scores out of 19 and an earlier yardstick out of 12, so their rows matched nothing, read as 0, and **23 of 96 archived runs that scored above zero were classified as build/boot failures — seven of them perfect scores.** The function existed for no other purpose than to prevent exactly that, and its docstring said so.

**Two things make this worth carrying beyond the one fix:**
- **A guard is only proven for the target its tests were written on.** All 21 of that file's tests used `23/23`. The protection was real and covered one of three targets, while 24 clitarget runs accumulated in the archive unnoticed. When this project gained a second and third target, every existing assertion silently narrowed from "the rule" to "the rule, on firestarter".
- **Analysis and reporting code is not exempt because it is not production.** It decides what we believe about the run population, so a target-specific constant there manufactures findings rather than misleading a model. The same pinned denominator had ALREADY been recorded as a defect in the baseline this very ticket (`T6-M`) was filed about, and it came back one axis over: that one dropped rows with no number, this one dropped rows with a different number.

**How to apply:** before trusting any cross-run number, ask what the denominator MEANS in each row ([[feedback-baseline-before-accuracy]]'s fourth lie). And when adding a test for a target-sensitive rule, add the second target's case in the same change — a single-target test suite reads exactly like a passing one.

## 2026-09-23 -- the user's reminder: the current targets are TOY CASES

Said mid-build of the dependency-version work (T1-GF): *"Please keep in mind, that the current testable files are just toy cases."* firestarter, clitarget and flightsim are probes of the loop, not the population it is for, so a mechanism that works on them has proven only that it works on them.

**Why:** tuning to the toy set is invisible from inside it -- every green result on firestarter looks like progress, and a rule shaped by Spring or by one library passes all of it.

**How to apply:** phrase every new rule over a CONVENTION (a group that prefixes a package, the shape of a version string, same-group alignment), never over a library or framework name; test it on INVENTED coordinates (`org.acme.kit`); and check it on at least one non-firestarter case before claiming it generalises (T1-GF was checked on LWJGL and JUnit as well as Spring).
