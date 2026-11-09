package com.walpro.autobuilder.core.coldread;

import com.walpro.autobuilder.core.build.CriterionProbeSynthesizer.ProbeRequest;
import com.walpro.autobuilder.core.coldread.ClaimProposer.Claim;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.Observation;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.Variant;
import com.walpro.autobuilder.core.merge.CapabilityCut.Capability;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReverseEngineeringJudgeTest {

    private final Claim claim = new Claim("k1", "c1", "GET /books/99 returns HTTP 404",
            List.of(new ProbeRequest("GET", "/books/99", null)), 404);
    private final List<Capability> caps = List.of(cap("c1"), cap("c2"), cap("c3"));
    private final Map<String, Variant> ran = Map.of(
            "as-is", variant(null, true), "c1", variant("c1", true), "c2", variant("c2", true), "c3", variant("c3", true));

    @Test
    void anchoredWhenItHoldsAndItsOwnCapabilityTakenOutBreaksIt() {
        var j = ReverseEngineeringService.judge(claim, obs(404, 500, 404, 500), ran, caps);
        assertThat(j.verdict()).isEqualTo("anchored");
        assertThat(j.alsoNeeds()).containsExactly("c3");
    }

    @Test
    void notAnchoredWhenTheGuttedTreeStillBearsItOut() {
        assertThat(ReverseEngineeringService.judge(claim, obs(404, 404, 404, 404), ran, caps).verdict())
                .isEqualTo("not-anchored");
    }

    @Test
    void notHeldWhenTheTreeAsItIsAnswersOtherwise() {
        var j = ReverseEngineeringService.judge(claim, obs(400, 500, 400, 400), ran, caps);
        assertThat(j.verdict()).isEqualTo("not-held");
        assertThat(j.alsoNeeds()).isEmpty();
    }

    @Test
    void anUnfinishedSequenceOnTheGuttedTreeCountsAsNotBearingItOut() {
        Map<String, Observation> o = obs(404, 404, 404, 404);
        o.put("c1", new Observation("c1", null, "SETUP_REFUSED", null, false));
        assertThat(ReverseEngineeringService.judge(claim, o, ran, caps).verdict()).isEqualTo("anchored");
    }

    @Test
    void unjudgedWhenItsOwnVariantNeverRan() {
        Map<String, Variant> v = new LinkedHashMap<>(ran);
        v.put("c1", variant("c1", false));
        assertThat(ReverseEngineeringService.judge(claim, obs(404, null, 404, 404), v, caps).verdict()).isEqualTo("unjudged");
    }

    @Test
    void unjudgedWhenItsCapabilityHadNothingToTakeOut() {
        Map<String, Variant> v = new LinkedHashMap<>(ran);
        v.put("c1", new Variant("c1", List.of(), true, true, null));
        assertThat(ReverseEngineeringService.judge(claim, obs(404, 404, 404, 404), v, caps).verdict()).isEqualTo("unjudged");
    }

    private Map<String, Observation> obs(Integer asIs, Integer c1, Integer c2, Integer c3) {
        Map<String, Observation> m = new LinkedHashMap<>();
        m.put("as-is", o("as-is", asIs));
        if (c1 != null) m.put("c1", o("c1", c1));
        m.put("c2", o("c2", c2));
        m.put("c3", o("c3", c3));
        return m;
    }

    private Observation o(String v, Integer status) {
        return new Observation(v, status, null, null, status != null && status == claim.expected());
    }

    private static Capability cap(String id) {
        return new Capability(id, id, "", List.of(), List.of(), false, null);
    }

    private static Variant variant(String c, boolean ran) {
        return new Variant(c, List.of(c + ".m"), ran, ran, null);
    }
}
