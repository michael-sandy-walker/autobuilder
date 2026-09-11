package com.walpro.autobuilder.graph.core;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T4-CM slice 1: input handling of the graph explorer's read surface. The label fragment
 * is interpolated into cypher (labels cannot be bound), so its validation is the security
 * boundary; the limit clamp bounds every browse; jsonSafe is the forensic-fidelity mapping.
 */
class OrchestrationGraphExplorerServiceTest {

    @Test
    void noLabelMeansNoFragment() {
        assertThat(OrchestrationGraphExplorerService.labelFragment(null)).isEmpty();
        assertThat(OrchestrationGraphExplorerService.labelFragment("  ")).isEmpty();
    }

    @Test
    void plainIdentifierLabelsPass() {
        assertThat(OrchestrationGraphExplorerService.labelFragment("Goal")).isEqualTo(":Goal");
        assertThat(OrchestrationGraphExplorerService.labelFragment("Criterion_Result2")).isEqualTo(":Criterion_Result2");
    }

    @Test
    void nonIdentifierLabelsAreRejected() {
        assertThatThrownBy(() -> OrchestrationGraphExplorerService.labelFragment("Goal) MATCH (m"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrchestrationGraphExplorerService.labelFragment("Goal`"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrchestrationGraphExplorerService.labelFragment("Goal Node"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * T5-BM input guard. The query itself is not mocked here — this class tests pure helpers and
     * there is no Neo4jClient idiom to follow — so it was verified against the live store instead:
     * the endpoint returned 216 actions for {@code goal-f1726624…} with the full type/status
     * breakdown, and 404 for a goal with none. That is stronger evidence than a mocked fluent API,
     * and it is recorded here so the absence of a query test is deliberate rather than overlooked.
     */
    @Test
    void goalActivityRejectsAnAbsentIdWithoutTouchingTheStore() {
        var service = new OrchestrationGraphExplorerService(null);
        assertThat(service.goalActivity(null)).isEmpty();
        assertThat(service.goalActivity("  ")).isEmpty();
    }

    @Test
    void limitClampsToBounds() {
        assertThat(OrchestrationGraphExplorerService.clampLimit(null))
                .isEqualTo(OrchestrationGraphExplorerService.DEFAULT_LIMIT);
        assertThat(OrchestrationGraphExplorerService.clampLimit(0)).isEqualTo(1);
        assertThat(OrchestrationGraphExplorerService.clampLimit(-5)).isEqualTo(1);
        assertThat(OrchestrationGraphExplorerService.clampLimit(50)).isEqualTo(50);
        assertThat(OrchestrationGraphExplorerService.clampLimit(99999))
                .isEqualTo(OrchestrationGraphExplorerService.MAX_LIMIT);
    }

    @Test
    void jsonSafePassesPlainValuesAndStringifiesDriverTypes() {
        assertThat(OrchestrationGraphExplorerService.jsonSafe(null)).isNull();
        assertThat(OrchestrationGraphExplorerService.jsonSafe("text")).isEqualTo("text");
        assertThat(OrchestrationGraphExplorerService.jsonSafe(42L)).isEqualTo(42L);
        assertThat(OrchestrationGraphExplorerService.jsonSafe(true)).isEqualTo(true);

        ZonedDateTime stamp = ZonedDateTime.parse("2026-07-23T00:00:00+02:00");
        assertThat(OrchestrationGraphExplorerService.jsonSafe(stamp)).isEqualTo(stamp.toString());

        Object nested = OrchestrationGraphExplorerService.jsonSafe(
                List.of(Map.of("when", stamp, "n", 1L)));
        assertThat(nested).isEqualTo(List.of(Map.of("when", stamp.toString(), "n", 1L)));
    }
}
