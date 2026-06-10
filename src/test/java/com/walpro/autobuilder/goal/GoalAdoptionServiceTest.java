package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoalAdoptionServiceTest {

    @Mock private LLMClient llmClient;

    private GoalAdoptionService service;

    @BeforeEach
    void setUp() {
        service = new GoalAdoptionService(llmClient,
                new GoalSimilarityService(mock(com.walpro.autobuilder.graph.goal.GoalGraphService.class)));
    }

    private static GoalEntity goal(String id, String desc) {
        return new GoalEntity(id, desc, "planner", GoalType.SUBGOAL, GoalStatus.ACTIVE,
                Instant.now(), "parent", null);
    }

    private void stubLlm(String content) {
        ChatResponse body = mock(ChatResponse.class);
        when(body.getFirstContent()).thenReturn(content);
        when(llmClient.ask(anyList())).thenReturn(ResponseEntity.ok(body));
    }

    @Test
    void confidentMatch_returnsCandidate() {
        stubLlm("Match: 1\nConfidence: 0.9");
        GoalEntity orphan = goal("o-1", "Implement LoanService business logic validation");
        GoalEntity candidate = goal("c-1", "Implement LoanService methods with validation");

        Optional<GoalEntity> result = service.findAdoptiveParent(orphan, List.of(candidate));

        assertThat(result).contains(candidate);
    }

    @Test
    void noneResponse_returnsEmpty() {
        stubLlm("Match: NONE\nConfidence: 0.95");
        Optional<GoalEntity> result = service.findAdoptiveParent(
                goal("o-1", "Implement loans"), List.of(goal("c-1", "Write member tests")));

        assertThat(result).isEmpty();
    }

    @Test
    void lowConfidenceMatch_returnsEmpty() {
        stubLlm("Match: 1\nConfidence: 0.5");
        Optional<GoalEntity> result = service.findAdoptiveParent(
                goal("o-1", "Implement loans"), List.of(goal("c-1", "Implement loan endpoint")));

        assertThat(result).isEmpty();
    }

    @Test
    void malformedResponse_returnsEmpty() {
        stubLlm("I think candidate one is a great fit!");
        Optional<GoalEntity> result = service.findAdoptiveParent(
                goal("o-1", "Implement loans"), List.of(goal("c-1", "Implement loan endpoint")));

        assertThat(result).isEmpty();
    }

    @Test
    void llmFailure_returnsEmpty() {
        when(llmClient.ask(anyList())).thenThrow(new RuntimeException("network"));
        Optional<GoalEntity> result = service.findAdoptiveParent(
                goal("o-1", "Implement loans"), List.of(goal("c-1", "Implement loan endpoint")));

        assertThat(result).isEmpty();
    }

    @Test
    void candidateSet_isShortlistBounded_toOneLlmCall() {
        stubLlm("Match: NONE\nConfidence: 0.9");
        GoalEntity orphan = goal("o-1", "Implement loan return endpoint");
        List<GoalEntity> candidates = IntStream.rangeClosed(1, 12)
                .mapToObj(i -> goal("c-" + i, "Candidate work item number " + i))
                .toList();

        service.findAdoptiveParent(orphan, candidates);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient).ask(captor.capture());
        String userPrompt = captor.getValue().get(1).getContent();
        long listed = userPrompt.lines().filter(l -> l.matches("^\\d+\\. .*")).count();
        assertThat(listed).isEqualTo(GoalAdoptionService.SHORTLIST_SIZE);
    }

    @Test
    void outOfRangeMatchIndex_returnsEmpty() {
        stubLlm("Match: 7\nConfidence: 0.9");
        Optional<GoalEntity> result = service.findAdoptiveParent(
                goal("o-1", "Implement loans"), List.of(goal("c-1", "Implement loan endpoint")));

        assertThat(result).isEmpty();
    }
}
