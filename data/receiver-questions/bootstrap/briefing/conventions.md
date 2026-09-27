# Conventions of this codebase


## no internal ids in model facing text

The test is not whether a token is internal. It is whether the prompt defines the token before
using it, and whether the model is expected to send it back.
Why: the first version of this memory said *"ticket ids, gate slugs, signal names"* were all the
project's vocabulary and not the model's. The user challenged the third -- *"I don't know if the same
goes for signal names"* -- and the challenge was right: signal names are the counter-example, taught
in the contract and parsed back, which makes the internal-versus-external framing wrong. Written
without checking, in a memory sitting next to, which is the rule it broke. Behind the original
question: `closure_refused`'s `gate` field is NOT prompt-bound (behaviour events sink to the NDJSON
log and the dashboard's SSE stream, agents only emit, no contract reads one), but nothing enforced
that, and already relays evaluator rejection reasons verbatim into the evolution and planner
prompts -- so the route exists.

## no target domain in framework strings

When writing user-facing strings in autobuilder's framework code -- LLM prompt blocks, hint maps, error messages, log lines -- do not use class names from the target project being evolved. The autobuilder runs against many target projects; today it happens to be `firestarter` (a Spring Boot library / loan / member domain with `BookService`, `MemberController`, `LoanService`, etc.), but the framework code must stay domain-neutral.
Why: Caught 2026-05-25 -- `CompileDiagnosticPromptBlock.HINTS` shipped with `"BookService is instance-scoped -- inject it via constructor and call on the instance."` for the `non-static method ... static context` shape. The hint text was lifted verbatim from the run-a recorded run example that motivated . The user corrected it to `"class is instance-scoped -- inject it via constructor and call on the instance."` because (a) the hint fires for *any* static-context error, not just BookService calls, and (b) future target projects won't have a BookService at all. The over-specific hint would mislead the LLM into reasoning about the wrong class.

## no ecosystem jargon in framework code

Autobuilder is a generic self-improvement loop. Its framework-level code (registries, resolvers, agents, prompt blocks) must abstract over ecosystem specifics -- never name a specific framework or version in identifiers, comments, or framework-level strings. Ecosystem knowledge lives in *data tables* (registries, config files, yaml); framework methods talk in *abstract terms*.
Why: Identified by the user 2026-05-27 after seeing `WellKnownPackageRegistry.isSpringBoot2(pomXml)` and the inline rule "refuse rewrite if javax and jakarta are both present" in framework code. The general invariant being expressed -- "don't rewrite when the legacy prefix is still active on the classpath" -- has nothing to do with Spring Boot or javax/jakarta specifically. Naming the method `isSpringBoot2` (a) couples the framework to one ecosystem we happen to care about today, (b) makes the abstraction illegible to anyone unfamiliar with Spring Boot version history, and (c) implies the framework owns that knowledge when it should belong to the data table. This is the sibling rule to -- that one bans target-project domain (`BookService`, `com.example.demo`) from framework strings; this one bans ecosystem-version specifics (`SpringBoot2`, `javax`, `jakarta`, `Mockito`, `JUnit5`) from framework identifiers, comments, and strings.

## no hardcoded ecosystem data

Static rule, not dynamic. This memory is about *source-code text*: no ecosystem literals in framework `.java` files. It is *not* a rule against runtime ecosystem knowledge -- at runtime the loop absolutely needs to know that `when` resolves to `org.mockito.Mockito:mockito-core:5.11`. The point is that the loop should *discover* that fact (from project pom, source imports, Maven Central, ...), not *read* it from a `Map.entry(...)` baked into framework source. Discovery is the goal; enumeration is the debt.
Why: Identified by the user 2026-05-27 after shipped a `STATIC_METHOD_TO_PREFIX` map with ~30 Mockito/JUnit/AssertJ entries and a `mockito-core:5.7.0` literal in `WellKnownPackageRegistry`. Direct quote: *"That fixed code is doom. It will fight against error correction in the future."* The hardcoded tables will go wrong in three ways: (1) versions drift (project pulls in 5.11; registry says 5.7 -- pom conflict), (2) coverage is incomplete (registry has `assertThat` but not `assertThatThrownBy`, guard rejects a valid proposal), (3) tables become authoritative for the system's worldview even when the project itself has a different opinion expressed via its pom. The error-correction machinery (resolver chain, registry-failure marking, build-fix subgoals) operates on individual symbols, not on table contents -- so a wrong table row stays wrong across every future run.

## ecosystem gated heuristics

When a heuristic encodes a rule that only holds inside a specific ecosystem (e.g. `ManufacturedDependencyHeuristic`: "don't `new` a container-managed component" is a dependency-injection-container rule, meaningless in plain Java), it must be gated to code that actually involves that ecosystem -- never applied project-wide by default.
Why: the same code shape is a defect in one ecosystem and perfectly normal outside it (a plain-Java class calling `new` on a collaborator is just... construction). An ungated ecosystem rule silently punishes valid target projects the moment autobuilder evolves something non-Spring.

## relative thresholds

When designing a trigger, cap, or escalation threshold, do not introduce another fixed constant ("after 3 failures", "max 5 entries", "20 cycles"). Make it relative to the environment the run actually exhibits.
Why: fixed numbers encode a worldview the loop cannot correct (same failure mode as hardcoded ecosystem data, see; they are tuned to one run profile and silently mis-fire on others.

## string consumption path

Before rejecting prose as unstructured, ask who consumes it:
Why: on (2026-07-06) the code-path rule was mis-applied to a prompt-path task and drove two wrong turns -- first an invented `TEST_ONLY_CHANGE` enum re-derived from the diff, then a pivot to's ProblemContext/give-up-brake -- before the user re-anchored: "the rationale/feedback message of different agents communicated back to other agents so they know better what to do / not do."
