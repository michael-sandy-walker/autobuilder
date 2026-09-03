---
name: project-detector-went-silent-as-the-fault-spread
description: "2026-09-03: the unregistered-bean detector required two annotated peers, so it stopped firing when two of three classes were wrong instead of one — a detector that fails harder as the fault gets worse."
metadata:
  node_type: memory
  type: project
---

Run `2026-09-03_19-05-02` ended `smoke=boot-failed` on `BookController` requiring a
`BookRepository` bean that was never registered. The loop **already had** the detector
(`WiringFactsAnalyzer.findUnregisteredInjectedTypes`), the repair
(`InjectedTypeRegistrationRepair`, invoked post-merge by `MergeAgent`) and a passing scenario test
for the identical defect from run `2026-08-30_18-44-37`.

**Why it did not fire:** the rule counted only ANNOTATED same-package peers and required at least
two (`PEER_GROUP_MIN_OTHERS`). The 08-30 run left ONE repository of three unregistered, so it had
two annotated peers and was caught. This run left TWO of three unregistered, so each candidate saw
a single annotated peer and the rule went silent. **The detector stopped firing exactly as the
fault spread.** Fixed by giving the rule its own bar (`INJECTED_TYPE_MIN_ANNOTATED_PEERS = 1`): it
has evidence the other peer rules lack — the type is a constructor parameter of a registered type,
so the container will ask for it and fail — and the peer set is only there to name WHICH annotation
is missing, which one peer does as well as two.

**Lens, and the reason this is worth remembering beyond the fix:** ask of any peer/majority rule
what happens when the anomaly becomes the majority. A threshold calibrated on "one thing is wrong"
inverts into silence at "several things are wrong", which is the case that matters most.
See [[project-capability-outruns-routing]] — again the behaviour was unreached, not unbuilt.

**Also observed in the same run, not yet fixed:** the boot gate DID catch it at 19:15:23 and
injected the exact message as work (`goal-4982e938`); one mutation was rejected at 19:16:13 and the
loop then never touched `BookRepository` again, spending 25 more minutes merging `BookController`
five times — the victim, not the cause. Filed as the open question in [[project-resume-state]].
