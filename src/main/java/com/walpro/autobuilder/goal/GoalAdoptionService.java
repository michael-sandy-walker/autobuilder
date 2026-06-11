package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-AJ: the shared semantic goal-equivalence reviewer. Answers one question — "do these two
 * goal descriptions name the same piece of work / belong together?" — in one place, so every
 * consumer applies the same equivalence notion (see feedback_one_invariant_one_home; the
 * measured failure of MongeElkan @ 0.88 on 2026-06-04 is why this is LLM-assessed: 6 of 7 real
 * duplicates scored below threshold while a genuinely distinct pair scored above it).
 *
 * <p>Cost-bounding: callers never get pairwise N×N LLM calls — all candidates go to the LLM in
 * <b>one</b> call that picks a match (or NONE) with a confidence. The candidate list is ordered
 * by the cheap string metric ({@link GoalSimilarityService#similarity}) but deliberately not
 * truncated to a fixed size: the metric is measured-unreliable at ranking same-work-vs-similar-words
 * (2026-06-04), so a head-cut could drop the true match while keeping lexical noise. Callers bound
 * cost structurally — per-pass LLM call budgets and relevance floors — not by hiding candidates.
 *
 * <p>Consumer Part A — orphan adoption: {@link #findAdoptiveParent} is consulted by
 * {@code GoalPlannerService}'s give-up cascade before re-parenting a merge-bearing child to its
 * nearest ACTIVE ancestor; a semantically compatible ACTIVE goal anywhere in the tree wins over
 * the purely structural choice.
 *
 * <p>Consumer Part B — tree consolidation: {@link #findEquivalent} is consulted by
 * {@code GoalConsolidationService} to find a goal naming the <i>same piece of work</i> as the
 * subject. Stricter question than adoption: a sub-task or prerequisite belongs together with its
 * parent but is not the same work and must not be folded.
 *
 * <p>Fallback invariant: on any LLM failure, parse failure, or below-threshold confidence this
 * returns {@link Optional#empty()} — correctness never depends on the reviewer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoalAdoptionService {

    /** Minimum LLM-reported confidence for a match to be acted on. */
    static final double CONFIDENCE_THRESHOLD = 0.7;

    private static final Pattern MATCH_LINE = Pattern.compile("(?im)^\\s*Match:\\s*(NONE|\\d+)\\s*$");
    private static final Pattern CONFIDENCE_LINE = Pattern.compile("(?im)^\\s*Confidence:\\s*([0-9]*\\.?[0-9]+)\\s*$");

    private static final String SYSTEM_PROMPT = """
            You assess whether two software work-item descriptions name the same piece of work or logically belong together.
            Base the answer on what code the work would touch and what behaviour it delivers, not on surface wording.
            Answer strictly in the requested format with no extra commentary.
            """;

    private final LLMClient llmClient;
    private final GoalSimilarityService goalSimilarityService;

    /** A reviewer match together with the confidence the LLM reported for it. */
    public record ReviewedMatch(GoalEntity goal, double confidence) {}

    /**
     * Picks the ACTIVE candidate the orphan's work logically belongs with, or empty when none
     * qualifies. One LLM call against the similarity-ordered candidate list.
     */
    public Optional<GoalEntity> findAdoptiveParent(GoalEntity orphan, List<GoalEntity> activeCandidates) {
        return review(orphan, activeCandidates, this::buildPrompt).map(ReviewedMatch::goal);
    }

    /**
     * T4-AJ Part B: picks the candidate naming the <i>same piece of work</i> as the subject —
     * completing one would make the other redundant — or empty when none qualifies. Sub-tasks
     * and prerequisites are explicitly not matches.
     *
     * <p>Symmetry-confirmed (2026-06-11 21:46 field finding): the list-form answer is not stable
     * under swapping which goal is the subject — the same model matched GET-list ↔ POST-return at
     * 0.9 in one direction after answering NONE at 1.0 in the other. Equivalence is symmetric, so
     * a proposed match is confirmed by one focused pair review with the roles swapped; disagreement
     * means no fold. Two LLM calls per <i>proposed</i> match (callers' per-pass budgets bound it).
     */
    public Optional<ReviewedMatch> findEquivalent(GoalEntity subject, List<GoalEntity> candidates) {
        return findEquivalent(subject, candidates, new java.util.HashSet<>());
    }

    /**
     * Memo-aware variant: {@code unconfirmedPairs} holds unordered id-pair keys whose proposed
     * match was already refused under role swap. Candidates forming such a pair with the subject
     * are excluded up front, and a fresh refusal is recorded into the set. Without this, the same
     * unstable pair was re-proposed and re-refused every consolidation pass (observed 2026-06-11
     * 21:55-21:59: one pair re-reviewed 5x across both orderings), exhausting the caller's call
     * budget before new pairs were ever reviewed.
     */
    public Optional<ReviewedMatch> findEquivalent(GoalEntity subject, List<GoalEntity> candidates,
                                                  java.util.Set<String> unconfirmedPairs) {
        List<GoalEntity> notYetRefused = candidates == null ? List.of() : candidates.stream()
                .filter(c -> c != null && !unconfirmedPairs.contains(pairKey(subject, c)))
                .toList();
        Optional<ReviewedMatch> proposed = review(subject, notYetRefused, this::buildEquivalencePrompt);
        if (proposed.isEmpty()) return proposed;

        Optional<ReviewedMatch> reverse = review(proposed.get().goal(), List.of(subject),
                this::buildEquivalencePrompt);
        if (reverse.isEmpty()) {
            log.info("T4-AJ: match {} <-> {} not confirmed under role swap — no fold",
                    subject.getId(), proposed.get().goal().getId());
            unconfirmedPairs.add(pairKey(subject, proposed.get().goal()));
            return Optional.empty();
        }
        return proposed;
    }

    /** Unordered pair key — same key regardless of which goal plays the subject. */
    static String pairKey(GoalEntity a, GoalEntity b) {
        return a.getId().compareTo(b.getId()) < 0
                ? a.getId() + "|" + b.getId()
                : b.getId() + "|" + a.getId();
    }

    private Optional<ReviewedMatch> review(GoalEntity subject, List<GoalEntity> candidates,
                                           java.util.function.BiFunction<GoalEntity, List<GoalEntity>, String> prompt) {
        if (subject == null || subject.getDescription() == null
                || candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }

        List<GoalEntity> rankedCandidates = candidates.stream()
                .filter(c -> c != null && c.getDescription() != null && !c.getId().equals(subject.getId()))
                .sorted(Comparator.comparingDouble(
                        (GoalEntity c) -> goalSimilarityService.similarity(subject.getDescription(), c.getDescription()))
                        .reversed())
                .toList();
        if (rankedCandidates.isEmpty()) return Optional.empty();

        try {
            List<Message> messages = List.of(
                    new Message("system", SYSTEM_PROMPT),
                    new Message("user", prompt.apply(subject, rankedCandidates)));
            ResponseEntity<ChatResponse> response = llmClient.ask(messages);
            if (response.getBody() == null || response.getBody().getFirstContent() == null) {
                log.warn("T4-AJ: empty LLM response — no match");
                return Optional.empty();
            }
            return parseMatchResponse(response.getBody().getFirstContent(), rankedCandidates);
        } catch (Exception e) {
            log.warn("T4-AJ: reviewer LLM call failed ({}) — no match", e.getMessage());
            return Optional.empty();
        }
    }

    private String buildPrompt(GoalEntity orphan, List<GoalEntity> rankedCandidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("ORPHANED WORK ITEM (its parent was retired; it carries merged work):\n");
        sb.append(orphan.getDescription()).append("\n\n");
        sb.append("CANDIDATE WORK ITEMS (active elsewhere in the plan):\n");
        for (int i = 0; i < rankedCandidates.size(); i++) {
            sb.append(i + 1).append(". ").append(rankedCandidates.get(i).getDescription()).append("\n");
        }
        sb.append("""

                Which candidate (if any) does the orphan logically belong with — the same piece of work, or a direct sub-task of it?
                Respond with exactly two lines:
                Match: <candidate number, or NONE>
                Confidence: <0.0-1.0>
                """);
        return sb.toString();
    }

    private String buildEquivalencePrompt(GoalEntity subject, List<GoalEntity> rankedCandidates) {
        // First field run (2026-06-11 02:08) produced description-only false positives at 0.85-0.95
        // confidence on same-file-different-rule pairs ("bookId 404" folded into "memberId 404").
        // Acceptance criteria separate such pairs crisply, so they go into the prompt.
        StringBuilder sb = new StringBuilder();
        sb.append("WORK ITEM UNDER REVIEW:\n");
        sb.append(subject.getDescription()).append("\n");
        appendCriteria(sb, subject);
        sb.append("\nOTHER WORK ITEMS IN THE SAME PLAN:\n");
        for (int i = 0; i < rankedCandidates.size(); i++) {
            sb.append(i + 1).append(". ").append(rankedCandidates.get(i).getDescription()).append("\n");
            appendCriteria(sb, rankedCandidates.get(i));
        }
        sb.append("""

                Which other item (if any) names the SAME piece of work, such that completing one makes the other redundant?
                Items whose acceptance criteria check different rules, different fields, or different endpoints are NOT the same work.
                A sub-task, prerequisite, or component of an item is NOT the same work — answer NONE for those.
                Respond with exactly two lines:
                Match: <item number, or NONE>
                Confidence: <0.0-1.0>
                """);
        return sb.toString();
    }

    /** Renders all acceptance criteria as indented bullet lines, untruncated. Every criterion —
     *  and every part of one — can be what distinguishes two near-identical goals. Cost stays
     *  bounded by call budgets, not by hiding evidence. */
    private static void appendCriteria(StringBuilder sb, GoalEntity goal) {
        if (goal.getMetadata() == null) return;
        Object raw = goal.getMetadata().get("acceptanceCriteria");
        if (!(raw instanceof List<?> l) || l.isEmpty()) return;
        for (Object o : l) {
            if (!(o instanceof String c) || c.isBlank()) continue;
            sb.append("   - ").append(c).append("\n");
        }
    }

    /** Parses {@code Match:}/{@code Confidence:} lines; empty on NONE, malformed, or low confidence. */
    Optional<ReviewedMatch> parseMatchResponse(String raw, List<GoalEntity> rankedCandidates) {
        if (raw == null || raw.isBlank()) return Optional.empty();

        Matcher match = MATCH_LINE.matcher(raw);
        Matcher conf = CONFIDENCE_LINE.matcher(raw);
        if (!match.find() || !conf.find()) {
            log.warn("T4-AJ: malformed reviewer response — no adoptive parent. Raw: {}",
                    raw.length() > 200 ? raw.substring(0, 200) : raw);
            return Optional.empty();
        }
        String picked = match.group(1).toUpperCase(Locale.ROOT);
        if ("NONE".equals(picked)) return Optional.empty();

        double confidence;
        try {
            confidence = Double.parseDouble(conf.group(1));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (confidence < CONFIDENCE_THRESHOLD) {
            log.info("T4-AJ: reviewer matched candidate {} but confidence {} < {} — not acting",
                    picked, confidence, CONFIDENCE_THRESHOLD);
            return Optional.empty();
        }

        int idx = Integer.parseInt(picked) - 1;
        if (idx < 0 || idx >= rankedCandidates.size()) return Optional.empty();
        log.info("T4-AJ: reviewer matched subject to candidate {} (confidence {})",
                rankedCandidates.get(idx).getId(), confidence);
        return Optional.of(new ReviewedMatch(rankedCandidates.get(idx), confidence));
    }
}
