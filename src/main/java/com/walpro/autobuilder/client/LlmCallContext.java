package com.walpro.autobuilder.client;

/**
 * T5-R: ambient (thread-local) tag naming the agent role and cycle a {@link LLMClient#ask} call
 * belongs to. The client itself has no role/cycle handle — every call goes through the same
 * {@code ask}, so attribution is set at the one point that knows both: the engine's agent dispatch
 * (which wraps each agent's whole execution, so sub-service LLM calls inherit the correct role) and
 * the interpretation step. Calls made outside any scope (auxiliary services, startup probes) read
 * {@link #UNKNOWN_ROLE}, which is honest — they are not one of the ranked agent roles.
 *
 * <p>The loop is single-threaded per cycle, so a plain {@link ThreadLocal} is safe; {@link Scope}
 * restores the previous tag on close so nested scopes (an agent whose execution triggers another
 * tagged call) behave correctly.
 */
public final class LlmCallContext {

    public static final String UNKNOWN_ROLE = "unknown";

    private record Tag(String role, String cycleId) {}

    private static final ThreadLocal<Tag> CURRENT = new ThreadLocal<>();

    private LlmCallContext() {}

    /** Open a scope; use try-with-resources so the previous tag is always restored. */
    public static Scope scope(String role, String cycleId) {
        Tag previous = CURRENT.get();
        CURRENT.set(new Tag(role == null ? UNKNOWN_ROLE : role, cycleId));
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    /**
     * Names the purpose of an auxiliary service's call when nothing else has, and otherwise leaves
     * the tag alone.
     *
     * <p>The class comment above called the untagged calls honest, and they were — but they were
     * also 38 of 96 calls in run {@code 2026-09-23_21-53-03}, shown in the interface as "unknown",
     * which answers nothing about where 40% of the model's time went. A service that knows what it
     * is asking says so here. Inside an agent's scope the agent keeps the attribution, because the
     * per-role cost readout is read by agent role and a sub-service's call IS that agent's cost.
     */
    public static Scope scopeIfUnscoped(String purpose) {
        Tag current = CURRENT.get();
        if (current != null && !UNKNOWN_ROLE.equals(current.role())) {
            return () -> { };
        }
        return scope(purpose, current == null ? null : current.cycleId());
    }

    public static String currentRole() {
        Tag t = CURRENT.get();
        return t == null ? UNKNOWN_ROLE : t.role();
    }

    public static String currentCycleId() {
        Tag t = CURRENT.get();
        return t == null ? null : t.cycleId();
    }

    /** AutoCloseable with a no-checked-exception close, for clean try-with-resources. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
