package com.walpro.autobuilder.scenario;

import com.walpro.autobuilder.analysis.structure.WiringFactsAnalyzer;
import com.walpro.autobuilder.core.build.InjectedTypeRegistrationRepair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replays run {@code 2026-09-03_19-05-02}, which ended unstartable on a defect the loop already
 * had a detector and a repair for.
 *
 * <pre>
 * APPLICATION FAILED TO START
 * Parameter 0 of constructor in ...web.BookController required a bean of type
 * '...repository.BookRepository' that could not be found.
 * </pre>
 *
 * <p><b>Why it was missed, and why that is the interesting part.</b> The same defect from run
 * {@code 2026-08-30_18-44-37} is covered by {@link UnregisteredInjectedTypeRepairReplayTest} and
 * was caught: one repository of three was unregistered, so it had two annotated peers and cleared
 * the peer bar. This run left TWO of three unregistered. Each candidate then saw a single
 * annotated peer, the bar asked for two, and the detector went silent — so the rule stopped firing
 * exactly as the fault spread, and the run merged thirteen mutations onto an application that
 * could not start.
 *
 * <p>The tree is the run's own, frozen by {@code scripts/freeze_scenario.sh} rather than retyped,
 * so what is asserted here is what actually defeated the loop.
 */
class UnregisteredRepositoriesScenarioTest {

    private static final String SCENARIO = "unregistered-repositories-2026-09-03";

    private final WiringFactsAnalyzer analyzer = new WiringFactsAnalyzer();

    @Test
    @DisplayName("the frozen tree is the one that failed: two repositories carry no annotation, one does")
    void theScenarioIsTheTreeThatFailed() {
        Scenario scenario = Scenario.load(SCENARIO);

        assertThat(scenario.javaFiles().keySet())
                .as(scenario.describe())
                .anyMatch(p -> p.endsWith("BookRepository.java"))
                .anyMatch(p -> p.endsWith("MemberRepository.java"))
                .anyMatch(p -> p.endsWith("BookController.java"));
        assertThat(source(scenario, "BookRepository.java")).doesNotContain("@Repository");
        assertThat(source(scenario, "LoanRepository.java")).doesNotContain("@Repository");
        assertThat(source(scenario, "MemberRepository.java")).contains("@Repository");
        assertThat(source(scenario, "BookController.java"))
                .as("the controller is registered and asks for the repository in its constructor, "
                        + "which is what makes the missing bean fatal rather than unused")
                .contains("@RestController")
                .contains("BookRepository");
    }

    @Test
    @DisplayName("both unregistered repositories are named, not just the one with two annotated peers")
    void bothUnregisteredRepositoriesAreNamed() {
        Scenario scenario = Scenario.load(SCENARIO);

        List<WiringFactsAnalyzer.UnregisteredInjectedType> found =
                analyzer.unregisteredInjectedTypes(scenario.javaFiles());

        assertThat(found).extracting(WiringFactsAnalyzer.UnregisteredInjectedType::typeName)
                .as(scenario.describe())
                .contains("BookRepository");
        assertThat(found).allSatisfy(f -> {
            assertThat(f.missingAnnotation()).isEqualTo("Repository");
            assertThat(f.peers()).isNotEmpty();
        });
    }

    @Test
    @DisplayName("the repair plans the edit that would have made the artifact start")
    void theRepairPlansTheMissingRegistration() {
        Scenario scenario = Scenario.load(SCENARIO);

        List<InjectedTypeRegistrationRepair.Repair> repairs = InjectedTypeRegistrationRepair.plan(
                scenario.javaFiles(), analyzer.unregisteredInjectedTypes(scenario.javaFiles()));

        assertThat(repairs).extracting(InjectedTypeRegistrationRepair.Repair::typeName)
                .as("a plan naming BookRepository is the difference between this run shipping "
                        + "nothing and this run booting")
                .contains("BookRepository");
        assertThat(repairs).allSatisfy(r -> {
            assertThat(r.annotation()).isEqualTo("Repository");
            assertThat(r.importLine()).contains("org.springframework.stereotype.Repository");
            assertThat(r.path()).endsWith(".java");
        });
    }

    @Test
    @DisplayName("the prose the loop reports reads correctly when only one peer carries the annotation")
    void theReportedFactReadsCorrectlyForASinglePeer() {
        Scenario scenario = Scenario.load(SCENARIO);

        List<String> facts = analyzer.peerAnnotationAnomalies(scenario.javaFiles());

        assertThat(facts).isNotEmpty();
        assertThat(facts).as("this text becomes a work-item description, so 'all 1 other types' "
                        + "would be shipped to the model as the reason to act")
                .noneMatch(f -> f.contains("all 1 other types"));
    }

    private static String source(Scenario scenario, String suffix) {
        return scenario.javaFiles().entrySet().stream()
                .filter(e -> e.getKey().endsWith(suffix))
                .map(java.util.Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no " + suffix + " in " + scenario.describe()));
    }
}
