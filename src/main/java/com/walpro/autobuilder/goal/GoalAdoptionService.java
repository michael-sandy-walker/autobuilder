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
 * the purely structural choice. Part B (ACTIVE-tree consolidation) is additive and reuses the
 * same reviewer.
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
            Judge by what code the work would touch and what behaviour it delivers, not by surface wording.
            Answer strictly in the requested format with no extra commentary.
            """;

    private final LLMClient llmClient;
    private final GoalSimilarityService goalSimilarityService;

    /**
     * Picks the ACTIVE candidate the orphan's work logically belongs with, or empty when none
     * qualifies. One LLM call against the string-metric shortlist.
     */
    public Optional<GoalEntity> findAdoptiveParent(GoalEntity orphan, List<GoalEntity> activeCandidates) {
        if (orphan == null || orphan.getDescription() == null
                || activeCandidates == null || activeCandidates.isEmpty()) {
            return Optional.empty();
        }

        List<GoalEntity> shortlist = activeCandidates.stream()
                .filter(c -> c != null && c.getDescription() != null && !c.getId().equals(orphan.getId()))
                .sorted(Comparator.comparingDouble(
                        (GoalEntity c) -> goalSimilarityService.similarity(orphan.getDescription(), c.getDescription()))
                        .reversed())
                .limit(SHORTLIST_SIZE)
                .toList();
        if (shortlist.isEmpty()) return Optional.empty();

        try {
            List<Message> messages = List.of(
                    new Message("system", SYSTEM_PROMPT),
                    new Message("user", buildPrompt(orphan, shortlist)));
            ResponseEntity<ChatResponse> response = llmClient.ask(messages);
            if (response.getBody() == null || response.getBody().getFirstContent() == null) {
                log.warn("T4-AJ: empty LLM response — no adoptive parent");
                return Optional.empty();
            }
            return parseMatchResponse(response.getBody().getFirstContent(), shortlist);
        } catch (Exception e) {
            log.warn("T4-AJ: reviewer LLM call failed ({}) — no adoptive parent", e.getMessage());
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

    /** Parses {@code Match:}/{@code Confidence:} lines; empty on NONE, malformed, or low confidence. */
    Optional<GoalEntity> parseMatchResponse(String raw, List<GoalEntity> shortlist) {
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
        log.info("T4-AJ: reviewer matched orphan to candidate {} (confidence {})",
                shortlist.get(idx).getId(), confidence);
        return Optional.of(shortlist.get(idx));
    }
}
