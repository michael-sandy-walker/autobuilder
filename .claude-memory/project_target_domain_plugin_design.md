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

- **Is the seam in the RIGHT PLACE — does the boundary correspond to a real joint?** (User
  correction, 2026-07-30: *"It's not about 'cheaper', but about 'right'."* An earlier draft of
  this note led with cost. That is the wrong criterion and it produces the wrong design: a
  boundary chosen because it is economical will sit wherever the current code happens to be
  easiest to cut, which is exactly how the patchwork arose.) The question is whether "what the
  target IS" and "how the loop reasons" are genuinely separable concerns, and where the line
  between them actually falls. A seam in the right place stays right when a second
  implementation arrives; a seam chosen for convenience does not.
  **Evidence available:** the `pollbox` second target already existed (`T4-CK`) — reading which
  machinery it reused and which it had to bypass shows where the joint really is, and that is a
  fact about the domain rather than an argument about effort.
- **Where does the seam sit relative to the prompts?** Half the debt is prompt TEXT, not code.
  A profile that supplies code but leaves "endpoint" hardcoded in four contracts has moved
  nothing.
- **What is the minimum viable second implementation?** A design validated only against the
  domain it was extracted from is not validated. A CLI-target profile is the cheapest real test.
- **Migration order.** `GoalPlannerService` is the worst offender and the riskiest to touch;
  the analysis/build components are more separable. Extraction order should follow risk, not
  hit count.

## Evidence gathered 2026-07-31 — the seam has NEVER been exercised

The note asked for the pollbox evidence before designing. Read, and it says two things that
change the design rather than confirming it.

**1. Pollbox was a second DOMAIN, not a second KIND OF TARGET.** Its surface is
`POST /polls/{id}/votes`, `POST /polls/{id}/close`, `GET /polls/{id}/results` — a REST web
service, like the library seed. **Both targets this project has ever run are REST web services.**
So the five seams have never faced a CLI, a library, a parser or anything else, and the
"where does the joint actually fall" question has no empirical answer yet. Any boundary drawn
today is drawn from one shape of target seen twice.

**2. Domain transfer already fails ABOVE the code seam, at the prompt.** `T2-EO` (filed from the
first pollbox campaign) records that the planner regressed to structure-pinned subgoals —
*"Implement Poll model id generation strategy"*, *"Add ID field to Poll model with auto-increment
mechanism"* — and stagnated within ~5 minutes on a cluster of five such leaves. Its diagnosis is
the important part: the behavioural-criteria steering is **"prompt-level and goal-agnostic in
FORM, but its effectiveness was only ever validated on the one goal. On an unfamiliar domain the
model's decomposition falls back into structure-speak despite the directive."**

**What this means for the design.** A plug-in seam that cleanly separates code will not fix a
steering rule whose FORM is already domain-neutral and whose EFFECT is not. Half the debt this
note counted is prompt text, and pollbox shows prompt text is where transfer actually breaks —
even between two REST domains. **So the seam must be judged on whether it carries EFFECTIVENESS
across a boundary, not merely vocabulary.** A profile that supplies domain-neutral wording and
leaves the model decomposing structurally has moved the jargon and not the capability.

**Cheapest thing that would answer the open question:** a CLI-shaped target — exit codes, argv,
stdout — seeded and run once. It is the first real test of every one of the five seams, and until
it exists the boundary is a hypothesis. It also directly tests `T2-EO`'s finding on a target that
is not a web service at all.

## First real test case for the seam (2026-07-30)

A concrete mechanism was identified and deliberately deferred to this design rather than built:
**refuse to close a subgoal while a structural fact contradicts its criteria.** Run
`2026-07-30_21-25-43` closed *"Implement input validation for Book model"* as COMPLETED while
`WiringFactsAnalyzer` had said 27 times that the constraints were not enforced; the false closure
then caused the parent repair item to be abandoned (`T4-CU`). The check needs no boot and costs
milliseconds.

It was not built because matching the fact to the criterion requires knowing that
`BookController.createBook()` serves `POST /books` — more controller/endpoint/status coupling in
the core. **It is a good first exercise for the seam** because it needs two of the five
(requirement extraction, structural risk facts) to cooperate without the core learning what an
endpoint is. If the boundary is in the right place, this mechanism is expressible across it; if it
is not, this is where that shows up.

## Standing rule until this is designed

Every new ecosystem-aware rule added in the meantime increases the eventual migration. When
adding one, note it here rather than only in its ticket, so the inventory stays current.
Today's `T4-DC` (catch-clause status mapping in `WiringFactsAnalyzer`) is such an addition.

---

# 2026-08-25 — THE NON-REST TARGET EXISTS AND HAS RUN. The premise of this note needs correcting.

The note's blocker was that both targets ever run were REST web services, so "where does the joint
actually fall" had no empirical answer. It has one now.

**What was built** (`907f6ca`): `clitarget/bootstrap/seed.txt` — a delimited-text command-line tool
with no network surface, no long-running process, argv in and stdout/stderr out, and an exit status
per outcome. `scripts/smoke_cli.sh` scores it in the same shape as `smoke23.sh` and was calibrated
BOTH ways before use (12/12 on a reference implementation, 10/12 with two injected defects naming
exactly those checks). The harness needed no change — `T4-CK` had already added `PREFLIGHT_TARGET`
and `PREFLIGHT_SMOKE`, so only a non-REST target was ever missing.

## The result contradicts what this note predicts

**The loop built a fully correct CLI tool: 12/12 at twelve minutes, and still 12/12 at nineteen
merges with three classes.** No HTTP, no Spring, no endpoints anywhere. Measured by running the
artefact, not inferred.

| behaviour | on a CLI target |
|---|---|
| planner decomposition, criteria authoring, evolution, merge, compile gates | **transferred cleanly** — criteria read *"exits 5"*, *"prints nothing to stdout and exits 0"* |
| criterion → probe synthesis, boot/HTTP observation, `T4-CY` | **abstained honestly** — *"closure claim not checked — none of its 3 criterion(s) can be executed"*, and **zero** false findings all run |
| a handful of framework STRINGS | **actively wrong** |
| an outcome representation that is not an HTTP status | **missing** |

## So the joint is NOT where this note places it

**The note ranks `GoalPlannerService` at 66 HTTP hits as "the sharpest signal — the component that
owns the goal tree should not know what an endpoint is." The file is right and the reason is wrong.**
The planner's REASONING transferred; what leaked is a small set of hardcoded strings that assert a
REST world onto whatever target is loaded. The clearest, observed live on a target with no network
surface at all:

- `GoalPlannerService:3108` — `"[BUILD_FIX] Make the packaged application start and serve the specified endpoint"`, injected as a work item and then demanded at closure **18 times** in one run.
- `"Provide a runnable application entry point — the goal specification names HTTP"`.
- `"provide tests for every endpoint"`.

**Hit counts measured MENTIONS. This measures what actually blocks.** They disagree, and the run wins.

## The revised shape of the work — two things, not five seams

1. **One missing abstraction: an observable outcome that is not an HTTP status.**
   `BehaviouralCriterionParser` can express only `verb + path + status`, so *"exits 5"* has nowhere
   to live and everything downstream abstains for want of a representation. This is the same defect
   family as `T1-FW` (a requirement losing part of itself between being read and being exercised) —
   there a component of the input, here the entire outcome channel.
2. **A handful of leaked strings**, enumerable by grep, that describe the target as a web service.

That is materially cheaper than extracting five subsystems, and it is grounded in a run.

## ⚠ The caveat that keeps this honest

**The loop produced a correct artefact while flying BLIND.** Its verifier could execute none of the
criteria, so nothing checked the work in-loop: it got there on the model's competence plus compile
gates alone. That is encouraging about the loop and says nothing good about the verification chain.
A harder CLI specification — one where the obvious implementation is wrong — would likely separate
them, and that is the next experiment rather than a conclusion to draw now.

**Also unproven:** one CLI target, one run, one seed. The five-seam decomposition may still be right
for a target kind that is neither a service nor a command (a library, a parser). This corrects the
note from one shape seen twice to two shapes seen once each.

## The deepest leak is an ASSUMED LIVENESS MODEL, not a string (same run)

**41 closure blocks, every one *"the application exits at startup (exit 3)"*.** The artefact is a
command-line tool — exiting is what it does, and exit 3 is its SPECIFIED answer for an unknown
command. The boot gate ran it with no arguments, got the correct exit status, and called it a
startup failure.

**The framework assumes a correct artefact STARTS AND KEEPS RUNNING.** Right for a service,
backwards for a command. This is not in a string and cannot be extracted by moving code — it is in
what the gate ASKS. Any plug-in design that supplies "how to run the target" but leaves the
question "did it stay up?" in the core has moved nothing.

**Only a target that TERMINATES could have revealed this.** It is invisible to hit counts, invisible
to code reading, and invisible to a second REST target. That is the argument for the non-REST target
having been the right prerequisite all along.
