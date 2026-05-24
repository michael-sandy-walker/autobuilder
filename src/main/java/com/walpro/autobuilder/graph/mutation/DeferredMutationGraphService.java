package com.walpro.autobuilder.graph.mutation;

import com.walpro.autobuilder.core.mutation.DeferredMutation;
import com.walpro.autobuilder.core.mutation.DeferredMutationState;

import java.util.List;
import java.util.Set;

/**
 * T4-BE: graph port for the deferred-mutation ledger. The Neo4j implementation persists each
 * {@link DeferredMutation} as a {@code (:DeferredMutation)} node with {@code BLOCKED_BY} edges
 * to {@code (:MissingMember|:MissingType {fingerprint})} nodes and {@code TOUCHES} edges to
 * {@code (:File {path, contentHash})} nodes. Test doubles can implement this interface in
 * memory without standing up Neo4j.
 */
public interface DeferredMutationGraphService {

    /** Persist a brand-new deferral. State is {@link DeferredMutationState#DEFERRED}. */
    void persist(DeferredMutation deferred);

    /** Replace state of an existing record. Used by clearance/restage/eviction flows. */
    void updateState(String deferredId, DeferredMutationState newState);

    /**
     * Drop the {@code BLOCKED_BY} edge from {@code deferredId} to any blocker whose fingerprint
     * is in {@code clearedFingerprints}. Returns the set of deferred ids whose open-blocker
     * count dropped to zero as a result — caller (clearance detector) flips them to
     * {@link DeferredMutationState#READY_TO_RESTAGE}.
     */
    Set<String> dropBlockersAndFindCleared(Set<String> clearedFingerprints);

    /** All open ({@link DeferredMutationState#DEFERRED} or READY_TO_RESTAGE) records. */
    List<DeferredMutation> findOpen();

    /** All records in {@link DeferredMutationState#READY_TO_RESTAGE} for the given intent goal id. */
    List<DeferredMutation> findReady();

    /** All open records whose intent goal id matches. */
    List<DeferredMutation> findByIntentGoal(String intentGoalId);

    /** Single fetch by id. */
    DeferredMutation findById(String deferredId);
}
