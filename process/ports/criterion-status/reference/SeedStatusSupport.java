package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.analysis.structure.Endpoint;
import com.walpro.autobuilder.analysis.structure.EndpointPaths;
import com.walpro.autobuilder.analysis.structure.SpecEndpointBehaviourQuery;
import com.walpro.autobuilder.analysis.structure.SpecEndpointBehaviourQuery.StatedBehaviour;
import com.walpro.autobuilder.core.build.BehaviouralCriterionParser.Assertion;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * T3-CB: is the response status an acceptance criterion asserts one its specification supports?
 *
 * <p>A criterion is read by {@link BehaviouralCriterionParser#parse}: one endpoint E (verb and
 * normalized path) and one asserted status. The statuses the specification itself states for E are
 * read by {@link com.walpro.autobuilder.analysis.structure.SpecEndpointBehaviourQuery#statedBehaviours};
 * the endpoints it names at all by {@link com.walpro.autobuilder.analysis.structure.EndpointPaths#declaredIn}.
 * The verdict:
 * <ul>
 *   <li>{@link Verdict#NO_OPINION} when the criterion or the specification is null or blank, or the
 *       parser reads no single endpoint with a single status from the criterion;</li>
 *   <li>{@link Verdict#UNSUPPORTED} when the specification does not name E at all;</li>
 *   <li>for a status outside 200-299: {@link Verdict#SUPPORTED} when it is among the statuses the
 *       specification states for E, otherwise {@link Verdict#UNSUPPORTED};</li>
 *   <li>for a status in 200-299: when the specification states one or more statuses in 200-299 for
 *       E, {@link Verdict#SUPPORTED} only if the asserted status is one of them; when it states none,
 *       {@link Verdict#SUPPORTED}, because a specification that names E and states no success status
 *       for it describes what E does, and success follows from that.</li>
 * </ul>
 *
 * <p>The rule names the specification, never a status, a verb or a domain term.
 */
public final class SeedStatusSupport {

    /** Whether the specification supports the status a criterion asserts. */
    public enum Verdict { SUPPORTED, UNSUPPORTED, NO_OPINION }

    private SeedStatusSupport() {
    }

    /** The verdict for {@code criterion} under {@code specification}; see the class comment. */
    public static Verdict of(String criterion, String specification) {
        if (specification == null || specification.isBlank()) return Verdict.NO_OPINION;
        Optional<Assertion> assertion = BehaviouralCriterionParser.parse(criterion);
        if (assertion.isEmpty()) return Verdict.NO_OPINION;

        Endpoint endpoint = new Endpoint(assertion.get().verb(), assertion.get().path());
        if (!EndpointPaths.declaredIn(specification).contains(endpoint)) return Verdict.UNSUPPORTED;

        int asserted = assertion.get().expectedStatus();
        Set<Integer> stated = new SpecEndpointBehaviourQuery().statedBehaviours(specification)
                .getOrDefault(endpoint, List.of()).stream()
                .map(StatedBehaviour::status)
                .collect(Collectors.toSet());
        if (stated.contains(asserted)) return Verdict.SUPPORTED;
        boolean statesASuccess = stated.stream().anyMatch(SeedStatusSupport::isSuccess);
        return isSuccess(asserted) && !statesASuccess ? Verdict.SUPPORTED : Verdict.UNSUPPORTED;
    }

    /** The success range the rule distinguishes: 200-299. */
    private static boolean isSuccess(int status) {
        return status >= 200 && status <= 299;
    }
}
