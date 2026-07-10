package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.core.tuning.TuningParameterStore;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class CycleStartSignalSeeder {

    // Boost tuning: enough to rank, not “permanent”
    private static final double STRONG = 0.80;
    private static final double MEDIUM = 0.50;
    private static final double WEAK = 0.20;

    // T4-BZ-4: the old decay constants survive as decay-equivalents — they state each seed
    // window's validity horizon (via the shared lifetime formula), so an unrefreshed seed
    // expires at exactly the age the old decay arithmetic killed it.
    private static final double FAST_DECAY = 0.25;
    private static final double NORMAL_DECAY = 0.15;

    // T4-J: the seed decay-equivalent is the BIRTH authority for a role's window lifetime —
    // ParticipationWindow.grant keeps the minimum decay-equivalent (the old reinforce ratchet),
    // so later re-grants with larger constants are min'd away and the seed value effectively
    // governs the role's window horizon for the whole run. ROLE_ANALYSIS seeds at 0.0 (window
    // never expires; its participation shrinks via no-progress attenuation only) — the T4-J
    // replay measured this constant as the most decision-sensitive in the system, hence the
    // tuning-store hook on exactly the analysis and planner seeds.
    private static final double ANALYSIS_SEED_DECAY_DEFAULT = 0.0;

    private static final String SOURCE = "cycle-start";

    /** T4-J Phase 2: optional — tests and manual construction keep code defaults. */
    @Setter
    @Autowired(required = false)
    private TuningParameterStore tuningParameterStore;

    public CycleContextData seedIfEmpty(CycleContextData ctx) {
        if (ctx == null) return null;

        // T4-BZ-3/4: carry-over on either selection carrier suppresses re-seeding — open
        // channel requests or active participation windows. Bootstrap grants only when
        // NOTHING carried.
        if (!ctx.requests().isEmpty() || !ctx.windows().isEmpty()) {
            return ctx;
        }

        boolean hasGoal =
                ctx.currentSubGoalId() != null
                        || ctx.currentGoalId() != null
                        || (ctx.currentGoalDescription() != null && !ctx.currentGoalDescription().isBlank());

        double analysisDecay = tuned("signal-decay.role-analysis.seed", ANALYSIS_SEED_DECAY_DEFAULT);

        if (!hasGoal) {
            // Start-from-nothing: planning must happen first
            ctx = grant(ctx, "planner", STRONG, tuned("signal-decay.role-planner.seed", FAST_DECAY));
            ctx = grant(ctx, "evolution", WEAK, NORMAL_DECAY);
            ctx = grant(ctx, "evaluation", WEAK, NORMAL_DECAY);
            ctx = grant(ctx, "merge", WEAK, NORMAL_DECAY);
            ctx = grant(ctx, "analysis", WEAK, analysisDecay);
        } else {
            // Goal exists: default forward motion is evolve -> evaluate
            ctx = grant(ctx, "evolution", STRONG, FAST_DECAY);
            ctx = grant(ctx, "evaluation", MEDIUM, NORMAL_DECAY);
            ctx = grant(ctx, "planner", WEAK, tuned("signal-decay.role-planner.seed", NORMAL_DECAY));
            ctx = grant(ctx, "merge", WEAK, NORMAL_DECAY);
            ctx = grant(ctx, "analysis", WEAK, analysisDecay);
        }

        return ctx;
    }

    private double tuned(String name, double codeDefault) {
        return tuningParameterStore != null ? tuningParameterStore.get(name, codeDefault) : codeDefault;
    }

    private static CycleContextData grant(CycleContextData ctx, String role, double boost, double decayEquivalent) {
        return ctx.grantWindow(ParticipationWindow.of(role, boost, decayEquivalent, SOURCE));
    }
}
