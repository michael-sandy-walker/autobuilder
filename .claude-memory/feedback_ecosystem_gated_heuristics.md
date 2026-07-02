---
name: feedback-ecosystem-gated-heuristics
description: "User steer 2026-07-02: ecosystem-specific heuristics (like the Spring-rule ManufacturedDependencyHeuristic) must only apply to code that actually uses that ecosystem — self-gate on the ecosystem's own markers, and treat the marker table as T4-BM discovery data."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 644ecb1e-2601-4fc0-baa0-9f5b02a95e5e
---

When a heuristic encodes a rule that only holds inside a specific ecosystem (e.g. `ManufacturedDependencyHeuristic`: "don't `new` a container-managed component" is a dependency-injection-container rule, meaningless in plain Java), it must be **gated to code that actually involves that ecosystem** — never applied project-wide by default.

**Why:** the same code shape is a defect in one ecosystem and perfectly normal outside it (a plain-Java class calling `new` on a collaborator is just... construction). An ungated ecosystem rule silently punishes valid target projects the moment autobuilder evolves something non-Spring.

**How to apply:**
- Make the gate structural, not configured: detect the ecosystem's own markers in the target code (annotations, build-config dependencies) and no-op when absent. `ManufacturedDependencyHeuristic` does this by requiring the container-marker annotation on BOTH sides of the detection; a marker-free project can never produce an opinion. Add a test asserting the no-markers case forms no opinion.
- Respect the ecosystem's own idioms inside the gate: e.g. `@Configuration` classes are the sanctioned manufacture site (`@Bean` factory methods), excluded as the flagging side.
- The marker list itself is hardcoded ecosystem data ([[feedback-no-hardcoded-ecosystem-data]]) — keep it as ONE clearly-marked table and file/point to the T4-BM discovery follow-up (derive markers from the project's pom/classpath instead of literals).
- When reviewing or adding any heuristic, ask: "does this rule hold for every target project, or only inside some ecosystem?" If the latter, it needs the gate.
