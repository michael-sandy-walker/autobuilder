package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.LlmCircuitBreaker;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T4-AN-4: tests for {@link BuildErrorClassifierAgent}. */
class BuildErrorClassifierAgentTest {

    private LLMClient llmClient;
    private LlmCircuitBreaker circuitBreaker;
    private BuildErrorClassifierAgent agent;

    @BeforeEach
    void setUp() {
        llmClient = mock(LLMClient.class);
        circuitBreaker = mock(LlmCircuitBreaker.class);
        lenient().when(circuitBreaker.isOpen()).thenReturn(false);
        agent = new BuildErrorClassifierAgent(llmClient, circuitBreaker);
    }

    @Test
    void classify_blankStderr_isFallback() {
        BuildErrorClassifier.Classification out = agent.classify("   ");

        assertThat(out.kind()).isEqualTo(BuildErrorClassifier.Kind.UNKNOWN);
        verify(llmClient, never()).ask(anyList());
    }

    @Test
    void classify_circuitOpen_fallsBackToPatternMatcher() {
        when(circuitBreaker.isOpen()).thenReturn(true);
        when(circuitBreaker.pauseReason()).thenReturn("test");

        BuildErrorClassifier.Classification out = agent.classify(
                "[ERROR] package javax.foo does not exist");

        assertThat(out.kind()).isEqualTo(BuildErrorClassifier.Kind.PACKAGE_DOES_NOT_EXIST);
        assertThat(out.category()).isEqualTo(BuildErrorClassifier.Category.CONFIG_FIXABLE);
        verify(llmClient, never()).ask(anyList());
    }

    @Test
    void classify_adaptiveValidJson_returnsAdaptiveClassificationAndCaches() {
        String stderr = "[ERROR] /pom.xml release version 21 not supported";
        String body = "{\"category\":\"CONFIG_FIXABLE\"," +
                "\"summaryLine\":\"maven-compiler-plugin 3.1 does not support the <release> element\"," +
                "\"missingArtifact\":\"\"," +
                "\"missingSymbol\":\"\"," +
                "\"suggestedFix\":\"upgrade maven-compiler-plugin to >= 3.10\"}";
        when(llmClient.ask(anyList())).thenReturn(ResponseEntity.ok(stubChatResponse(body)));

        BuildErrorClassifier.Classification first = agent.classify(stderr);

        assertThat(first.category()).isEqualTo(BuildErrorClassifier.Category.CONFIG_FIXABLE);
        assertThat(first.summaryLine()).contains("maven-compiler-plugin 3.1");
        assertThat(first.suggestedFix()).contains("upgrade maven-compiler-plugin");
        assertThat(first.missingArtifact()).isNull();
        assertThat(first.missingSymbol()).isNull();

        // Same stderr → cache hit, no second LLM call.
        BuildErrorClassifier.Classification second = agent.classify(stderr);
        assertThat(second).isSameAs(first);
        verify(llmClient, times(1)).ask(anyList());
        assertThat(agent.cacheSize()).isEqualTo(1);
    }

    @Test
    void classify_adaptiveMalformedJson_fallsBackToPattern() {
        String stderr = "[ERROR] symbol: class Foo";
        when(llmClient.ask(anyList())).thenReturn(ResponseEntity.ok(stubChatResponse("not json at all")));

        BuildErrorClassifier.Classification out = agent.classify(stderr);

        // Fallback path used — kind comes from the pattern matcher; category from defaults.
        assertThat(out.kind()).isEqualTo(BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL);
        assertThat(out.category()).isEqualTo(BuildErrorClassifier.Category.SOURCE_FIXABLE);
        // Fallback should not be cached — the next attempt must retry the LLM.
        assertThat(agent.cacheSize()).isZero();
    }

    @Test
    void classify_adaptiveUnknownCategoryString_fallsBackToPattern() {
        when(llmClient.ask(anyList())).thenReturn(ResponseEntity.ok(
                stubChatResponse("{\"category\":\"SOMETHING_NEW\",\"summaryLine\":\"x\"}")));

        BuildErrorClassifier.Classification out = agent.classify("[ERROR] symbol: class Foo");

        assertThat(out.kind()).isEqualTo(BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL);
        assertThat(out.summaryLine()).isNull();
    }

    @Test
    void classify_adaptiveMarkdownFencedJson_isAccepted() {
        String body = "```json\n{\"category\":\"SOURCE_FIXABLE\",\"summaryLine\":\"missing Book\"}\n```";
        when(llmClient.ask(anyList())).thenReturn(ResponseEntity.ok(stubChatResponse(body)));

        BuildErrorClassifier.Classification out = agent.classify("[ERROR] symbol: class Book");

        assertThat(out.category()).isEqualTo(BuildErrorClassifier.Category.SOURCE_FIXABLE);
        assertThat(out.summaryLine()).isEqualTo("missing Book");
    }

    @Test
    void classify_adaptiveLlmThrows_fallsBackToPattern() {
        when(llmClient.ask(anyList())).thenThrow(new RuntimeException("network"));

        BuildErrorClassifier.Classification out = agent.classify("[ERROR] symbol: class Foo");

        assertThat(out.kind()).isEqualTo(BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL);
        assertThat(out.category()).isEqualTo(BuildErrorClassifier.Category.SOURCE_FIXABLE);
    }

    @Test
    void classify_adaptiveHit_emitsBehaviourEvent() {
        BehaviourLogger behaviourLogger = mock(BehaviourLogger.class);
        agent.setBehaviourLogger(behaviourLogger);
        String body = "{\"category\":\"SOURCE_FIXABLE\",\"summaryLine\":\"missing Book\"," +
                "\"missingSymbol\":\"Book\"}";
        when(llmClient.ask(anyList())).thenReturn(ResponseEntity.ok(stubChatResponse(body)));

        agent.classify("[ERROR] symbol: class Book");

        verify(behaviourLogger).buildClassifierAdaptiveHit("SOURCE_FIXABLE", "CANNOT_FIND_SYMBOL", "Book", null);
    }

    @Test
    void classify_fallbackPath_doesNotEmitAdaptiveHit() {
        when(circuitBreaker.isOpen()).thenReturn(true);
        when(circuitBreaker.pauseReason()).thenReturn("test");
        BehaviourLogger behaviourLogger = mock(BehaviourLogger.class);
        agent.setBehaviourLogger(behaviourLogger);

        agent.classify("[ERROR] symbol: class Foo");

        verify(behaviourLogger, never()).buildClassifierAdaptiveHit(any(), any(), any(), any());
    }

    @Test
    void classify_adaptive_stripsAnsiFromLlmPromptButKeepsDiagnosticText() {
        // Real Maven-coloured stderr: ANSI escape sequences (ESC = \u001B) around the banner and
        // the diagnostic. The [ERROR] tag and the [3,8] position are content, not ANSI.
        String esc = "\u001B";
        String ansiStderr = esc + "[1;31m[ERROR]" + esc + "[m /src/main/java/app/Foo.java:[3,8] "
                + "symbol:   class " + esc + "[1mBar" + esc + "[m";
        when(llmClient.ask(anyList())).thenReturn(ResponseEntity.ok(stubChatResponse(
                "{\"category\":\"SOURCE_FIXABLE\",\"summaryLine\":\"missing Bar\"}")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        agent.classify(ansiStderr);
        verify(llmClient).ask(captor.capture());

        String userMessage = captor.getValue().get(1).getContent();
        assertThat(userMessage)
                .as("ANSI escape sequences must be stripped from the classifier prompt")
                .doesNotContain(esc);
        assertThat(userMessage)
                .as("the diagnostic text itself must survive the strip")
                .contains("[ERROR]")
                .contains("symbol:   class Bar")
                .contains("Foo.java:[3,8]");
    }

    private static ChatResponse stubChatResponse(String content) {
        ChatResponse response = new ChatResponse();
        ChatResponse.Choice choice = new ChatResponse.Choice();
        choice.setMessage(new Message("assistant", content));
        response.setChoices(List.of(choice));
        return response;
    }
}
