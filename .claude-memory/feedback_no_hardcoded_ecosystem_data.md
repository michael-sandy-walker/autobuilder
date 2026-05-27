---
name: feedback-no-hardcoded-ecosystem-data
description: "Hardcoded ecosystem data (package → GAV maps, simple-name → prefix maps, version literals) in registries is technical debt — autobuilder needs discovery (project-pom parse, Maven Central, classpath scan, recipe catalogues), not enumeration"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: b1f99192-77ac-48ce-ba2a-ec6e18f838f6
---

Hardcoded ecosystem data tables — `Map.entry("when", "org.mockito.Mockito")`, `new Gav("org.mockito", "mockito-core", "5.7.0")`, simple-name → FQN-prefix maps — are technical debt. Autobuilder is a self-improvement loop; baking-in version literals and enumeration tables freezes a snapshot of the world into the framework, and the system has no path to correct that snapshot when it drifts.

**Why:** Identified by the user 2026-05-27 after T2-DU shipped a `STATIC_METHOD_TO_PREFIX` map with ~30 Mockito/JUnit/AssertJ entries and a `mockito-core:5.7.0` literal in `WellKnownPackageRegistry`. Direct quote: *"That fixed code is doom. It will fight against error correction in the future."* The hardcoded tables will go wrong in three ways: (1) versions drift (project pulls in 5.11; registry says 5.7 — pom conflict), (2) coverage is incomplete (registry has `assertThat` but not `assertThatThrownBy`, guard rejects a valid proposal), (3) tables become authoritative for the system's worldview even when the project itself has a different opinion expressed via its pom. The error-correction machinery (resolver chain, registry-failure marking, build-fix subgoals) operates on individual symbols, not on table contents — so a wrong table row stays wrong across every future run.

**How to apply:**

1. **When tempted to add a row to a hardcoded map, file the discovery ticket instead.** Tactical row-adds are acceptable as a short-term unblock, but every such add is also a signal that the discovery mechanism is missing. Don't ship the row without also filing the follow-up.
2. **Discovery sources, ranked.** (a) The project's own `pom.xml` dependency tree — it already tells us which artifacts are on the classpath and at what versions; reverse-index by package. (b) The project's own source — `import static org.mockito.Mockito.when;` already says which symbols come from which source. (c) Maven Central REST API — `/solrsearch/select?q=fc:<class>&rows=N` resolves a missing FQN to candidate artifacts. (d) OpenRewrite recipe catalogue — for *migrations* (javax → jakarta), recipes already enumerate the equivalences; consume them, don't re-enumerate.
3. **Tactical tables remain as a fallback only.** Discovery first, table second, LLM lookup third. A row in the table should explain *why it exists despite discovery* — usually "discovery is too slow at proposal time and this is the hot path".
4. **Apply to both kinds of data.** This rule covers *both* package → GAV maps *and* simple-name → prefix maps *and* version literals. All three are equally brittle. The rule [[feedback-no-ecosystem-jargon-in-framework-code]] bans ecosystem names from framework *identifiers*; this rule bans ecosystem *data literals* from framework code.

Recurring violation pattern to watch: a behaviour-log cascade names N missing symbols → tactical fix adds N rows → cascade closes for that specific N → next cascade names different M symbols → repeat. The cascade *shape* repeats because the discovery mechanism is missing, not because the row count is too low. Each row-add buys one run; the discovery ticket buys all future runs.
