package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import com.walpro.autobuilder.core.build.inject.InjectorFixRecord;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-CQ: a rejected candidate is rolled back whole. The injector's own additions (a bootstrapped
 * pom, a skeleton) are kept; a fix that edited one of the model's staged files goes with them.
 */
class EvaluationAgentInjectorPreservationTest {

    @Test
    void aFixToTheModelsOwnFileIsNotPreserved_anAddedFileIs() {
        CycleContextData ctx = CycleContextData.builder()
                .evolutionOutcome(new EvolutionOutcome(
                        List.of(new FileMutation("src/test/java/shop/GadgetTest.java", "class GadgetTest {}")),
                        List.of(), "s", "r"))
                .injectorFixes(List.of(
                        new InjectorFixRecord("declared-accessor:getKind", Kind.CANNOT_FIND_SYMBOL, "getKind",
                                List.of("src/test/java/shop/GadgetTest.java"), "rewrote"),
                        new InjectorFixRecord("bootstrap-pom", Kind.MISSING_POM, "", List.of("pom.xml"), "added")))
                .build();

        assertThat(EvaluationAgent.preservableInjectorPaths(ctx)).containsExactly("pom.xml");
    }
}
