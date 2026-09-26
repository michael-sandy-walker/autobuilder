package com.walpro.autobuilder.core.lifecycle;

/**
 * T4-BZ-4: one role's participation window — the carrier that replaces the stored ROLE_*
 * identity signals. A window is a stated grant ("this role participates, with this boost,
 * for this long"), issued by the same mechanisms that emitted role signals before: the
 * cycle-start seeder, the deliberation proposer's mode decisions, evaluation-accept (merge),
 * analysis-unlock (evolution), the deferred restager and the build-fix escalator.
 *
 * Boost is 1:1 with the old ROLE_* strength (Q-c), including the additive re-grant capped at
 * {@link ChannelRequest#MAX_URGENCY} that mirrors {@code Signal.reinforce}, and the x0.9 {@link #shrink}
 * that mirrors the engine's attenuation. Boost never decays mid-life — lifetime is the stated
 * {@code validForCycles} horizon instead (the same deliberate change the request channel made).
 *
 * {@code decayEquivalent} is a MIGRATION PIN, not window semantics: the legacy decay constant,
 * kept only to recompute the validity horizon whenever boost changes, so that an unrefreshed
 * window expires at exactly the age the old decay arithmetic would have removed the role signal
 * (including after re-grants — the min() merge reproduces the reinforce decay ratchet, so a
 * never-decays 0.0 seed like ROLE_ANALYSIS's stays never-expiring). T4-BZ-5 deletes it when
 * the tuning store re-points at window lifetimes directly.
 */
public record ParticipationWindow(
        String role,
        double boost,
        String source,
        int age,
        Integer validForCycles,
        double decayEquivalent
) {
    /** The boost floor below which a window has effectively closed (the deleted stored map's 0.05 survival mirror). */
    public static final double CLOSED_THRESHOLD = 0.05;

    public static ParticipationWindow of(String role, double boost, double decayEquivalent, String source) {
        double capped = Math.min(boost, ChannelRequest.MAX_URGENCY);
        return new ParticipationWindow(role, capped, source, 0,
                ChannelRequest.horizonFromDecay(capped, decayEquivalent), decayEquivalent);
    }

    /**
     * Re-grant merges like {@code Signal.reinforce}: additive boost capped at MAX_STRENGTH,
     * age reset to the newest grant, minimum decay-equivalent (the ratchet), validity
     * recomputed from the merged boost so expiry matches the legacy decay death exactly.
     */
    public ParticipationWindow grant(ParticipationWindow other) {
        if (!role.equals(other.role)) return this;
        double mergedBoost = Math.min(boost + other.boost, ChannelRequest.MAX_URGENCY);
        double mergedDecay = Math.min(decayEquivalent, other.decayEquivalent);
        int mergedAge = Math.min(age, other.age);
        return new ParticipationWindow(role, mergedBoost, source, mergedAge,
                validityFrom(mergedAge, mergedBoost, mergedDecay), mergedDecay);
    }

    /** The engine's no-progress attenuation, 1:1: boost x0.9, validity recomputed. */
    public ParticipationWindow shrink() {
        double shrunk = boost * 0.9;
        return new ParticipationWindow(role, shrunk, source, age,
                validityFrom(age, shrunk, decayEquivalent), decayEquivalent);
    }

    /** One cycle boundary passed. Age is the only thing that moves — boost never decays. */
    public ParticipationWindow tick() {
        return new ParticipationWindow(role, boost, source, age + 1, validForCycles, decayEquivalent);
    }

    /** Past the stated horizon, or shrunk below the survival threshold. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean expired() {
        return (validForCycles != null && age > validForCycles) || boost <= CLOSED_THRESHOLD;
    }

    private static Integer validityFrom(int age, double boost, double decayEquivalent) {
        Integer remaining = ChannelRequest.horizonFromDecay(boost, decayEquivalent);
        return remaining == null ? null : age + remaining;
    }
}
