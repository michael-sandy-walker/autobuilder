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

    private final StringMetric metric = StringMetrics.mongeElkan();
    private static final float SIMILARITY_THRESHOLD = 0.88f;
    private static final int CANDIDATE_LIMIT = 200;

    public boolean isDuplicate(String candidate) {
        if (candidate == null) return false;
        String c = normalize(candidate);
        if (c.isBlank()) return false;

        List<String> all = goalGraphService.findAllDescriptions(CANDIDATE_LIMIT);
        for (String known : all) {
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
}
