package com.walpro.autobuilder.core.checkpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * T4-DQ: implemented by every {@link StateBucket#CAPTURE} holder — the state the checkpoint
 * actually writes and the rewind actually restores.
 *
 * <p>Capture returns any Jackson-serialisable value (usually a small record); restore is handed
 * back the parsed node. Deliberately not a marker interface over reflection: a holder decides for
 * itself which of its fields are decisions and which are per-cycle memos, and only the holder
 * knows the difference. {@code RecurringDefectTracker} is the example — its shape map and totals
 * are decisions, its {@code definedCache} is a within-cycle memo of an output-tree walk.
 */
public interface CheckpointableState {

    /** Stable key this holder's state is stored under. Must be unique across all holders. */
    String stateKey();

    /** The decision-bearing state, as a Jackson-serialisable value. Never null. */
    Object captureState();

    /**
     * Restore previously captured state. Implementations replace their state wholesale rather
     * than merging — a rewind means "be as you were", not "be as you were plus what happened
     * since". A null or empty node means "reset to the start-of-run value".
     */
    void restoreState(JsonNode state, ObjectMapper mapper);
}
