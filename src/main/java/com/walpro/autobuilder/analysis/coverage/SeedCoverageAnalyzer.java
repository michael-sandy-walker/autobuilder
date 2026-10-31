package com.walpro.autobuilder.analysis.coverage;

import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.SeedRequirements;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * T4-DA: compares the seed against the WHOLE current plan — every subgoal regardless of status,
 * plus every acceptance criterion — and reports which parts of the specification the plan does
 * not articulate.
 * <p>
 * This exists to be TOLD TO THE AGENTS. The readout is rendered into the planner, evolution and
 * analysis prompts so they can choose differently; raising work items from it is a separate,
 * later decision. A version of this that only injects goals has missed the point.
 * <p>
 * Deterministic and domain-neutral by construction. It reads the seed as text and the plan as
 * text: no model call, no notion of endpoints, status codes, resources or any other category.
 * A seed it cannot split into clauses, or a plan with no descriptions, yields an empty readout
 * and no opinion.
 * <p>
 * <b>What it measures, precisely, and what it does not.</b> A clause is reported when terms of it
 * appear nowhere in the plan's text. That is a statement about the PLAN, not about the artifact:
 * the loop routinely implements a detail (input validation, say) inside a broadly-worded subgoal
 * that never names it, and such a clause is reported although the code is present. Measured over
 * three frozen runs, every requirement that failed in all three was reported, alongside about
 * four reports per run whose behaviour was in fact implemented. Recall is the property worth
 * having here, because the consumer is a prompt and a reader can dismiss a clause in a glance —
 * were this ever to gate anything, that trade would have to be revisited.
 * <p>
 * <b>Why filler words do not need filtering.</b> A term is only reported when it is absent from
 * EVERY subgoal description. Ordinary connective words survive in any plan of more than a few
 * goals, so they rarely surface; when they do, they surface as part of a clause whose substance
 * the plan never engaged with — "404 if either not found" was reported in all three frozen runs
 * on the strength of {@code either}/{@code found} alone, and it was a genuine gap each time.
 */
@Slf4j
@Component
public class SeedCoverageAnalyzer {




    /** Everything that is not a term character. Braces and backslashes stay: a spec may state patterns. */
    private static final Pattern NON_TERM = Pattern.compile("[^A-Za-z0-9{}\\\\]+");

    /**
     * Below this many terms a fragment is punctuation or a stray heading rather than a clause.
     * Not a tuned threshold — three terms is the smallest fragment that can carry a subject and
     * a constraint, so anything shorter cannot be checked for coverage in the first place.
     */
    private static final int MIN_CLAUSE_TERMS = 3;

    /**
     * A ceiling for a pathological specification, not a prompt budget. The prompt budget belongs
     * to the renderer, which knows what it can afford; the analyzer's job is to report what it
     * found. An earlier cut capped this at 8 here and it removed one of the two requirements that
     * failed in every frozen run — in a different run each time, so no ordering rescued it.
     */
    private static final int SANITY_CEILING = 40;

    /**
     * @param seed  the root goal's description — the specification as stated
     * @param goals every subgoal of the run, in any status; terminal goals count as claiming
     *              their part of the seed just as active ones do
     */
    public SeedCoverageReadout analyze(String seed, Collection<GoalEntity> goals) {
        return analyze(seed, goals, (String) null);
    }

    /**
     * T6-S: as above, with the target's written obligations when it has them.
     *
     * <p>A PARAMETER rather than a field. A settable field would have made this bean stateful — the
     * T4-DQ registration contract failed the build for exactly that, correctly — and the
     * set-then-call pattern is not safe to share either. The analyzer is already given its seed as
     * text; its obligations belong in the same place.
     */
    public SeedCoverageReadout analyze(String seed, Collection<GoalEntity> goals,
                                       String writtenObligations) {
        return analyze(seed, goals, SeedRequirements.obligationsOrClauses(seed, writtenObligations));
    }

    /** T4-EC slice 3: as above, with the sentences already read -- every input's, from the purpose. */
    public SeedCoverageReadout analyze(String seed, Collection<GoalEntity> goals, List<String> obligations) {
        if (seed == null || seed.isBlank() || goals == null || goals.isEmpty()) {
            return SeedCoverageReadout.empty();
        }

        List<String> clauses = clausesOf(obligations);
        if (clauses.isEmpty()) return SeedCoverageReadout.empty();

        Set<String> planTerms = new LinkedHashSet<>();
        Set<String> criterionTerms = new LinkedHashSet<>();
        for (GoalEntity g : goals) {
            if (g == null || restatesTheSpecification(g, seed)) continue;
            planTerms.addAll(termsOf(g.getDescription()));
            for (String criterion : criteriaOf(g)) {
                criterionTerms.addAll(termsOf(criterion));
            }
        }
        if (planTerms.isEmpty()) return SeedCoverageReadout.empty();

        List<Ranked> unclaimed = new ArrayList<>();
        List<Ranked> unprovable = new ArrayList<>();
        for (int i = 0; i < clauses.size(); i++) {
            String clause = clauses.get(i);
            Set<String> terms = new LinkedHashSet<>(termsOf(clause));
            List<String> missingFromPlan = absent(terms, planTerms);
            double planEngagement = engagement(terms, missingFromPlan);
            // A clause is unclaimed when the plan barely engages it — NOT when any single term is
            // absent. Requiring every term meant one unrestated detail condemned the whole clause:
            // hand-verified on run 2026-08-28_20-37-30, which reported 17 of 19 clauses unclaimed
            // while its goal tree contained "Implement POST /books endpoint", "Implement GET /books
            // endpoint" and "Implement GET /books/{id} endpoint" verbatim. The clause stating those
            // was condemned for `120`, `chars` and `404` — spec detail no work item restates. At
            // 17-of-19 false the readout is noise the planner cannot act on, which is the failure
            // mode this class's own javadoc warns about ([[feedback-activity-not-progress]]).
            // engagement() already computed the share; it was only ordering the list.
            if (!missingFromPlan.isEmpty() && planEngagement < CLAIMED_ENGAGEMENT) {
                unclaimed.add(new Ranked(new SeedCoverageReadout.Finding(clause, missingFromPlan),
                        planEngagement, i));
                continue;
            }
            // Only a clause the plan DOES articulate can be unprovable — otherwise the absent
            // criterion is a restatement of the unclaimed finding rather than a second fact.
            List<String> missingFromCriteria = absent(terms, criterionTerms);
            if (!missingFromCriteria.isEmpty()) {
                unprovable.add(new Ranked(new SeedCoverageReadout.Finding(clause, missingFromCriteria),
                        engagement(terms, missingFromCriteria), i));
            }
        }

        SeedCoverageReadout readout = new SeedCoverageReadout(
                cap(unclaimed), cap(unprovable));
        log.info("T4-DA: seed coverage over {} clause(s) against {} goal(s) — {} unclaimed, {} claimed-but-unprovable",
                clauses.size(), goals.size(), readout.unclaimed().size(), readout.unprovable().size());
        return readout;
    }

    /**
     * A goal that merely restates the specification does not articulate it, and must not count
     * toward coverage.
     * <p>
     * The root goal's description IS the seed, and {@code getAllGoals()} returns it alongside the
     * subgoals. Without this the seed's every term was in the plan vocabulary by construction, so
     * nothing could ever be reported unclaimed — the check would have answered "no drift" for the
     * rest of the project's life while looking healthy in the log. Caught on the first field
     * firing, which printed {@code 0 unclaimed} against a tree holding only the root.
     * <p>
     * Keyed on the description rather than on {@link com.walpro.autobuilder.goal.GoalType} because
     * the property that matters is restating the specification, not sitting at the top of the
     * tree: a resumed run's adopted root, or a work item pasted from the spec, has the same effect.
     */
    private boolean restatesTheSpecification(GoalEntity goal, String seed) {
        String description = goal.getDescription();
        if (description == null) return false;
        return termsOf(description).equals(termsOf(seed));
    }

    /**
     * Clause boundaries, delegated to {@link SeedRequirements#sentencesOf} rather than re-derived.
     *
     * <p><b>T6-S.</b> This used to split on {@code \R} and then on a bare {@code (?<=[.;])\s+},
     * which is wrong twice over on the prose this project actually has. Every seed is hard-wrapped,
     * so the line split tore sentences in half — the clitarget seed yielded {@code "differs from
     * the header's."} as a clause of its own, and one that had lost its own {@code "Exit 5 when
     * the"}. And the terminator split was not bracket-aware, so it cut at the semicolons INSIDE
     * the firestarter seed's parentheticals: {@code "Books: POST /books (create;"} ·
     * {@code "title required, max 120 chars;"}. Joining the lines alone makes those fragments
     * LONGER rather than whole, which is why the two had to be fixed together.
     *
     * <p>{@code SeedRequirements} had already met and fixed exactly this, on exactly this
     * sentence. Sharing that pipeline is the point: a second copy is how the fix failed to arrive
     * here the first time ([[feedback-one-invariant-one-home]]).
     *
     * <p>BOTH of its stages are needed. Taking the bracket-aware sentence scan alone swung the
     * error the other way — the whole Loans paragraph became one 250-character clause, and a goal
     * covering one of its several obligations could no longer clear it. The second stage splits a
     * sentence long enough to be bundling obligations back into separable ones.
     */
    private List<String> clausesOf(List<String> obligations) {
        List<String> out = new ArrayList<>();
        // T6-S: the obligations file, when the target has one, IS the clause list -- reviewed,
        // correctable, and identical on every run. Its absence falls through to the derived split,
        // so a target without one behaves exactly as before.
        for (String sentence : obligations) {
            // Whitespace is normalised because a clause reaches three prompts verbatim, and a
            // sentence rejoined across a wrap otherwise carries its line breaks into them.
            String clause = sentence.strip().replaceAll("\\s+", " ");
            if (termsOf(clause).size() >= MIN_CLAUSE_TERMS) out.add(clause);
        }
        return out;
    }

    /**
     * Terms are lowercased and single characters are dropped unless they carry a digit, so a
     * stated numeric bound ("3 active loans") is checkable while stray letters are not.
     */
    private List<String> termsOf(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String raw : NON_TERM.split(text.toLowerCase())) {
            if (raw.isEmpty()) continue;
            if (raw.length() >= 3 || raw.chars().anyMatch(Character::isDigit)) out.add(canonical(raw));
        }
        return out;
    }

    /**
     * Both sides of the comparison are prose about the same system, so a term is reduced to a
     * canonical form before matching: without it a specification saying "list all loans" and a
     * work item saying "lists all loans" disagree on {@code list}, and the check reports a
     * requirement as unarticulated purely on a plural. This is a canonical form, not a
     * dictionary — the same rule runs over both texts, so an imperfect stem still matches
     * itself, and it can only ever remove a finding, never invent one.
     */
    private String canonical(String term) {
        return term.length() >= 5 && term.endsWith("s") ? term.substring(0, term.length() - 1) : term;
    }

    @SuppressWarnings("unchecked")
    private List<String> criteriaOf(GoalEntity goal) {
        if (goal.getMetadata() == null) return List.of();
        Object raw = goal.getMetadata().get("acceptanceCriteria");
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o != null) out.add(o.toString());
        }
        return out;
    }

    /**
     * The share of a clause's terms the plan must mention before the clause counts as claimed.
     *
     * <p><b>Chosen by sweep, not by taste</b> (2026-08-28), against two independent checks at once:
     * run 2026-08-28_20-37-30's archived GoalStack, and this class's own tests.
     * <pre>
     *   0.5 → 5 unclaimed · 1 test failing (loses the isbn-format finding a test exists to keep)
     *   0.6 → 7 unclaimed · 0 failing        ← chosen
     *   0.7 → 11 unclaimed · 2 failing
     *   0.8 → 14 unclaimed · 2 failing (approaching the all-terms rule's 17-of-19 noise)
     * </pre>
     * 0.6 is the only value where the archived run's readout is defensible AND no existing
     * assertion breaks. The tension is real and worth naming: the clause splitter sometimes glues
     * two requirements together ("isbn must match \d{13}), GET /books (list all)"), so a plan that
     * covers one of them engages half the clause. A lower bar loses the uncovered half; a higher
     * bar re-condemns clauses the plan plainly articulates.
     */
    private static final double CLAIMED_ENGAGEMENT = 0.6;

    private List<String> absent(Set<String> terms, Set<String> vocabulary) {
        List<String> missing = new ArrayList<>();
        for (String t : terms) {
            if (!vocabulary.contains(t)) missing.add(t);
        }
        return missing;
    }

    /** A finding with the two keys that order it: how engaged the plan is, then the seed's order. */
    private record Ranked(SeedCoverageReadout.Finding finding, double engagement, int seedOrder) {}

    /**
     * The share of a clause's terms the plan does mention. It orders the readout, so it decides
     * what survives if a consumer has to truncate, and the direction is deliberate: a clause the
     * plan has ALREADY engaged with and left incomplete is a requirement half-articulated, while
     * a clause the plan barely touches is usually the specification's framing prose ("Evolve the
     * … application into …") that no work item will ever mirror term for term.
     * <p>
     * Measured over the three frozen runs it does move the two requirements that failed in every
     * one of them up the list — but not reliably into any fixed number of leading places, which
     * is why the count cap was removed rather than tuned. Ordering is a preference; it is not a
     * filter, and it was not treated as one.
     */
    private double engagement(Set<String> terms, List<String> missing) {
        if (terms.isEmpty()) return 0.0;
        return 1.0 - ((double) missing.size() / terms.size());
    }

    private List<SeedCoverageReadout.Finding> cap(List<Ranked> ranked) {
        return ranked.stream()
                .sorted(java.util.Comparator
                        .comparingDouble(Ranked::engagement).reversed()
                        .thenComparingInt(Ranked::seedOrder))
                .limit(SANITY_CEILING)
                .map(Ranked::finding)
                .toList();
    }
}
