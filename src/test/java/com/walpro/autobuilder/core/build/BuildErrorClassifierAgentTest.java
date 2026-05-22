package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.LlmCircuitBreaker;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    private BuildClassifierConfigProperties config;
    private BuildErrorClassifierAgent agent;

    @BeforeEach
    void setUp() {
        llmClient = mock(LLMClient.class);
        circuitBreaker = mock(LlmCircuitBreaker.class);
        lenient().when(circuitBreaker.isOpen()).thenReturn(false);
        config = new BuildClassifierConfigProperties();
        agent = new BuildErrorClassifierAgent(llmClient, circuitBreaker, config);
    }

    @Test
    void classify_adaptiveOff_fallsBackToPatternMatcherWithoutCallingLlm() {
        config.setAdaptive(false);
        String stderr = "[ERROR] symbol: class Foo";

        BuildErrorClassifier.Classification out = agent.classify(stderr);

        assertThat(out.kind()).isEqualTo(BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL);
        assertThat(out.category()).isEqualTo(BuildErrorClassifier.Category.SOURCE_FIXABLE);
        verify(llmClient, never()).ask(anyList());
    }

    @Test
    void classify_blankStderr_isFallback() {
        config.setAdaptive(true);
        BuildErrorClassifier.Classification out = agent.classify("   ");

        assertThat(out.kind()).isEqualTo(BuildErrorClassifier.Kind.UNKNOWN);
        verify(llmClient, never()).ask(anyList());
    }

    @Test
    void classify_circuitOpen_fallsBackToPatternMatcher() {
        config.setAdaptive(true);
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
        config.setAdaptive(true);
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
        config.setAdaptive(true);
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
        config.setAdaptive(true);
        when(llmClient.ask(anyList())).thenReturn(ResponseEntity.ok(
                stubChatResponse("{\"category\":\"SOMETHING_NEW\",\"summaryLine\":\"x\"}")));

        BuildErrorClassifier.Classification out = agent.classify("[ERROR] symbol: class Foo");

        assertThat(out.kind()).isEqualTo(BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL);
        assertThat(out.summaryLine()).isNull();
    }

    @Test
    void classify_adaptiveMarkdownFencedJson_isAccepted() {
        config.setAdaptive(true);
        String body = "```json\n{\"category\":\"SOURCE_FIXABLE\",\"summaryLine\":\"missing Book\"}\n```";
        when(llmClient.ask(anyList())).thenReturn(ResponseEntity.ok(stubChatResponse(body)));

        BuildErrorClassifier.Classification out = agent.classify("[ERROR] symbol: class Book");

        assertThat(out.category()).isEqualTo(BuildErrorClassifier.Category.SOURCE_FIXABLE);
        assertThat(out.summaryLine()).isEqualTo("missing Book");
    }

    @Test
    void classify_adaptiveLlmThrows_fallsBackToPattern() {
        config.setAdaptive(true);
        when(llmClient.ask(anyList())).thenThrow(new RuntimeException("network"));

        BuildErrorClassifier.Classification out = agent.classify("[ERROR] symbol: class Foo");

        assertThat(out.kind()).isEqualTo(BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL);
        assertThat(out.category()).isEqualTo(BuildErrorClassifier.Category.SOURCE_FIXABLE);
    }

    private static ChatResponse stubChatResponse(String content) {
        ChatResponse response = new ChatResponse();
        ChatResponse.Choice choice = new ChatResponse.Choice();
        choice.setMessage(new Message("assistant", content));
        response.setChoices(List.of(choice));
        return response;
    }
}
