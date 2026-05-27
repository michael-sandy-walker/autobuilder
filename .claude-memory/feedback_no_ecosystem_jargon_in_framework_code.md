---
name: feedback-no-ecosystem-jargon-in-framework-code
description: "Autobuilder framework code (identifiers, comments, error messages) must abstract over ecosystem-version specifics — never bake `SpringBoot2`, `javax`, `jakarta`, or any framework/version name into method names, types, or framework-level strings"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 574059be-9aee-4669-bc3c-282473a6bda2
---

Autobuilder is a generic self-improvement loop. Its framework-level code (registries, resolvers, agents, prompt blocks) must abstract over ecosystem specifics — never name a specific framework or version in identifiers, comments, or framework-level strings. Ecosystem knowledge lives in *data tables* (registries, config files, yaml); framework methods talk in *abstract terms*.

**Why:** Identified by the user 2026-05-27 after seeing `WellKnownPackageRegistry.isSpringBoot2(pomXml)` and the inline rule "refuse rewrite if javax and jakarta are both present" in framework code. The general invariant being expressed — "don't rewrite when the legacy prefix is still active on the classpath" — has nothing to do with Spring Boot or javax/jakarta specifically. Naming the method `isSpringBoot2` (a) couples the framework to one ecosystem we happen to care about today, (b) makes the abstraction illegible to anyone unfamiliar with Spring Boot version history, and (c) implies the framework owns that knowledge when it should belong to the data table. This is the sibling rule to [[feedback-no-target-domain-in-framework-strings]] — that one bans target-project domain (`BookService`, `com.example.demo`) from framework strings; this one bans ecosystem-version specifics (`SpringBoot2`, `javax`, `jakarta`, `Mockito`, `JUnit5`) from framework identifiers, comments, and strings.

**How to apply:** When introducing a framework-level abstraction that handles ecosystem-specific knowledge:

1. **Identifiers must be ecosystem-agnostic.** `isSpringBoot2(pomXml)` → `legacyPrefixActive(manifest, prefixPair)`. `replacedBy(missingPackage, pomXml)` → `replacementFor(missingPackage, manifest)`. Method names describe *what* the rule expresses, not *which ecosystem* it applies to.
2. **Ecosystem literals live in the registry/config, never in code.** `javax.validation` → `jakarta.validation` is one row in `PACKAGE_REPLACED_BY`. The framework methods that consume that row don't know it's about javax or jakarta — they know it's about a `LegacyPrefix → ModernPrefix` mapping.
3. **Comments and Javadoc abstract over ecosystem.** "Refuse rewrite for SB2 projects (javax is still on the classpath)" → "Refuse rewrite when the manifest indicates the legacy prefix is still active on the classpath; only the data table knows which prefix pair this applies to."
4. **Tests and roadmap narratives may name specifics.** A `@DisplayName("javax.validation rewrite on SB3")` test is fine — the test exercises a concrete ecosystem fixture. A roadmap entry citing `behaviour-2026-05-24_22-21-21.ndjson` and the LoanService incident is fine. The rule applies to *production* identifiers/comments/strings only.

Recurring violation pattern to watch: when a tactical fix lands for a single high-frequency ecosystem case (here: javax→jakarta), the registry table gains a row *and* the framework code grows a method named after that case. Step 1 is correct; step 2 is the bug. The registry row alone should be sufficient — the framework code should never know which row is firing.
