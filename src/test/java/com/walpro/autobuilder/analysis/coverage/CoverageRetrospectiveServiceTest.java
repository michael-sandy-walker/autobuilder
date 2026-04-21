package com.walpro.autobuilder.analysis.coverage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CoverageRetrospectiveServiceTest {

    private CoverageRetrospectiveService service;

    @BeforeEach
    void setUp() {
        service = new CoverageRetrospectiveService(mock(com.walpro.autobuilder.client.LLMClient.class));
    }

    @Test
    void parseResponse_completeMarker_returnsEmptyList() {
        List<String> result = service.parseResponse("#COMPLETE");
        assertThat(result).isEmpty();
    }

    @Test
    void parseResponse_completeMarkerWithSurroundingText_returnsEmptyList() {
        List<String> result = service.parseResponse("After reviewing all work items, I can confirm:\n#COMPLETE");
        assertThat(result).isEmpty();
    }

    @Test
    void parseResponse_dashPrefixedLines_extractsFeatures() {
        String raw = """
                The following requirements are not covered:
                - POST /members endpoint with name validation
                - DELETE /members/{id} endpoint
                """;
        List<String> result = service.parseResponse(raw);
        assertThat(result).containsExactly(
                "POST /members endpoint with name validation",
                "DELETE /members/{id} endpoint"
        );
    }

    @Test
    void parseResponse_mixedLines_ignoresNonDashLines() {
        String raw = """
                Summary of gaps:
                - Implement GET /loans?memberId endpoint
                The above feature was removed by the planner.
                - POST /loans return endpoint
                """;
        List<String> result = service.parseResponse(raw);
        assertThat(result).containsExactly(
                "Implement GET /loans?memberId endpoint",
                "POST /loans return endpoint"
        );
    }

    @Test
    void parseResponse_nullInput_returnsEmptyList() {
        assertThat(service.parseResponse(null)).isEmpty();
    }

    @Test
    void parseResponse_blankInput_returnsEmptyList() {
        assertThat(service.parseResponse("   ")).isEmpty();
    }

    @Test
    void parseResponse_noDashLines_returnsEmptyList() {
        String raw = "All features appear to be covered by the completed subgoals.";
        assertThat(service.parseResponse(raw)).isEmpty();
    }

    @Test
    void parseResponse_dashLineWithBlankFeature_skipsIt() {
        String raw = "- \n- Real feature description\n-   \n";
        List<String> result = service.parseResponse(raw);
        assertThat(result).containsExactly("Real feature description");
    }
}
