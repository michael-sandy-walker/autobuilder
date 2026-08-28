---
name: project-invented-facts-have-authorities
description: "2026-08-28 session lens: every defect found tonight is the loop INVENTING a fact whose cheap, consultable AUTHORITY sat unconsulted — and the session's own method errors were the same shape one level up. Triage question: 'what is the authority for this fact, and who consulted it?'"
metadata: 
  node_type: memory
  type: project
  originSessionId: 08a0057e-31eb-4f9d-a1db-8717c69fbf03
  modified: 2026-08-28T04:35:27.677Z
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

The last row is the family's apex: "accounted for" is an invented completion-fact, and the seed —
the one authority the whole system exists to serve — is the only party never consulted at the
closure edge.

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
