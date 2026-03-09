package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.graph.goal.GoalGraphService;
import lombok.RequiredArgsConstructor;
import org.simmetrics.StringMetric;
import org.simmetrics.metrics.StringMetrics;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class GoalSimilarityService {

    private final GoalGraphService goalGraphService;

    private final StringMetric metric = StringMetrics.mongeElkan(); // or jaccard(), diceSimilarity()
    private static final float SIMILARITY_THRESHOLD = 0.88f;

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "from", "this", "that", "with", "are", "were",
            "have", "been", "will", "would", "could", "should", "into", "onto",
            "then", "than", "when", "where", "some", "more", "over", "under",
            "after", "before", "their", "there", "these", "those", "about");

    public boolean isDuplicate(String candidate) {
        if (candidate == null) return false;
        String c = normalize(candidate);
        if (c.isBlank()) return false;

        List<String> keywords = extractKeywords(c);
        Set<String> seen = new LinkedHashSet<>();
        for (String kw : keywords) {
            List<String> partial = goalGraphService.findDescriptionCandidates(kw, 50);
            if (partial != null) seen.addAll(partial);
        }

        for (String known : seen) {
            if (known == null) continue;
            float score = metric.compare(c, normalize(known));
            if (score >= SIMILARITY_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    public float similarity(String a, String b) {
        if (a == null || b == null) return 0.0f;
        String aa = normalize(a);
        String bb = normalize(b);
        if (aa.isBlank() || bb.isBlank()) return 0.0f;
        return metric.compare(aa, bb);
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    /**
     * Extracts up to 3 content keywords (length > 4, not a stop word) from a
     * normalised description. Keyword-based candidate expansion yields a far
     * richer candidate set than a 12-char prefix, catching paraphrase variants
     * whose first token differs from the stored description.
     */
    static List<String> extractKeywords(String normalized) {
        String[] words = normalized.toLowerCase().split("\\s+");
        List<String> keywords = new ArrayList<>();
        for (String w : words) {
            if (w.length() > 4 && !STOP_WORDS.contains(w)) {
                keywords.add(w);
            }
            if (keywords.size() >= 3) break;
        }
        if (keywords.isEmpty()) {
            // fallback: use prefix
            String lower = normalized.toLowerCase();
            keywords.add(lower.substring(0, Math.min(12, lower.length())));
        }
        return keywords;
    }
}
