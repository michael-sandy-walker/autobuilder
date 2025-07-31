# Alignment Logic Roadmap (Autobuilder)

This document outlines the foundation and next steps for Autobuilder’s alignment system — translating ethical principles into grounded system constraints.

---

## Current Foundation

| Component              | Description                                                                |
|------------------------|----------------------------------------------------------------------------|
| `ChangeProposal`       | Represents a proposed change to system state or structure                  |
| `AlignmentVector`      | Captures measurable signals: centrality, edit distance, path options, etc. |
| `TrueAlignmentAnalyzer`| Analyzes alignment by applying thresholds to the `AlignmentVector`         |

---

## Implementation Principles

- Reject changes that centralize power, destroy structure, or reduce future flexibility
- All constraints are based on concrete system metrics
- All thresholds are explicitly configurable and traceable
- This logic serves as the common ground for all mutation and evaluation decisions, as well as the 
  mechanics of agent and planner mechanisms

---

## Next Steps (Recommended)

1. Implement measurable signals:
   - `centralityScore`: via Neo4j queries
   - `editDistance`: via `DiffService`
   - `alternatePaths`: via pathfinding over graph
   - `compressionGain`: via summarizer/token count
   - `deletedReferencedNodes`: via reference tracker
   - `layerSpanDelta`: via AST/nesting depth

2. Integrate into evaluation and merge pipelines:
   - Use `TrueAlignmentAnalyzer` before accepting or promoting a proposal

3. Log all violations:
   - Each rejected mutation should record *why* it failed alignment

4. (Optional) Display in Dashboard:
   - Expose alignment metrics and violations for human traceability

5. Evolve the logic conservatively:
   - Avoid adding symbolic abstractions unless they’re grounded in structure

---

## Design Goal

> “Protect clarity, autonomy, and structural integrity — without relying on untestable abstractions.”
