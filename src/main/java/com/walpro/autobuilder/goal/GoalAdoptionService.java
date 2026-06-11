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
 * <p>Cost-bounding: callers never get pairwise N×N LLM calls. The candidate set is shortlisted
 * by the cheap string metric ({@link GoalSimilarityService#similarity}) and the survivors go to
 * the LLM in <b>one</b> call that picks a match (or NONE) with a confidence.
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

    /** Max candidates forwarded to the LLM after string-metric ranking. */
    static final int SHORTLIST_SIZE = 5;

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
     * qualifies. One LLM call against the string-metric shortlist.
     */
    public Optional<GoalEntity> findAdoptiveParent(GoalEntity orphan, List<GoalEntity> activeCandidates) {
        return review(orphan, activeCandidates, this::buildPrompt).map(ReviewedMatch::goal);
    }

    /**
     * T4-AJ Part B: picks the candidate naming the <i>same piece of work</i> as the subject —
     * completing one would make the other redundant — or empty when none qualifies. Sub-tasks
     * and prerequisites are explicitly not matches. One LLM call against the shortlist.
     */
    public Optional<ReviewedMatch> findEquivalent(GoalEntity subject, List<GoalEntity> candidates) {
        return review(subject, candidates, this::buildEquivalencePrompt);
    }

    private Optional<ReviewedMatch> review(GoalEntity subject, List<GoalEntity> candidates,
                                           java.util.function.BiFunction<GoalEntity, List<GoalEntity>, String> prompt) {
        if (subject == null || subject.getDescription() == null
                || candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }

        List<GoalEntity> shortlist = candidates.stream()
                .filter(c -> c != null && c.getDescription() != null && !c.getId().equals(subject.getId()))
                .sorted(Comparator.comparingDouble(
                        (GoalEntity c) -> goalSimilarityService.similarity(subject.getDescription(), c.getDescription()))
                        .reversed())
                .limit(SHORTLIST_SIZE)
                .toList();
        if (shortlist.isEmpty()) return Optional.empty();

        try {
            List<Message> messages = List.of(
                    new Message("system", SYSTEM_PROMPT),
                    new Message("user", prompt.apply(subject, shortlist)));
            ResponseEntity<ChatResponse> response = llmClient.ask(messages);
            if (response.getBody() == null || response.getBody().getFirstContent() == null) {
                log.warn("T4-AJ: empty LLM response — no match");
                return Optional.empty();
            }
            return parseMatchResponse(response.getBody().getFirstContent(), shortlist);
        } catch (Exception e) {
            log.warn("T4-AJ: reviewer LLM call failed ({}) — no match", e.getMessage());
            return Optional.empty();
        }
    }

    private String buildPrompt(GoalEntity orphan, List<GoalEntity> shortlist) {
        StringBuilder sb = new StringBuilder();
        sb.append("ORPHANED WORK ITEM (its parent was retired; it carries merged work):\n");
        sb.append(orphan.getDescription()).append("\n\n");
        sb.append("CANDIDATE WORK ITEMS (active elsewhere in the plan):\n");
        for (int i = 0; i < shortlist.size(); i++) {
            sb.append(i + 1).append(". ").append(shortlist.get(i).getDescription()).append("\n");
        }
        sb.append("""

                Which candidate (if any) does the orphan logically belong with — the same piece of work, or a direct sub-task of it?
                Respond with exactly two lines:
                Match: <candidate number, or NONE>
                Confidence: <0.0-1.0>
                """);
        return sb.toString();
    }

    private String buildEquivalencePrompt(GoalEntity subject, List<GoalEntity> shortlist) {
        StringBuilder sb = new StringBuilder();
        sb.append("WORK ITEM UNDER REVIEW:\n");
        sb.append(subject.getDescription()).append("\n\n");
        sb.append("OTHER WORK ITEMS IN THE SAME PLAN:\n");
        for (int i = 0; i < shortlist.size(); i++) {
            sb.append(i + 1).append(". ").append(shortlist.get(i).getDescription()).append("\n");
        }
        sb.append("""

                Which other item (if any) names the SAME piece of work, such that completing one makes the other redundant?
                A sub-task, prerequisite, or component of an item is NOT the same work — answer NONE for those.
                Respond with exactly two lines:
                Match: <item number, or NONE>
                Confidence: <0.0-1.0>
                """);
        return sb.toString();
    }

    /** Parses {@code Match:}/{@code Confidence:} lines; empty on NONE, malformed, or low confidence. */
    Optional<ReviewedMatch> parseMatchResponse(String raw, List<GoalEntity> shortlist) {
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
        if (idx < 0 || idx >= shortlist.size()) return Optional.empty();
        log.info("T4-AJ: reviewer matched subject to candidate {} (confidence {})",
                shortlist.get(idx).getId(), confidence);
        return Optional.of(new ReviewedMatch(shortlist.get(idx), confidence));
    }
}
