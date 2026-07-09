package com.walpro.autobuilder.core.tuning;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * T4-J Phase 2: read path for tuning parameters.
 *
 * Resolution order: {@code autobuilder.tuning.<name>} from the Spring environment (yml,
 * env var, -D override — the channel field runs and preflight flags already use) → the
 * caller's code default. Values are cached after first read so a parameter is stable for
 * the lifetime of a run (per-run comparability; a changed value means a restart, which is
 * how the A/B field pattern works anyway).
 *
 * Parameter names in use (see project_signal_dispositions.md for why exactly these):
 * - score.merge-acceptance-floor      (DeterministicScoreHeuristic, EvaluationInterpreter)
 * - score.subgoal-closure             (SubGoalFulfillmentHeuristic)
 * - score.plateau-override-floor      (SubGoalFulfillmentHeuristic)
 * - signal-decay.role-analysis.seed   (CycleStartSignalSeeder — birth decay authority)
 * - signal-decay.role-planner.seed    (CycleStartSignalSeeder)
 * - signal-decay.reinforce-default    (AdaptiveExecutionEngine — the ratchet floor:
 *   Signal.reinforce keeps the MINIMUM decay, so this and the seed values are the effective
 *   per-role decay; later re-emissions with larger constants are min'd away)
 *
 * The write path is deliberately a stub (T4-J scope): no autonomous self-tuning.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TuningParameterStore {

    public static final String PREFIX = "autobuilder.tuning.";

    private final Environment environment;
    private final Map<String, Double> cache = new ConcurrentHashMap<>();

    /** Resolve a tuning parameter: environment property first, code default otherwise. */
    public double get(String name, double codeDefault) {
        return cache.computeIfAbsent(name, n -> {
            Double configured = environment.getProperty(PREFIX + n, Double.class);
            if (configured != null) {
                log.info("Tuning parameter {}{} = {} (overrides code default {})",
                        PREFIX, n, configured, codeDefault);
                return configured;
            }
            return codeDefault;
        });
    }

    /** T4-J scope: no autonomous write path. Present so callers fail loudly, not silently. */
    public void set(String name, double value) {
        throw new UnsupportedOperationException(
                "TuningParameterStore is read-only by design (T4-J); set via configuration.");
    }
}
