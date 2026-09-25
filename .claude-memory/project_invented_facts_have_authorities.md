---
name: project-invented-facts-have-authorities
description: "2026-08-28 session lens, extended 2026-09-11 with a sharper variant -- an authority the loop CROPPED and then trusted, and 2026-09-23 with a third -- an authority CONSULTED FIVE TIMES and asked someone else's question (a 150-line cap cut the method the requirement was about; the same model answers correctly on the full file, proven by controlled ablation, so ask what it actually SAW before concluding it cannot read): every defect found tonight is the loop INVENTING a fact whose cheap, consultable AUTHORITY sat unconsulted — and the session's own method errors were the same shape one level up. Triage question: 'what is the authority for this fact, and who consulted it?'"
metadata: 
  node_type: memory
  type: project
  originSessionId: 08a0057e-31eb-4f9d-a1db-8717c69fbf03
  modified: 2026-09-23T02:30:00.000Z
---

# Invented facts have authorities

One session (2026-08-28) produced two built mechanisms, four filed tickets, and a fourth false
completion — and they are all ONE finding. In every case the loop **invented a fact** and acted on
it, while a **cheap, deterministic authority** for that fact sat unconsulted:

| invented fact | authority that existed | outcome | ticket |
|---|---|---|---|
| which type declares `glBindVertexArray` | the resolved classpath (open the class files) | dependency re-added, ~20 wasted cycles | `T4-DF` ✅ built |
| that `gl4java:2.4.0-rc-2` exists | the repository (`versionsOf`) | 26 unresolvable coordinates, run died unfulfilled | `T4-DH` ✅ built |
| that `com.sun.opengl` is the GL API | classpath ∪ repository | placeholder renderer, silence — never even an error | `T4-DL` filed |
| that all files belong under `model/` | the seed + the project's own tree | correct file rejected at 0.42 for obeying it | `T4-DJ` filed |
| what a pom should contain | the target's own declarations | a flight simulator made a Spring Boot app | `T4-DI` filed |
| that the goal is FULFILLED | **the seed itself** | fourth false completion, by ledger dissolution | `T4-DK` filed |
| what `countPhysicalLines` returns | **the method body, on disk** | sixth false completion, 17/19 | `T2-FI` filed |

The last row is the family's apex: "accounted for" is an invented completion-fact, and the seed —
the one authority the whole system exists to serve — is the only party never consulted at the
closure edge.

## 2026-09-11 — a sharper variant: the loop CROPPED its own authority, then trusted the answer

The rows above are authorities that sat unconsulted. This one was consulted and **cut off
mid-sentence**. `CriteriaVerificationService` crops every file to `MAX_LINES_PER_FILE = 150`
before asking the model whether the code satisfies a requirement. `Main.java` is 215 lines, and
`countPhysicalLines` -- the method computing the number the requirement is about -- lives at 200.
The model saw the call site, never the body.

**Measured as a controlled ablation, not inferred:** the run's own closure prompt, replayed against
the same local model, 5 samples per arm at temperature 0, one variable changed.

| prompt | answer | correct? |
|---|---|---|
| cropped to 150 lines (what the run sent) | `line 2: expected 3 fields, found 2` | no -- that is the SPEC's value |
| the whole file | `line 1: expected 3 fields, found 2` | yes -- that is what the artifact prints |

**So the triage question needs a second half.** Not only *"what is the authority for this fact, and
who consulted it?"* but *"and did the consultation actually reach it?"* A cropped authority reads
exactly like a consulted one at the receiving end: the answer comes back confident, formatted
correctly, and wrong. The model filled the hole from the requirement text, which is the most
plausible thing it could have done and the least detectable.

**And it retires a tempting hypothesis.** "The local model cannot verify code by reading" is the
explanation this project has reached for repeatedly. On this instance it is FALSE: shown the whole
file, the model reported the real behaviour 5 times out of 5. The ceiling here was input, not
capability -- which does not make reading reliable in general, and does not demote the execution
probe, but does mean a wrong verdict deserves "what did it actually see?" before "it cannot do it".

**The pattern generalizes the existing lenses rather than replacing them.**
[[project-grounding-night-insight]] said belief→verification recurs at every scale;
[[feedback-no-hardcoded-ecosystem-data]] said discovery beats enumeration. This adds the triage
edge: **for any wrong behaviour, first ask "what fact was invented, what is its authority, and how
cheap is a consult?"** Tonight every authority turned out to be deterministic, local or one HTTP
call away, and model-free. The expensive-looking repairs (T4-DF's structural supertype rule,
T4-DH's availability enum) were each ~1 day precisely because the authority was cheap.

**The same shape governed the session's own method errors — one level up.** Three counts inflated
or deflated by reading LOG RENDERINGS instead of event authorities (89x one pom bootstrap, ~140x
nine import proposals, "six coordinates" that were 26 across two Maven wordings); two fixture
suites green against inputs the real producers never emit (javac's two `location:` forms, Maven's
two resolution wordings). Every claim from replaying the real artifact held; every claim from
regexing its rendering needed correction. The assistant inventing a count has the same cure as the
loop inventing a coordinate: **consult the producer, not the residue.**

**🔴 THE AUTHORITY THE LOOP LACKS IS WEB-GATED, MEASURED 2026-08-28.** The mechanism that
distinguishes a spec-derived requirement from an invented one — `SpecTraceability`, whose AUTHORED
stamps correctly keep the completion gate from blocking on requirements the loop made up — is
switched on by `hasTraceabilitySignal(spec)`, which is literally *"does this specification declare
HTTP endpoints"*. **On any non-REST target the discrimination never runs**, so every invented
criterion carries full weight, including an invented file path. Run 2026-08-28_05-27-49 carries zero
binding stamps, and its false completion turns on exactly that: `com/flightsim/ui/CockpitHUD.java`
and the `model/` constraint that contradicted it were BOTH inventions, and nothing could tell.

**So the table's bottom three rows are one capability, not three tickets.** `T4-DJ` (which
requirement wins), `T4-DL` (which API exists), and `T4-DK`'s open half (is the seed met) all need
the same thing: **a domain-free way to ask what the specification actually states.** Today that
question has a web-shaped answer or none. This is `T4-DD`'s requirement-extraction seam, reached
from three directions at once — and per [[feedback-one-invariant-one-home]] it should be built once.

**What this predicts (falsifiable):** new flightsim-class failures will keep arriving as long as
repairs are per-invention-class — each run this session found a NEW class (members → coordinates →
API surface → conventions → completion). The accumulating fix is not more resolvers; it is making
"consult the authority" a step the loop itself owns — which is [[project-introspection-as-north-star]]
stated concretely, and what `T4-DD`'s seams would give a home to.

**Standing questions this leaves open:** does the grounding belong at proposal time (steer before
writing), probe time (reject after), or closure time (audit before claiming)? Tonight produced one
of each: T4-DL wants proposal-time, T4-DF/T4-DH act at probe time, T4-DK wants closure time. If a
future slice can only afford one, the false-completion record says closure time is where the
damage concentrates.


---

# 2026-09-23 — THE THIRD VARIANT: the authority was consulted, repeatedly, and asked the WRONG QUESTION

The note's two shapes are *unconsulted* (2026-08-28) and *cropped, then trusted* (2026-09-11).
T2-FL found a third, and it is the most expensive of the three because every instrument reads
healthy while it happens.

**Run `2026-09-22_15-59-33` ended at its deadline with a 23/23 artifact and four ACTIVE leaves the
loop could have retired.** The authority for "are these leaves done?" is the boot gate, and it was
not unconsulted and not cropped: over the last 54 minutes it ran **five times**, on the per-cycle
path, against the tree on disk, reporting `probed=9 skipped=6 … contradicted=0` each time.

**It was asked the SPECIFICATION's questions and never the WORK ITEMS'.** The self-check's
requirement supplier was `specDerivedRequirements` alone. Two of the four leaves state claims the
specification's prose cannot yield at all — `GET /members/{} → 200` and
`POST /loans/{}/return → 200`, because a success status stated once per resource does not attach
to a path the way an error status does — and the derivable ones were exactly the `unprobeable`
skips. So the evidence that would have closed the run was never gathered, by a mechanism that ran
five times and reported clean each time.

## Why this one is worse than the first two

An unconsulted authority leaves a hole you can grep for. A cropped one leaves a truncation you can
measure. **A consulted authority asked the wrong question leaves a green log line** — `probed=9,
contradicted=0` is indistinguishable from the same line on a run where the right questions were
asked. Same failure family as [[project-detector-went-silent-as-the-fault-spread]]: the instrument
kept reporting, and what it reported stopped being about the thing you cared about.

## The triage question, extended

The note's question was *"what is the authority for this fact, and who consulted it?"*. Add a
third clause: ***and what was it asked?*** A consultation is identified by its INPUT, not by the
component that performed it. Before concluding an authority has no answer, read the argument list.

**Corollary for any probe, sweep or verifier:** if it takes a question set from one source and its
answers are consumed by another, name the mismatch before trusting a clean report. Here the probe
took its questions from the specification and its answers were wanted by the goal tree, and the
two had drifted 11 criteria apart without a single warning.

**2026-09-25, false completion on structdiff run `2026-09-25_04-35-01` -- the authority was the test
SOURCE, and the question went to a model instead.** The verifier marked all 26 requirements met in one
call, "a test must assert what the entry point actually returns" among them, over a test named
`summarizeReturnsNonEmptyString`. Which assertions a test method makes is a static fact; a model
reading 26 claims at once is the one reader that can get it wrong. Fixed by `EntryPointAssertions`
(refusal only). **Triage rule it adds: before a requirement goes to the verifier, ask whether the
tree itself can answer it by reading structure -- if so, that reading is the gate and the model
is only asked what structure cannot settle.** Same night, same shape: `DeclaredAccessorResolver`
(the compiler plus the type's source settle `getType()` vs `type()`) and `ObservedBehaviour` (a
`but was` line is the code's own answer, kept instead of re-learned each cycle).
