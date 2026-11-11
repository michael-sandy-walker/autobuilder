package com.walpro.autobuilder.core.coldread;

import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.Account;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.Feature;
import com.walpro.autobuilder.core.merge.CapabilityCut.Capability;
import com.walpro.autobuilder.protocol.rules.WorkRulesBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-EK slice (c): how work on an imported project is done, proposed only from what was observed of it. */
class ObservedRulesTest {

    private static Account account(List<Feature> features, List<Capability> capabilities) {
        return new Account("src", "copy", null, "library-style", features, capabilities, List.of(), List.of(), List.of(),
                List.of(), Map.of(), 0);
    }

    private static final Capability COUNTING = new Capability("c1", "Counting what was issued",
            "keeps the running number", List.of("Counter", "Counter.next", "Tally.add"), List.of(), false, null);
    private static final Capability UNUSED = new Capability("c2", "Start-up", "starts it", List.of("Main.main"), List.of(), false, null);

    @Test
    void aDependencyAndAPartThatFeaturesRestOnBecomeRulesAndNothingElseDoes() {
        Feature shelf = new Feature("Shelf", "library-style", List.of("Shelf.put"), List.of("Shelf.put"), List.of(), List.of("c1"), List.of("k1"));
        Feature desk = new Feature("Desk", "library-style", List.of("Desk.lend"), List.of("Desk.lend"), List.of("Shelf"), List.of("c1"), List.of("k2"));
        Feature idle = new Feature("Idle", "library-style", List.of("Idle.x"), List.of("Idle.x"), List.of("Shelf"), List.of("c2"), List.of());

        List<String> rules = ObservedRules.propose(account(List.of(shelf, desk, idle), List.of(COUNTING, UNUSED)));

        assertThat(rules).containsExactly(
                "Desk goes through Shelf (its checks stop holding when that is taken out): a change to Shelf keeps what "
                        + "Desk does working.",
                "\"Counting what was issued\" is the work of Counter and Tally, and what Shelf and Desk do rests on it "
                        + "(seen by taking it out): behaviour of that kind is changed there, not built a second time beside it.");
        assertThat(String.join(" ", rules)).as("a feature no check was kept for supports no rule")
                .doesNotContain("Idle").doesNotContain("Start-up");
    }

    @Test
    void aProjectWhereNeitherWasObservedGetsNoRulesAndNoBlock() {
        Feature alone = new Feature("Alone", "library-style", List.of("Alone.x"), List.of("Alone.x"), List.of(), List.of(), List.of("k1"));
        assertThat(ObservedRules.propose(account(List.of(alone), List.of(COUNTING)))).isEmpty();
        assertThat(ObservedRules.block(account(List.of(alone), List.of(COUNTING)))).isEmpty();
    }

    @Test
    void confirmedRulesAreReadBackAsRulesNeverAsSeedLinesOrStatementsToCheck() {
        Feature shelf = new Feature("Shelf", "library-style", List.of("Shelf.put"), List.of("Shelf.put"), List.of(), List.of("c1"), List.of("k1"));
        String block = ObservedRules.block(account(List.of(shelf), List.of(COUNTING)));
        String text = "Shelf:\n- puts a thing on it [k1]\n- a thing put twice is refused" + block + "rule: Keep the packages as they are\n";

        List<SeedComposer.Line> lines = SeedComposer.lines(text, Set.of("Shelf"));

        assertThat(lines).extracting(SeedComposer.Line::kind).containsExactly(SeedComposer.Line.HEADER, SeedComposer.Line.CITED,
                SeedComposer.Line.UNCITED, SeedComposer.Line.RULE, SeedComposer.Line.RULE);
        assertThat(lines.get(4).text()).isEqualTo("Keep the packages as they are");
        String seed = ReverseEngineeringConfirmation.seedText(lines);
        assertThat(seed).contains("a thing put twice is refused").doesNotContain("rule").doesNotContain("Keep the packages")
                .doesNotContain(ObservedRules.HEADING);
    }

    @Test
    void theBlockForTheAgentsSaysWhatRulesAreAndIsAbsentWithoutAny() {
        assertThat(WorkRulesBlock.render(List.of())).isNull();
        assertThat(WorkRulesBlock.render(null)).isNull();
        String block = WorkRulesBlock.render(List.of("Keep the packages as they are", " "));
        assertThat(block).startsWith("--- HOW WORK ON THIS PROJECT IS DONE ---")
                .contains("not requirements to build").contains("  - Keep the packages as they are\n");
        assertThat(block.lines().filter(l -> l.startsWith("  - "))).hasSize(1);
    }
}
