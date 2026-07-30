---
name: project-target-domain-plugin-design
description: The core machinery has accumulated target-domain knowledge (web-service endpoint logic) as a patchwork; design note for pulling it behind a plug-in seam. User-raised 2026-07-30. READ BEFORE adding any new ecosystem-aware rule.
metadata: 
  node_type: memory
  type: project
  originSessionId: df54f45e-184f-4e0a-8554-38e62ac1303c
  modified: 2026-07-30T19:22:28.384Z
---

# Target-domain knowledge belongs behind a seam, not scattered through the core

**Raised by the user, 2026-07-30:** *"I want us to design a kind of modular framework for this kind of problems, to plug those things (in this case web service endpoint logic) into autobuilder. The core machinery shouldn't be a patchwork of all kinds of specific toycase code and puzzles."*

**Status: DESIGN NOT STARTED.** The user queued it behind `T4-DC` being field-proven. This note exists so the measurement and the seam analysis are not re-derived.

## This is the systemic form of three rules already enforced locally

`feedback-no-ecosystem-jargon-in-framework-code`, `feedback-no-hardcoded-ecosystem-data` and
`feedback-no-target-domain-in-framework-strings` each police one site at a time. Every individual
application was correct. Nobody ever asked what the total came to — and the total is the problem,
because a rule that is locally justified can still be globally wrong.

## The measurement (2026-07-30, `src/main/java/com/walpro/autobuilder`)

43 of 417 files mention HTTP/REST concepts. Excluding autobuilder's OWN web layer
(`controller/`, `client/` — autobuilder is itself a Spring application and its own API is not
the debt), the machinery that reasons about **the target** being a web service:

| file | hits | what it encodes |
|---|---|---|
| `goal/GoalPlannerService` | 66 | the central orchestrator reasons about endpoints |
| `core/build/BootGate` | 64 | HTTP probing and response reading |
| `analysis/structure/SpecEndpointBehaviourQuery` | 36 | spec prose → endpoint claims |
| `analysis/structure/EndpointSurfaceQuery` | 26 | built request-mapping discovery |
| `core/build/DeterministicProbeSynthesizer` | 24 | request synthesis (400/404/409 shapes) |
| `analysis/heuristic/MergeRegressionHeuristic` | 20 | annotation-loss rules |
| `protocol/{planner,evaluation,evolution,analysis}` contracts | 51 | prompt text about endpoints and statuses |
| `analysis/structure/WiringFactsAnalyzer` | 17 | annotation/status wiring rules |
| `core/build/BehaviouralCriterionParser` | 14 | verb + path + status parsing |

`GoalPlannerService` at 66 is the sharpest signal: the component that owns the goal tree should
not know what an endpoint is.

## The five seams

Every one of those files does one of five things. That is the plug-in interface.

1. **Requirement extraction** — read testable claims out of a specification.
   Today: `BehaviouralCriterionParser` (verb + path + status), `SpecEndpointBehaviourQuery`.
   A CLI target would read "exit status 2 when any row was skipped"; a library target, a method
   contract.
2. **Surface discovery** — what does the built artifact expose?
   Today: `EndpointSurfaceQuery` scanning request mappings.
   Elsewhere: exported commands, public API, plugin registrations.
3. **Probe synthesis** — construct an input that exercises a requirement.
   Today: `DeterministicProbeSynthesizer` (empty body → 400, unused id → 404, repeat → 409).
   Elsewhere: argv + stdin; a call with boundary arguments.
4. **Execution and observation** — run the artifact and read what happened.
   Today: `BootGate` (boot, HTTP call, status + body).
   Elsewhere: process exit code + stdout; an in-process invocation.
5. **Structural risk facts** — ecosystem-specific wiring smells that predict a wrong outcome.
   Today: `WiringFactsAnalyzer` (unmapped throws, validation surface, peer anomalies).
   Elsewhere: unhandled error paths, missing exit-code mapping.

## What must stay in the core

The goal tree, the cycle, agent selection and eligibility, merge and rollback, evaluation and
heuristic negotiation, the graph, signals, the closure edge. None of these need to know what the
target is. The `T4-DA` seed-coverage readout is a small proof that this is achievable — it
compares specification text against plan text and holds no opinion about endpoints at all.

## Design questions to settle FIRST (do not skip to code)

- **Is a plug-in interface actually cheaper than the patchwork, at this repo's size?** The
  honest failure mode is a five-interface abstraction with exactly one implementation forever.
  The evidence that would settle it: the `pollbox` second target already existed
  (`T4-CK`) — how much of the REST machinery did it reuse, and how much did it need to skip?
  Measure that before designing.
- **Where does the seam sit relative to the prompts?** Half the debt is prompt TEXT, not code.
  A profile that supplies code but leaves "endpoint" hardcoded in four contracts has moved
  nothing.
- **What is the minimum viable second implementation?** A design validated only against the
  domain it was extracted from is not validated. A CLI-target profile is the cheapest real test.
- **Migration order.** `GoalPlannerService` is the worst offender and the riskiest to touch;
  the analysis/build components are more separable. Extraction order should follow risk, not
  hit count.

## Standing rule until this is designed

Every new ecosystem-aware rule added in the meantime increases the eventual migration. When
adding one, note it here rather than only in its ticket, so the inventory stays current.
Today's `T4-DC` (catch-clause status mapping in `WiringFactsAnalyzer`) is such an addition.
