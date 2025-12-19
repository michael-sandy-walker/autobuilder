package com.walpro.autobuilder.goal;

import java.util.HashSet;
import java.util.Set;

import org.simmetrics.StringMetric;
import org.simmetrics.metrics.StringMetrics;
import org.springframework.stereotype.Service;

@Service
public class GoalSimilarityService {

    private final Set<String> knownSubgoals = new HashSet<>();
    private final StringMetric metric = StringMetrics.mongeElkan(); // or jaccard(), diceSimilarity()

    private static final float SIMILARITY_THRESHOLD = 0.88f;

    public boolean isDuplicate(String candidate) {
        for (String known : knownSubgoals) {
            float score = metric.compare(candidate, known);
            if (score >= SIMILARITY_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    public float similarity(String a, String b) {
        if (a == null || b == null) return 0.0f;
        String aa = a.trim();
        String bb = b.trim();
        if (aa.isEmpty() || bb.isEmpty()) return 0.0f;
        return metric.compare(aa, bb);
    }

    public void register(String goal) {
        knownSubgoals.add(goal);
    }

    public void clear() {
        knownSubgoals.clear();
    }
}
