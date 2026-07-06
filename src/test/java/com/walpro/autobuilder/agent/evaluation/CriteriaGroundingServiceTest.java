package com.walpro.autobuilder.agent.evaluation;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CriteriaGroundingServiceTest {

    @Mock private LLMClient llmClient;
    @InjectMocks private CriteriaGroundingService service;

    private static final String SPEC = """
            Evolve the application into a library book-lending REST API.
            Loans: POST /loans (404 if book or member not found; 409 if book already on loan;
            a member may hold at most 3 active loans — 422 if exceeded).
            Invalid input returns HTTP 400 with JSON body {"message": "<reason>"}.
            """;

    private void stubLlm(String content) {
        ChatResponse body = mock(ChatResponse.class);
        when(body.getFirstContent()).thenReturn(content);
        when(llmClient.ask(anyList())).thenReturn(ResponseEntity.ok(body));
    }

    @Test
    void classifiesGroundedAndAuthored() {
        // Run 19's exact shapes: a spec-stated status rule vs the compile-diagnostic fossil.
        stubLlm("1. ✓ POST /loans returns 422 when the member exceeds the loan limit\n"
                + "2. ✗ LoanService.createLoan validates that bookId and memberId are strings");
        Map<Integer, Boolean> out = service.classifyGrounded(SPEC, List.of(
                "POST /loans returns 422 when the member exceeds the loan limit",
                "LoanService.createLoan validates that bookId and memberId are strings"));
        assertThat(out).containsEntry(1, true).containsEntry(2, false);
    }

    @Test
    void abstainsOnLlmFailure_returnsEmpty() {
        when(llmClient.ask(anyList())).thenThrow(new RuntimeException("timeout"));
        assertThat(service.classifyGrounded(SPEC, List.of("anything"))).isEmpty();
    }

    @Test
    void abstainsOnUnparseableAnswer_returnsEmpty() {
        stubLlm("I think these requirements look reasonable overall.");
        assertThat(service.classifyGrounded(SPEC, List.of("anything"))).isEmpty();
    }

    @Test
    void cachesPerSpecAndCriterion_noSecondLlmCall() {
        stubLlm("1. ✓ POST /loans returns 422 when the member exceeds the loan limit");
        List<String> criteria = List.of("POST /loans returns 422 when the member exceeds the loan limit");
        service.classifyGrounded(SPEC, criteria);
        Map<Integer, Boolean> second = service.classifyGrounded(SPEC, criteria);
        assertThat(second).containsEntry(1, true);
        verify(llmClient, times(1)).ask(anyList());
    }

    @Test
    void emptyInputs_returnEmptyWithoutLlmCall() {
        assertThat(service.classifyGrounded(null, List.of("x"))).isEmpty();
        assertThat(service.classifyGrounded(SPEC, List.of())).isEmpty();
        verifyNoInteractions(llmClient);
    }
}
