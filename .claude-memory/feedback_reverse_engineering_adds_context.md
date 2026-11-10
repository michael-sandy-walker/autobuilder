---
name: feedback-reverse-engineering-adds-context
description: Reverse engineering ADDS the context an Autobuilder-built project would have (graph nodes, seed, obligations, criteria); it never corrects the project's code, README or configuration.
metadata:
  type: feedback
---

Reverse engineering a project the loop did not build adds the layer Autobuilder would have produced had it built it -- graph nodes, `bootstrap/seed.txt`, obligations, the goal tree, criteria with their exercises, recorded answers such as `meta/services.json`. It never "corrects" what the project already has: its code, README and configuration stay exactly as found, as the evidence (the user, 2026-11-10, T1-GK/T4-EN: "It's not about 'correcting' code or README files or anything like that in the reverse-engineering step").

**Why:** the project as found is the evidence the claims are judged against; editing it to suit the reading would make the reading grade itself. Also the seed holds only what the running code bore out, so an unchecked README is context, never seed (T1-GK).

**How to apply:** a cleaned or derived view (e.g. `ReadmeProse`) is a reading in memory, never written back; work on a copy; reach a service at the address the project is configured with instead of moving the address (T4-EN); before any reverse-engineering step writes into `output/`, ask -- the one known case, `ReverseEngineeringConfirmation.writeClaimTests`, is an open question on T4-EK. Related: [[project-living-project-vision]], [[project-invented-facts-have-authorities]].
