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
