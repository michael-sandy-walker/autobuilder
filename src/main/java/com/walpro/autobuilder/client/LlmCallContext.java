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
