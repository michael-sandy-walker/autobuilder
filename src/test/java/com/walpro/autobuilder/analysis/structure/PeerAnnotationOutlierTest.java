package com.walpro.autobuilder.analysis.structure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * T3-BW: the odd-one-out rule. A type whose annotation set is a strict subset of what EVERY peer
 * carries is reported — with the peer relation, the anomaly and the wording all derived from the
 * code under analysis, never from a table of framework names.
 */
class PeerAnnotationOutlierTest {

    private final WiringFactsAnalyzer analyzer = new WiringFactsAnalyzer();

    private static Map<String, String> files(String... srcs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < srcs.length; i++) m.put("F" + i + ".java", srcs[i]);
        return m;
    }

    private static String type(String name, String... annotations) {
        StringBuilder sb = new StringBuilder("package p;\n");
        for (String a : annotations) sb.append("@").append(a).append("\n");
        return sb.append("public class ").append(name).append(" { void m() {} }\n").toString();
    }

    // --- the field case that motivated the rule (run 2026-07-26_22-41) --------------------

    @Test
    void reportsTheTypeMissingWhatEveryPeerCarries() {
        List<String> obs = analyzer.analyze(files(
                type("BookController", "RestController", "RequestMapping(\"/books\")"),
                type("MemberController", "RestController", "RequestMapping(\"/members\")"),
                type("LoanController", "RequestMapping(\"/loans\")")));

        assertThat(obs).anySatisfy(o -> assertThat(o)
                .contains("LoanController")
                .contains("RestController")
                .contains("BookController"));
    }

    /** The rule must hold no ecosystem knowledge: invented annotations work identically. */
    @Test
    void worksWithAnnotationsItHasNeverHeardOf() {
        List<String> obs = analyzer.analyze(files(
                type("Alpha", "Zorp", "Quux"),
                type("Beta", "Zorp", "Quux"),
                type("Gamma", "Zorp")));

        assertThat(obs).anySatisfy(o -> assertThat(o)
                .contains("Gamma").contains("Quux").contains("Alpha").contains("Beta"));
    }

    // --- err toward silence ---------------------------------------------------------------

    @Test
    void silentWhenThePeerSetIsNotUnanimous() {
        // One dissenter makes it a style choice, not an anomaly.
        List<String> obs = analyzer.analyze(files(
                type("A", "Marker", "Extra"),
                type("B", "Marker"),
                type("C", "Marker")));

        assertThat(obs).noneSatisfy(o -> assertThat(o).contains("does not also carry"));
    }

    @Test
    void silentWhenTooFewPeersToEstablishANorm() {
        // Two types cannot establish a norm — one of them would always be "the odd one".
        List<String> obs = analyzer.analyze(files(
                type("A", "Marker", "Extra"),
                type("B", "Marker")));

        assertThat(obs).noneSatisfy(o -> assertThat(o).contains("does not also carry"));
    }

    @Test
    void silentWhenNoTypeCarriesAnnotations() {
        assertThat(analyzer.analyze(files(type("A"), type("B"), type("C"))))
                .noneSatisfy(o -> assertThat(o).contains("does not also carry"));
    }

    @Test
    void silentWhenEveryPeerAgrees() {
        List<String> obs = analyzer.analyze(files(
                type("A", "Marker", "Extra"),
                type("B", "Marker", "Extra"),
                type("C", "Marker", "Extra")));

        assertThat(obs).noneSatisfy(o -> assertThat(o).contains("does not also carry"));
    }

    @Test
    void reportsEachTypeAndMissingAnnotationOnlyOnce() {
        // Two shared markers mean the same anomaly is reachable by two peer groupings.
        List<String> obs = analyzer.analyze(files(
                type("A", "M1", "M2", "Extra"),
                type("B", "M1", "M2", "Extra"),
                type("C", "M1", "M2")));

        assertThat(obs.stream().filter(o -> o.contains("C is the only one") && o.contains("Extra")).count())
                .isEqualTo(1);
    }

    @Test
    void survivesUnparseableSourcesAlongsideValidOnes() {
        Map<String, String> m = files(
                type("A", "Marker", "Extra"),
                type("B", "Marker", "Extra"),
                type("C", "Marker"));
        m.put("Broken.java", "package p; class {{{");

        assertThat(analyzer.analyze(m)).anySatisfy(o -> assertThat(o).contains("C").contains("Extra"));
    }

    // --- the fact must reach the agent that can act on it ---------------------------------

    @Test
    void anomalyIsExposedOnItsOwnForTheEvolutionPrompt() {
        List<String> only = analyzer.peerAnnotationAnomalies(files(
                type("BookController", "RestController", "RequestMapping"),
                type("MemberController", "RestController", "RequestMapping"),
                type("LoanController", "RequestMapping")));

        assertThat(only).hasSize(1);
        assertThat(only.get(0)).contains("LoanController").contains("RestController");
    }

    @Test
    void anomalyListIsEmptyOnAConsistentArtifact() {
        assertThat(analyzer.peerAnnotationAnomalies(files(
                type("A", "M", "X"), type("B", "M", "X"), type("C", "M", "X")))).isEmpty();
        assertThat(analyzer.peerAnnotationAnomalies(Map.of())).isEmpty();
        assertThat(analyzer.peerAnnotationAnomalies(null)).isEmpty();
    }
}
