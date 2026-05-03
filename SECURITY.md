# Security

This document captures the threat model for autobuilder and the deployment guidance derived from it. It is intentionally short and focused on the boundaries that matter, not on speculative exposures.

## Current intended posture

- Single dev machine.
- Ollama (or any LLM endpoint) bound to `localhost` only.
- `MergeAgent` writes to a sandboxed `output/` directory under `file.base-path`. No automatic VCS push, CI trigger, or deploy.
- Autobuilder REST endpoints (`/autobuilder/evolve`, `/autobuilder/status`, manual reset) reachable only from `localhost`.

In this posture the items below are theoretical. They become load-bearing in any of these futures: (a) the LLM endpoint exposed beyond localhost, (b) the LLM process shared with workloads that handle untrusted input, (c) `MergeAgent` wired to push out-of-process without a human-review gate, (d) autobuilder run artefacts (logs, ndjson, mutations, snapshots, commits) shared with anyone.

## Threat model — input-side

### KV-cache prefix-reuse side channel

Modern LLM serving frameworks reuse cached KV-prefix across requests for latency. With shared access to the endpoint, an attacker can probe candidate prefixes and measure time-to-first-token to recover prior users' prompts. Documented against vLLM, SGLang, and similar engines (cf. *"InputSnatch"*, Zhang et al. 2024, and follow-ups). Ollama's prefix cache exhibits the same property.

**Applies when:** the LLM endpoint is reachable by anyone other than the autobuilder process itself.

**Mitigation:**
- Verify the LLM endpoint binds only to loopback. For Ollama: `OLLAMA_HOST=127.0.0.1:11434` (the default in most installs, but easy to override). Confirm with `ss -tlnp | grep 11434`.
- Do not run other applications against the same LLM daemon while autobuilder is running.

### MoE expert-routing leakage

Mixture-of-Experts models (e.g. `qwen3-coder:30b`) route each token to a subset of experts. Co-batched-tenant attacks can reconstruct victim tokens from observed expert-activation patterns (cf. *"Stealing User Prompts from Mixture of Experts"*, Yona et al. 2024). Mitigated by sole-tenant batching, which is Ollama's default for solo workloads — broken if the same Ollama process serves more than one source of requests.

**Applies when:** the LLM process is shared across multiple request sources.

**Mitigation:** sole-tenant inference. If shared inference is unavoidable, migrate to a serving framework with documented per-tenant isolation guarantees.

### State-priming amplification via auto-merge

Autobuilder's first-cycle output depends on inference-host state — specifically GPU-allocator and MoE-routing state, which is reset on Ollama restart but perturbed by any other model that runs in between. An attacker who can force LLM-process restarts (OOM another large model into the host, exploit a known crash) chooses *which mode* the code generator runs in — i.e. picks the *distribution* of generated mutations.

Today this is harmless because a human reviews each merge. In any future where `MergeAgent` is wired to push out-of-process automatically, distributional control over generated code becomes a code-shipping vector.

**Applies when:** autobuilder mutations are merged to anything beyond the local sandbox without human review.

**Mitigation:** mandatory human-review checkpoint between `mutation_merged` (filesystem write) and any out-of-process side effect (VCS push, CI run, deploy). The point isn't to gate dev-loop merges; it's to ensure the auto-deploy pathway, when it eventually exists, doesn't bypass review.

### Reproducibility as a defensive property

`ChatRequest` does not currently send `seed` or `top_k` to the LLM endpoint. Without them, identical prompts produce slightly different outputs across runs even at `temperature=0` (FP-tie sampler behaviour, MoE routing drift, allocator-state-dependent kernel selection). This makes anomalous outputs harder to flag because the baseline isn't reproducible.

**Mitigation:** when investigating regressions, doing security-review of generated mutations, or producing publishable runs, pin `autobuilder.llm.api.seed` and `autobuilder.llm.api.topK=1`. Leave unset for ordinary use to retain MoE diversity.

## Threat model — output-side

### First-cycle output as a side channel for inference-host state

The cold-vs-warm allocator-state variance has a symmetric reading: if anyone observes autobuilder *outputs* across multiple runs, the structural pattern of cycle 0 leaks information about the inference host without any access to it.

- **Operator-activity oracle.** A "cold" first-cycle signature (broad mutation, ~15 files for the library-API seed) versus a "warm" signature (narrow, ~5 files) tells an observer whether the LLM process was recently restarted — which in turn reveals when the operator was debugging, switched models, or rebooted.
- **Model + prompt-template fingerprinting.** First-cycle file-set, naming conventions, and structural choices are characteristic of a specific model + prompt-contract combination. An observer comparing outputs against a corpus of known model fingerprints can identify the underlying LLM and the prompt scaffolding, narrowing the search space for targeted prompt-injection or jailbreak crafting.
- **Seed-text recovery.** Because the planner's first subgoal derives near-deterministically from `bootstrap/seed.txt`, an observer with several consecutive first-cycle outputs can reverse-engineer or constrain the seed text content even without read access to the file. Relevant if the seed contains anything sensitive (private goal language, internal terminology, references to non-public systems).
- **MoE-routing inference.** With enough samples, the *variation* between first-cycle outputs across runs is itself signal: which decision points flip between cold and warm states identifies which planner-prompt subdecisions have FP-tie ambiguity in the model — narrowing the search space for an attacker who later tries to influence those same decisions through co-tenant priming or prompt crafting.

**Applies when** any autobuilder run artefact leaves the host. This includes:

- `behaviour-*.ndjson` files shared with anyone (debugging help, bug reports, screenshots).
- `mutations/` or `snapshots/` directories committed to or synced with any external location.
- Autobuilder runs published in any form (research, demos, blog posts, presentations).
- Centralised log aggregation (autobuilder logs sent to a multi-tenant observability backend).
- Future: any VCS push from `MergeAgent` produces a per-commit sample of model + state output.

**Mitigation:**
- Treat `bootstrap/seed.txt` as the same sensitivity tier as the artefacts derived from it. If the seed describes private internal systems, every published cycle output may leak fragments of that description.
- Before publishing any run output (filing an issue, writing a blog post, demoing the system), redact or paraphrase if the seed or any prompt block contains anything non-public.
- For research / demo runs, use a synthetic seed that's safe to publish, rather than a real internal goal.
- For deliberately publishable runs, pin sampling parameters (`seed`, `top_k=1`) so the published output has a known, reproducible fingerprint rather than an unknowable distribution.

## Quick checklist before changing posture

Before doing any of the following, revisit this document and the corresponding mitigations:

- [ ] Moving the LLM endpoint off `localhost` → re-read input-side threat model; add request authentication on autobuilder REST endpoints.
- [ ] Sharing the LLM process with anything else → re-read MoE expert-routing leakage; consider sole-tenant isolation.
- [ ] Wiring `MergeAgent` to push out-of-process → re-read state-priming amplification; add human-review checkpoint.
- [ ] Publishing run artefacts (logs, ndjson, mutations, commits) → re-read output-side threat model; sanitise seed and prompts first.
- [ ] Migrating to shared inference (cloud, vLLM, hosted endpoint) → re-read KV-cache and MoE sections; require per-tenant isolation guarantees.

## Out of scope

The following concerns are intentionally not addressed here. They belong in separate documents or tickets if and when they become relevant:

- Sandboxing of generated code beyond the existing `output/` boundary.
- Prompt-injection resistance in the LLM itself (a model-level concern, not a framework one).
- TLS for `LLMClient` (irrelevant while the endpoint is loopback).
- Supply-chain integrity of dependencies (covered by standard build tooling, not autobuilder-specific).

## References

- Zhang et al., *"InputSnatch"* (2024) — KV-cache prefix-reuse timing attacks against vLLM/SGLang.
- Yona et al., *"Stealing User Prompts from Mixture of Experts"* (DeepMind, 2024) — co-batched-tenant attacks on MoE inference.
- Roadmap ticket `T5-L` — implementation phases for the mitigations described here.
