# process/

How the operator and the session work on Autobuilder -- not Autobuilder itself. Nothing here is part of
the product, and nothing in `src/` or `scripts/` depends on it.

- `port_review.py`, `blind_reference_workspace.sh`: the port review stage. A solution the loop built for a
  ticket reaches `src/` only after the operator reviews a packet and approves (meta_process.md, M-E).
- `ports/<target>/`: one review packet per loop-built solution. `ports/stuck-router/` holds the stuck
  router's differential inputs (archived signatures, then labelled probes) and its harness.
