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
    void candidateSet_allCandidatesReachTheLlm_inOneCall() {
        // The string metric is measured-unreliable at ranking same-work-vs-similar-words, so
        // no fixed-size truncation may hide candidates; cost is bounded by the single call.
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
        assertThat(listed).isEqualTo(12);
    }

    @Test
    void outOfRangeMatchIndex_returnsEmpty() {
        stubLlm("Match: 7\nConfidence: 0.9");
        Optional<GoalEntity> result = service.findAdoptiveParent(
                goal("o-1", "Implement loans"), List.of(goal("c-1", "Implement loan endpoint")));

        assertThat(result).isEmpty();
    }

    // ── T4-AJ Part B: findEquivalent ──

    @Test
    void findEquivalent_confidentMatch_returnsMatchWithConfidence() {
        stubLlm("Match: 1\nConfidence: 0.85");
        GoalEntity subject = goal("s-1", "Implement member retrieval in the service");
        GoalEntity candidate = goal("c-1", "Add member retrieval method to the service");

        Optional<GoalAdoptionService.ReviewedMatch> result =
                service.findEquivalent(subject, List.of(candidate));

        assertThat(result).isPresent();
        assertThat(result.get().goal()).isSameAs(candidate);
        assertThat(result.get().confidence()).isEqualTo(0.85);
    }

    @Test
    void findEquivalent_usesSameWorkPrompt_notTheAdoptionPrompt() {
        stubLlm("Match: NONE\nConfidence: 0.9");
        service.findEquivalent(goal("s-1", "Implement member retrieval"),
                List.of(goal("c-1", "Add member retrieval method")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient).ask(captor.capture());
        String userPrompt = captor.getValue().get(1).getContent();
        assertThat(userPrompt).contains("SAME piece of work");
        assertThat(userPrompt).contains("NOT the same work");
        assertThat(userPrompt).doesNotContain("ORPHANED WORK ITEM");
    }

    @Test
    void findEquivalent_rendersAcceptanceCriteria_forSubjectAndCandidates() {
        stubLlm("Match: NONE\nConfidence: 0.9");
        GoalEntity subject = goal("s-1", "Implement loan creation validation returning 404");
        subject.getMetadata().put("acceptanceCriteria",
                List.of("POST /loans returns 404 when bookId does not exist"));
        GoalEntity candidate = goal("c-1", "Implement loan creation validation returning 404 for members");
        candidate.getMetadata().put("acceptanceCriteria",
                List.of("POST /loans returns 404 when memberId does not exist"));

        service.findEquivalent(subject, List.of(candidate));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient).ask(captor.capture());
        String userPrompt = captor.getValue().get(1).getContent();
        assertThat(userPrompt).contains("bookId does not exist");
        assertThat(userPrompt).contains("memberId does not exist");
        assertThat(userPrompt).contains("different rules, different fields, or different endpoints");
    }

    @Test
    void findEquivalent_noneOrLowConfidence_returnsEmpty() {
        stubLlm("Match: 1\nConfidence: 0.4");
        Optional<GoalAdoptionService.ReviewedMatch> result = service.findEquivalent(
                goal("s-1", "Implement member retrieval"),
                List.of(goal("c-1", "Add member retrieval method")));

        assertThat(result).isEmpty();
    }
}
