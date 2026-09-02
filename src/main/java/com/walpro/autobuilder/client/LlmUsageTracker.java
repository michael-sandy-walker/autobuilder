package com.walpro.autobuilder.client;

import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * T5-R: per-role and per-session accumulation of LLM token usage. With ~5 agent roles each making
 * calls per cycle plus auxiliary services, session cost is otherwise invisible — a run that burned
 * 10× the tokens looks identical in the trace. Fed from the single {@link LLMClient#ask} chokepoint;
 * read by the {@code GET /autobuilder/usage} endpoint. No dollar cost: the target runs a local model
 * (see project_local_first_constraint), so tokens are the honest unit; a pricing map is not modelled.
 */
@Component
@StateHolder(value = StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "session token accounting; rewound time was still spent")
public class LlmUsageTracker {

    /** Per-role running totals. Keyed by {@link LlmCallContext} role (incl. {@code "unknown"}). */
    private final Map<String, RoleTotals> byRole = new ConcurrentHashMap<>();

    private final AtomicLong sessionPromptTokens = new AtomicLong(0);
    private final AtomicLong sessionCompletionTokens = new AtomicLong(0);
    private final AtomicLong sessionCalls = new AtomicLong(0);
    private final AtomicLong sessionCallsWithUsage = new AtomicLong(0);

    /**
     * Record one completed LLM call. {@code promptTokens}/{@code completionTokens} are null when the
     * provider omitted the usage block — the call is still counted (so call volume is truthful) but
     * does not contribute to token totals.
     */
    public void record(String role, Integer promptTokens, Integer completionTokens) {
        String key = role == null ? LlmCallContext.UNKNOWN_ROLE : role;
        RoleTotals totals = byRole.computeIfAbsent(key, k -> new RoleTotals());
        sessionCalls.incrementAndGet();
        totals.calls.incrementAndGet();
        boolean hadUsage = promptTokens != null || completionTokens != null;
        if (hadUsage) {
            sessionCallsWithUsage.incrementAndGet();
            totals.callsWithUsage.incrementAndGet();
        }
        if (promptTokens != null) {
            sessionPromptTokens.addAndGet(promptTokens);
            totals.promptTokens.addAndGet(promptTokens);
        }
        if (completionTokens != null) {
            sessionCompletionTokens.addAndGet(completionTokens);
            totals.completionTokens.addAndGet(completionTokens);
        }
    }

    public long sessionPromptTokens() { return sessionPromptTokens.get(); }
    public long sessionCompletionTokens() { return sessionCompletionTokens.get(); }
    public long sessionTotalTokens() { return sessionPromptTokens.get() + sessionCompletionTokens.get(); }
    public long sessionCalls() { return sessionCalls.get(); }
    public long sessionCallsWithUsage() { return sessionCallsWithUsage.get(); }

    /** Immutable snapshot for the usage endpoint — session totals plus a per-role breakdown. */
    public Snapshot snapshot() {
        Map<String, RoleSnapshot> roles = new TreeMap<>();
        byRole.forEach((role, t) -> roles.put(role, new RoleSnapshot(
                t.calls.get(), t.callsWithUsage.get(),
                t.promptTokens.get(), t.completionTokens.get(),
                t.promptTokens.get() + t.completionTokens.get())));
        return new Snapshot(
                sessionCalls.get(), sessionCallsWithUsage.get(),
                sessionPromptTokens.get(), sessionCompletionTokens.get(), sessionTotalTokens(),
                roles);
    }

    private static final class RoleTotals {
        final AtomicLong calls = new AtomicLong(0);
        final AtomicLong callsWithUsage = new AtomicLong(0);
        final AtomicLong promptTokens = new AtomicLong(0);
        final AtomicLong completionTokens = new AtomicLong(0);
    }

    public record RoleSnapshot(long calls, long callsWithUsage,
                               long promptTokens, long completionTokens, long totalTokens) {}

    public record Snapshot(long calls, long callsWithUsage,
                           long promptTokens, long completionTokens, long totalTokens,
                           Map<String, RoleSnapshot> byRole) {}
}
