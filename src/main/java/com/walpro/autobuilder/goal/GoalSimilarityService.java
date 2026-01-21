package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.graph.goal.GoalGraphService;
import lombok.RequiredArgsConstructor;
import org.simmetrics.StringMetric;
import org.simmetrics.metrics.StringMetrics;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GoalSimilarityService {

    private final GoalGraphService goalGraphService;

    private final StringMetric metric = StringMetrics.mongeElkan(); // or jaccard(), diceSimilarity()
    private static final float SIMILARITY_THRESHOLD = 0.88f;

    public boolean isDuplicate(String candidate) {
        if (candidate == null) return false;
        String c = normalize(candidate);
        if (c.isBlank()) return false;

        String query = coarseQuery(c);
        List<String> candidates = goalGraphService.findDescriptionCandidates(query, 50);

        for (String known : candidates) {
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

    /**
     * Graph-backed now: register/clear are intentionally no-ops to preserve call sites.
     */
    public void register(String goal) {
        // no-op (graph is authoritative)
    }

    public void clear() {
        // no-op (graph is authoritative)
    }


    private static String normalize(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    /**
     * Cheap narrowing string for the graph query.
     * Uses a short prefix to keep candidate set small without indexes.
     */
    private static String coarseQuery(String normalized) {
        String n = normalized.toLowerCase();
        if (n.length() <= 12) return n;
        return n.substring(0, 12);
    }
}
