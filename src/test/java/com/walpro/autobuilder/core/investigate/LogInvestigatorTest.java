package com.walpro.autobuilder.core.investigate;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T4-EE: the probe loop -- each probe answered from the log, the answer returned with the steps behind it. */
class LogInvestigatorTest {

    private static final List<String> LOG = List.of(
            "alpha starts",
            "Widget.java: cannot find symbol frob",
            "beta",
            "Widget.java: cannot find symbol frob",
            "gamma ends");

    private final LLMClient llm = mock(LLMClient.class);

    private void replies(String... texts) {
        List<ResponseEntity<ChatResponse>> rs = new ArrayList<>();
        for (String t : texts) {
            ChatResponse r = new ChatResponse();
            ChatResponse.Choice c = new ChatResponse.Choice();
            c.setMessage(new Message("assistant", t));
            r.setChoices(List.of(c));
            rs.add(ResponseEntity.ok(r));
        }
        var stub = when(llm.ask(anyList()));
        stub.thenReturn(rs.get(0), rs.subList(1, rs.size()).toArray(ResponseEntity[]::new));
    }

    @Test
    void probesUntilAnAnswer_andKeepsEveryStepBesideIt() {
        replies("#Probe search app.log cannot find symbol",
                "#Probe read app.log 1 3",
                "#Answer\nWidget.java failed twice on frob (lines 2 and 4).");

        var result = new LogInvestigator(llm).investigate("What failed?", Map.of("app.log", LOG), 5, null);

        assertThat(result.answered()).isTrue();
        assertThat(result.answer()).isEqualTo("Widget.java failed twice on frob (lines 2 and 4).");
        assertThat(result.steps()).extracting(LogInvestigator.Step::probe)
                .containsExactly("#Probe search app.log cannot find symbol", "#Probe read app.log 1 3");
        assertThat(result.steps().get(0).result()).contains("2 matching lines", "2: Widget.java", "4: Widget.java");
        assertThat(result.steps().get(1).result()).contains("1: alpha starts", "3: beta").doesNotContain("4:");
    }

    @Test
    @SuppressWarnings("unchecked")
    void eachResultReachesTheModel_withTheProbesLeft() {
        replies("#Probe count app.log frob", "#Answer\ntwo");
        new LogInvestigator(llm).investigate("How many?", Map.of("app.log", LOG), 4, null);

        ArgumentCaptor<List<Message>> sent = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).ask(sent.capture());
        List<Message> second = sent.getAllValues().get(1);
        assertThat(second.get(1).getContent()).contains("How many?", "app.log -- 5 lines", "4 probes");
        assertThat(second.get(second.size() - 1).getContent()).contains("2 matching lines.", "(3 probes left)");
    }

    @Test
    void probesRunOut_thenOneLastCallAsksForTheAnswer() {
        replies("#Probe count app.log alpha", "#Probe count app.log beta", "#Answer\nalpha and beta once each");

        var result = new LogInvestigator(llm).investigate("q", Map.of("app.log", LOG), 2, null);

        assertThat(result.steps()).hasSize(2);
        assertThat(result.answered()).isTrue();
        assertThat(result.answer()).isEqualTo("alpha and beta once each");
    }

    @Test
    void noAnswerAfterTheBudget_isReportedUnanswered() {
        replies("#Probe count app.log alpha", "still thinking");

        var result = new LogInvestigator(llm).investigate("q", Map.of("app.log", LOG), 1, null);

        assertThat(result.answered()).isFalse();
        assertThat(result.answer()).isNull();
    }

    @Test
    void malformedProbesAreAnsweredWithWhatWasWrong() {
        Map<String, List<String>> logs = Map.of("app.log", LOG);
        assertThat(LogInvestigator.run("search", "other.log", "x", logs)).contains("no log named other.log", "app.log");
        assertThat(LogInvestigator.run("search", "app.log", "(unclosed", logs)).contains("does not compile");
        assertThat(LogInvestigator.run("read", "app.log", "two lines", logs)).contains("two line numbers");
        assertThat(LogInvestigator.run("read", "app.log", "9 12", logs)).contains("only 5 lines");
        assertThat(LogInvestigator.run("search", "app.log", "\"symbol frob\"", logs)).contains("2 matching lines");
        assertThat(LogInvestigator.run("search", "app.log", "zzz", logs)).isEqualTo("No line matches.");
    }

    @Test
    void largeResultsAreBounded() {
        List<String> big = IntStream.rangeClosed(1, 500).mapToObj(i -> "hit " + i + " " + "x".repeat(400)).toList();
        Map<String, List<String>> logs = Map.of("big.log", big);

        String search = LogInvestigator.run("search", "big.log", "hit", logs);
        assertThat(search).startsWith("500 matching lines, the first");
        assertThat(search.length()).isLessThan(LogInvestigator.MAX_RESULT_CHARS + 2 * LogInvestigator.MAX_LINE_CHARS);
        String read = LogInvestigator.run("read", "big.log", "1 500", logs);
        assertThat(Arrays.stream(read.split("\n")).count()).isLessThanOrEqualTo(LogInvestigator.MAX_READ_LINES);
        assertThat(read).contains(" ...");
    }
}
