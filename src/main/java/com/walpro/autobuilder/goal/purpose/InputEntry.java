package com.walpro.autobuilder.goal.purpose;

import java.time.Instant;

/**
 * T4-EC slice 1: one input a project was given -- what was ASKED, never what was derived from it.
 *
 * <p>The seed is entry 1. Later inputs (another project, instructions, an example) are entries 2..n; what is
 * derived from an input (its obligations, derived tests, adoption baseline, goals) stays in its own home and
 * points back here by {@link #id}.
 *
 * @param id           stable id, {@code input-<uuid>}
 * @param sequence     1 for the seed; arrival order after it
 * @param kind         {@code seed} for now
 * @param content      the input's text exactly as the root goal holds it
 * @param sourceDigest SHA-256 hex of the source text the input was read from ({@code bootstrap/seed.txt} where it
 *                     exists), so a key computed from those bytes before the ledger existed stays the same
 * @param addedBy      who gave it: {@code seed-file}, {@code reset}, {@code operator}, {@code migrated}
 * @param addedAt      when it was recorded
 * @param runId        the run that recorded it
 * @param lineageId    the lineage it belongs to
 */
public record InputEntry(String id, int sequence, String kind, String content, String sourceDigest,
                         String addedBy, Instant addedAt, String runId, String lineageId) {

    public static final String KIND_SEED = "seed";
}
