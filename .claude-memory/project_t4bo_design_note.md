---
name: project-t4bo-design-note
description: "T4-BO design note (2026-07-08) — signal-decay observability, measure before tuning. Two-snapshot lifetime-tracking algorithm (decayed vs cleared, no hooks at the ~70 withoutSignal call sites), causality-preserving signal->action correlation via a pre-execution snapshot, and the offline analyser. Read before touching Signal/AdaptiveExecutionEngine/CycleExecutor signal plumbing or before building T4-J."
metadata:
  node_type: memory
  type: project
  originSessionId: 528096bd-6c47-419f-b5f1-3995d268575d
---

# T4-BO design note — signal-decay observability (2026-07-08)

Entered per the 2026-07-08 re-triage as a significant-advancement ticket riding alongside T4-AI. Timing rationale: with the meta-lane now off by default (T4-AI-2+5), agents in the field fire PURELY on signal fitness — "does signal X actually drive action Y" is for the first time an uncontaminated question (previously the meta prefix could fire an agent regardless of its signals, confounding any correlation). Ticket header says "design only, no code" but the re-triage upgraded this to build-now; the ticket's own body already sketches the build (~100 LOC tracker + ~50 LOC analyser). This note is the design; the code follows in the same session.

## The core design problem

The ticket asks for two measurements per signal type: (1) lifetime distribution (cycles from emission to death) and (2) signal→action correlation. Two decisions determine everything else:

**1. How do we know WHY a signal died** (decayed vs explicitly cleared) **without hooking the ~70 `withoutSignal(...)` call sites** (confirmed via grep) scattered across the codebase? Hooking each one would violate [[feedback-one-invariant-one-home]] and be a maintenance trap (every new clear site would need the same instrumentation).

**Answer: two snapshots per cycle are sufficient.** Capture the signal map immediately BEFORE `tickSignals()` runs (reflects everything this cycle's agents did, including any explicit clears) and immediately AFTER (reflects natural decay only). Comparing against what was tracked alive at the END of the PREVIOUS cycle:
- A type tracked alive last cycle but already absent from THIS cycle's pre-tick snapshot → some agent called `withoutSignal` on it during this cycle's execution, before the tick ran → **cleared**.
- A type present pre-tick but absent post-tick → `tick()` pushed it below `SIGNAL_SURVIVAL_THRESHOLD` → **decayed**.

This needs exactly ONE call site per cycle boundary (where `tickSignals()` already runs), not 70. Verified algorithmically against `Signal.tick()`'s actual semantics (age==0 → no decay on the signal's first tick, since `reinforce` always resets age to `min(age, 0)=0` — so a freshly-emitted-then-reinforced signal never "ages" in the traditional sense; the tracker's set-membership approach handles this correctly for free, without re-deriving decay math).

**Known, accepted limitation:** sub-cycle churn (a type cleared and re-emitted for the same type within one cycle, before the next snapshot) is invisible — indistinguishable from "never died." This is fine: "cycles to death" is the unit the ticket asks for, and cycles are the natural granularity of the whole control loop.

**2. Where do we snapshot signals for the correlation question, and why does it have to be that exact point?** For "does signal X's presence correlate with action Y firing" to mean CAUSATION-direction rather than a tautology, the snapshot must be taken BEFORE any agent executes this cycle — otherwise an agent's own execution (which can itself emit/clear signals) would contaminate the "signal present" reading for actions later in the same cycle, and a signal that changes AS A RESULT of action Y firing would falsely look like it "caused" Y.

**Answer:** `AdaptiveExecutionEngine.runCycle` already captures exactly this point today — the local variable `snapshot` (`final CycleContextData snapshot = context;`, line 155), taken right before the fitness-ranked execution loop and already used for `fitnessFor`/`fitnessBreakdown`/the existing `agent_fitness_ranked` event. No new state needed; the correlation snapshot is a second behaviour-log emission using the SAME `snapshot` variable.

**Known, accepted limitation:** when `meta-lane-enabled=true` (non-default since T4-AI-2+5), an agent can execute via the meta-prefix path BEFORE `snapshot` is captured, and its `agent_selected` event is schema-identical to a fitness-pass selection — the analyser cannot distinguish "selected by meta" from "selected by fitness" from the event alone. Accepted because the meta lane is off by default in the field; documented rather than silently ignored.

## Event shapes (added to `BehaviourEvent`, house style: sealed record, `event()` string)

- `SignalSnapshot(timestamp, cycleId, signals: Map<String,Double>)` — raw signal-type→strength at ranking time, once per fitness-pass cycle. Reuses the existing `extractSignalSnapshot` helper (previously only used for `MetaOverrideEvent`).
- `SignalLifetimeObserved(timestamp, cycleId, signalType, emitCycle, deathCycle, deathReason, emittedBySource)` — one per completed lifetime episode (death), NOT per cycle-per-signal (deaths are far rarer than ticks, so this is cheap — well inside the ticket's ≤20% volume-inflation acceptance bar). `emitCycle`/`deathCycle` are the TRACKER'S OWN monotonic counter (incremented once per `observe()` call), NOT `cycleName()` (a timestamp string like `cycle-<epochMilli>`) — so `deathCycle - emitCycle` is directly the lifetime in cycles, and the analyser doesn't need to parse cycle IDs as numbers at all (correlation matches events by the shared `cycleId` string; only the lifetime metric needs an ordinal, which the tracker supplies).

## `sessionEnd` handling — no shutdown hook

A signal type still alive when the JVM stops never produces a terminal `SignalLifetimeObserved` event (there's no "next cycle" to observe the death). Considered a `@PreDestroy` flush hook; rejected as unnecessary complexity for pure observability. Instead: the offline analyser reconstructs open episodes purely from the `SignalSnapshot` timeline (present→absent/absent→present transitions across snapshot events in file order) and, for any episode still open at the last snapshot, synthesizes a `deathReason="sessionEnd"` row. This matches the ticket's literal `{deathReason: decayed/cleared/sessionEnd}` shape without runtime shutdown-ordering fragility.

## Wiring (minimal, follows existing house patterns exactly)

- `SignalLifetimeTracker` — new `@Component`, in `observability/` (per the ticket's suggested path), pure state machine: `List<Death> observe(Map<SignalType,Signal> preTick, Map<SignalType,Signal> postTick)`. No Spring/logging dependency of its own — testable with plain maps, zero mocking needed.
- `CycleExecutor` gets an optional `@Setter @Autowired(required=false)` field (identical pattern to its existing `behaviourLogger`/`goalStateEventPublisher`/`deferredRestageScheduler` optional collaborators — chosen specifically so the existing manually-constructed `CycleExecutorGoalLoopTest` positional constructor call keeps compiling; `@RequiredArgsConstructor` would have made it a breaking required param). A new private helper `tickSignalsTracked(context)` wraps `context.tickSignals(SIGNAL_SURVIVAL_THRESHOLD)` at all THREE call sites (the `SYSTEM_PAUSED` early return, the T3-BL criteria-banked early exit, and the main end-of-cycle path) — signals decay in paused/early-exit cycles too, so all three are legitimate lifetime-observation points, not just the main path.
- `AdaptiveExecutionEngine` gets one new line next to the existing `agentFitnessRanked` call: `behaviourLogger.signalSnapshot(snapshot.cycleName(), extractSignalSnapshot(snapshot));`.

## Offline analyser (`scripts/signal_lifetime_report.py`)

Reads a `behaviour.ndjson` path, produces two tables from a single pass:
1. **Lifetime table** per signal type: emission count (from `SignalLifetimeObserved` deaths + reconstructed `sessionEnd` opens), lifetime p50/p95 in cycles, death-reason breakdown (decayed/cleared/sessionEnd counts).
2. **Correlation table** per (signalType, role): `P(role selected | signal present this cycle) - P(role selected | signal absent this cycle)`, built by joining `SignalSnapshot` (signal presence per cycleId) against `AgentSelected` (role executions per cycleId) on the shared `cycleId`. Near-zero correlation across a real run is the "candidate for deletion" signal the ticket names; correlation concentrated in one signal's clearing (rather than its own decay) is the "over-tuned, shorten the decay" signal.

Acceptance is validated by running the analyser against a preserved corpus from earlier today's A/B runs (`/tmp/preflight_runs/2026-07-08_*`), not just unit tests on synthetic ndjson — proving it produces real numbers on a real run, per [[feedback-activity-not-progress]].

## What this explicitly does NOT do (matches the ticket's own scope line)

No tuning changes. No new decay constants. No T4-J work. Pure observation; the deliverable is data, not a fix.
