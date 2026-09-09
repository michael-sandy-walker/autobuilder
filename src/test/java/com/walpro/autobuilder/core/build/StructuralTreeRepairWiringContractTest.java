package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.AutobuilderApplication;
import com.walpro.autobuilder.agent.merge.MergeAgent;
import com.walpro.autobuilder.core.service.AutobuilderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-DR: the repair reaches BOTH of its callers in a real context, by construction.
 *
 * <p><b>The failure this exists to make impossible.</b> {@link StructuralTreeRepair} is injected
 * as {@code @Autowired(required = false)} at two independent sites — the merge path and the
 * start-of-run pass — because both must tolerate slices that do not build the analyzer. A
 * collaborator that is optional is a collaborator that can be silently NULL, and when it is, both
 * repairs stop happening and nothing says so: {@code T3-CA} fires in roughly four runs of sixteen
 * even when perfectly healthy, so ZERO firings is exactly what "no finding arose" looks like too.
 * **A field run cannot tell those apart** — the 2026-09-08 verification runs hit precisely this,
 * where one run's silence was uninformative and the other proved only the start-of-run site.
 *
 * <p>Every existing test wires the collaborator by hand, so a green suite would stay green with
 * the bean unwired. This asserts the two facts that make injection certain instead: the type is a
 * component under the scanned root, and each caller's field asks for it.
 *
 * <p>Follows {@code StateHolderRegistrationContractTest}'s idiom deliberately — this project boots
 * the full context in tests only where it must ({@code FileServiceTest} records why), so the
 * contract is checked by construction rather than by standing a context up.
 */
class StructuralTreeRepairWiringContractTest {

    @Test
    @DisplayName("the repair is a component under the scanned root, so the context will create it")
    void theRepairIsDiscoverableByComponentScan() {
        assertThat(StructuralTreeRepair.class.getAnnotation(Component.class))
                .as("without @Component nothing creates the bean and both callers get null")
                .isNotNull();

        String scanRoot = AutobuilderApplication.class.getPackageName();
        assertThat(StructuralTreeRepair.class.getPackageName())
                .as("a component outside the scanned root is never created")
                .startsWith(scanRoot);
    }

    @Test
    @DisplayName("both callers ask for the repair, so neither can be left holding null")
    void everyCallerDeclaresAnInjectedField() {
        for (Class<?> caller : List.of(MergeAgent.class, AutobuilderService.class)) {
            List<Field> fields = new ArrayList<>();
            for (Field f : caller.getDeclaredFields()) {
                if (f.getType() == StructuralTreeRepair.class) fields.add(f);
            }
            assertThat(fields)
                    .as("%s must hold the repair as a field; T4-DR's whole point is that BOTH the "
                            + "merge path and the start-of-run pass reach the same code", caller.getSimpleName())
                    .hasSize(1);
            assertThat(fields.get(0).getAnnotation(Autowired.class))
                    .as("%s's field is not @Autowired, so the context leaves it null and the "
                            + "repairs silently stop — indistinguishable from a run with no findings",
                            caller.getSimpleName())
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("the repair still exposes both tree-property repairs through its one entry point")
    void theEntryPointCoversBothRepairs() throws Exception {
        // If either is dropped from repairTree(), the start-of-run pass silently loses half its
        // job while every caller still compiles.
        assertThat(StructuralTreeRepair.class.getDeclaredMethod("repairTree", String.class))
                .isNotNull();
        assertThat(StructuralTreeRepair.class
                .getDeclaredMethod("applyInjectedTypeRegistrationRepair", String.class)).isNotNull();
        assertThat(StructuralTreeRepair.class
                .getDeclaredMethod("applyPeerPlacementRelocation", String.class)).isNotNull();
    }
}
