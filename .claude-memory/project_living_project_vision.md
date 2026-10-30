---
name: project-living-project-vision
description: "The user's long-range picture (2026-10-30): a project is never finished with its seed -- it keeps taking in inputs of any kind; Autobuilder grows itself that way and also builds OFFSPRING projects whose code AND graph become a knowledge part of whatever includes them. Read before designing input, merge, import or graph-portability work."
metadata:
  type: project
---

The user's picture, confirmed in their words on 2026-10-30 ("That's one of the basic ideas, yes"):

- **A project keeps taking input.** The seed is only the first input. A project (Autobuilder itself included) is grown by handing it more -- another code base, a paper with ideas (their example: "the Autobuilder base + the Pleroma paper"), refactor instructions (version up/downgrade, cleanup), examples, images, concepts. Each input is understood against what exists and worked in (fill, extend, correct), with provenance and undo, while work continues. From a small seed Autobuilder builds a rough first version with gaps and is then steered by adding inputs, not by rewriting the spec. Merging projects is one case of taking in input.
- **Offspring.** Autobuilder also builds OTHER projects (their examples: motor-sensory controllers, emotional simulators). They are not Autobuilder, but when included -- back into Autobuilder or into another project -- they become a **knowledge part** of it.
- **"Knowledge part" = the code base AND its metadata, like the graph** (goals, criteria, decisions, history). The user: "I don't know exactly how to do it for now, but I have the feeling, that this is crucial for Autobuilder's better understanding."

**Why:** this is the direction the input/merge work serves; it ties merge, import and graph portability to self-understanding ([[project-introspection-as-north-star]]).

**How to apply:** when designing any input, merge, import or export path, carry an input's GRAPH with it, not only its code and a provenance stamp -- today's merge keeps only `sourceLineageId` as metadata and the lineage-scoped goal map drops foreign-lineage nodes. The "how" is open; do not invent a mechanism under this note -- it is direction, and the concrete design lives on `T4-EC` (ledger of inputs, graph-first). Earlier evidence to weigh: runs of ONE seed share almost nothing worth transferring (T6-G), so the value is expected from DIFFERENT projects, which offspring are. Related: [[project-project-portability-vision]], [[project-cross-run-knowledge-base]].
