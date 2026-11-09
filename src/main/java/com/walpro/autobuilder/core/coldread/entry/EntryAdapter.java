package com.walpro.autobuilder.core.coldread.entry;

import com.walpro.autobuilder.core.coldread.ClaimProposer.Claim;
import com.walpro.autobuilder.core.coldread.ColdRead;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.Observation;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * T4-EK: how a project is exercised -- by starting it ({@link ProgramAdapter}) or by calling it
 * ({@link LibraryStyleAdapter}). Everything else -- capabilities, features, variants, which feature a claim belongs to,
 * judgement -- is shared; an adapter only finds its ways in and runs exercises.
 */
public interface EntryAdapter {

    /** The tree's ways in, each with its kind; empty when this adapter has none for it. */
    List<EntryPoint> find(Map<String, String> files, ColdRead coldRead);

    /** How a claim of {@code kind} is written, for the model. */
    String exerciseForm(EntryKind kind);

    /**
     * Exercises {@code claims} on the tree at {@code dir}, recording one observation per claim under {@code variant}.
     *
     * @return whether the tree could be exercised at all -- a tree that did not build or start says nothing about any
     *         claim, and must never read as every claim failing
     */
    Ran exercise(Path dir, String variant, List<Claim> claims, Map<String, Map<String, Observation>> seen);

    /** Whether a tree could be exercised; {@code why} when it could not. */
    record Ran(boolean built, boolean ran, String why) {
    }
}
