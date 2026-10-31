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
 * @param kind         {@code seed}, or what a later input is: {@code text}, {@code project}
 * @param content      the seed's text exactly as the root goal holds it, a text input's text, a project input's intent
 * @param sourceDigest SHA-256 hex of the source text the input was read from ({@code bootstrap/seed.txt} where it
 *                     exists), so a key computed from those bytes before the ledger existed stays the same; for a
 *                     project, a digest of the tree it arrived with
 * @param addedBy      who gave it: {@code seed-file}, {@code reset}, {@code operator}, {@code migrated}
 * @param addedAt      when it was recorded
 * @param runId        the run that recorded it
 * @param lineageId    the lineage it belongs to
 * @param sourceRunId     a project input's run, or null
 * @param sourceLineageId a project input's lineage, whose history stays under it; null for a third party's
 */
public record InputEntry(String id, int sequence, String kind, String content, String sourceDigest,
                         String addedBy, Instant addedAt, String runId, String lineageId,
                         String sourceRunId, String sourceLineageId) {

    public static final String KIND_SEED = "seed";
    public static final String KIND_TEXT = "text";
    public static final String KIND_PROJECT = "project";
}
