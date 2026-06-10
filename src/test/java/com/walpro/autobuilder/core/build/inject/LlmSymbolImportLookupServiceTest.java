package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.LlmCircuitBreaker;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.core.build.inject.SymbolImportLookupService.Resolution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmSymbolImportLookupServiceTest {

    private LLMClient llmClient;
    private LlmCircuitBreaker breaker;
    private SymbolImportCache cache;
    private LlmSymbolImportLookupService service;

    @BeforeEach
    void setUp() {
        llmClient = mock(LLMClient.class);
        breaker = mock(LlmCircuitBreaker.class);
        cache = mock(SymbolImportCache.class);
        when(breaker.isOpen()).thenReturn(false);
        service = new LlmSymbolImportLookupService(llmClient, breaker, cache);
    }

    private void answerWith(String content) {
        ChatResponse chat = new ChatResponse();
        ChatResponse.Choice choice = new ChatResponse.Choice();
        Message m = new Message();
        m.setRole("assistant");
        m.setContent(content);
        choice.setMessage(m);
        chat.setChoices(List.of(choice));
        when(llmClient.ask(any())).thenReturn(ResponseEntity.ok(chat));
    }

    @Test
    void parseResponse_validJson_returnsResolution() {
        Optional<Resolution> r = LlmSymbolImportLookupService.parseResponse(
                "{\"fqn\":\"java.util.List\",\"group\":\"\",\"artifactId\":\"\",\"version\":\"\"}", "List");
        assertThat(r).isPresent();
        assertThat(r.get().fqn()).isEqualTo("java.util.List");
        assertThat(r.get().hasGav()).isFalse();
    }

    @Test
    void parseResponse_withGav_returnsResolutionWithCoords() {
        Optional<Resolution> r = LlmSymbolImportLookupService.parseResponse(
                "{\"fqn\":\"x.y.Z\",\"group\":\"g\",\"artifactId\":\"a\",\"version\":\"1.0\"}", "Z");
        assertThat(r).isPresent();
        assertThat(r.get().groupId()).isEqualTo("g");
        assertThat(r.get().artifactId()).isEqualTo("a");
        assertThat(r.get().version()).isEqualTo("1.0");
    }

    @Test
    void parseResponse_noneSentinel_returnsEmpty() {
        assertThat(LlmSymbolImportLookupService.parseResponse("NONE", "X")).isEmpty();
        assertThat(LlmSymbolImportLookupService.parseResponse("  NONE  ", "X")).isEmpty();
    }

    @Test
    void parseResponse_codeFenceWrapped_stillParses() {
        Optional<Resolution> r = LlmSymbolImportLookupService.parseResponse(
                "```json\n{\"fqn\":\"x.y.Z\",\"group\":\"g\",\"artifactId\":\"a\",\"version\":\"\"}\n```", "Z");
        assertThat(r).isPresent();
        assertThat(r.get().fqn()).isEqualTo("x.y.Z");
    }

    @Test
    void parseResponse_garbage_returnsEmpty() {
        assertThat(LlmSymbolImportLookupService.parseResponse("dunno mate", "X")).isEmpty();
        assertThat(LlmSymbolImportLookupService.parseResponse("", "X")).isEmpty();
        assertThat(LlmSymbolImportLookupService.parseResponse(null, "X")).isEmpty();
    }

    @Test
    void parseResponse_fqnWithoutDot_rejected() {
        // A single-token "fqn" almost always means the LLM made something up.
        assertThat(LlmSymbolImportLookupService.parseResponse(
                "{\"fqn\":\"NotARealFqn\",\"group\":\"\",\"artifactId\":\"\",\"version\":\"\"}", "NotARealFqn"))
                .isEmpty();
    }

    @Test
    void lookup_poisonedCacheHit_isInvalidated_andFreshLookupRuns() {
        // Self-heal: entries cached before the simple-name invariant existed get invalidated
        // and re-asked instead of refiring the hallucinated import forever.
        when(cache.get("BookNotFoundException")).thenReturn(Optional.of(new Resolution(
                "org.springframework.data.rest.webmvc.ResourceNotFoundException",
                "org.springframework.data", "spring-data-rest-webmvc", "3.4.2")));
        answerWith("NONE");

        Optional<Resolution> r = service.lookup("BookNotFoundException");

        assertThat(r).isEmpty();
        verify(cache).invalidate("BookNotFoundException");
        verify(llmClient).ask(any());
    }

    @Test
    void lookup_cacheHit_skipsLlm() {
        when(cache.get("Cached"))
                .thenReturn(Optional.of(new Resolution("p.q.Cached", null, null, null)));

        Optional<Resolution> r = service.lookup("Cached");

        assertThat(r).isPresent();
        verify(llmClient, never()).ask(any());
    }

    @Test
    void lookup_cachedMiss_skipsLlmAndReturnsEmpty() {
        when(cache.get("KnownMiss")).thenReturn(Optional.empty());

        Optional<Resolution> r = service.lookup("KnownMiss");

        assertThat(r).isEmpty();
        verify(llmClient, never()).ask(any());
    }

    @Test
    void lookup_unknown_callsLlm_storesHit() {
        when(cache.get("Fresh")).thenReturn(null);
        answerWith("{\"fqn\":\"p.q.Fresh\",\"group\":\"g\",\"artifactId\":\"a\",\"version\":\"\"}");

        Optional<Resolution> r = service.lookup("Fresh");

        assertThat(r).isPresent();
        ArgumentCaptor<Resolution> stored = ArgumentCaptor.forClass(Resolution.class);
        verify(cache).putHit(any(), stored.capture());
        assertThat(stored.getValue().fqn()).isEqualTo("p.q.Fresh");
    }

    @Test
    void lookup_unknown_llmReturnsNone_storesMiss() {
        when(cache.get("Unknown")).thenReturn(null);
        answerWith("NONE");

        Optional<Resolution> r = service.lookup("Unknown");

        assertThat(r).isEmpty();
        verify(cache).putMiss("Unknown");
    }

    @Test
    void lookup_circuitOpen_returnsEmptyWithoutCallingLlm() {
        when(cache.get("Blocked")).thenReturn(null);
        when(breaker.isOpen()).thenReturn(true);

        Optional<Resolution> r = service.lookup("Blocked");

        assertThat(r).isEmpty();
        verify(llmClient, never()).ask(any());
    }

    @Test
    void lookup_invalidSimpleName_returnsEmpty() {
        assertThat(service.lookup(null)).isEmpty();
        assertThat(service.lookup("")).isEmpty();
        assertThat(service.lookup("  ")).isEmpty();
        assertThat(service.lookup("not.a.simple.name")).isEmpty();
        assertThat(service.lookup("has space")).isEmpty();
        verify(llmClient, never()).ask(any());
    }

    @Test
    void markResolutionFailed_flipsCachedHitToMiss() {
        when(cache.get("Bad"))
                .thenReturn(Optional.of(new Resolution("wrong.Fqn", "g", "a", null)));

        service.markResolutionFailed("Bad");

        verify(cache).putMiss("Bad");
    }

    @Test
    void markResolutionFailed_unknownEntry_isNoOp() {
        when(cache.get("Untouched")).thenReturn(null);

        service.markResolutionFailed("Untouched");

        verify(cache, never()).putMiss(any());
    }

    @Test
    void markResolutionFailed_existingMiss_isNoOp() {
        when(cache.get("AlreadyMissed")).thenReturn(Optional.empty());

        service.markResolutionFailed("AlreadyMissed");

        verify(cache, never()).putMiss(any());
    }

    @Test
    void parseResponse_fqnWithDifferentSimpleName_rejectedAsHallucination() {
        // 2026-06-10 field misfire: "Resolve symbol: BookNotFoundException" answered with
        // Spring's ResourceNotFoundException. An import can never resolve a symbol of a
        // different simple name.
        assertThat(LlmSymbolImportLookupService.parseResponse(
                "{\"fqn\":\"org.springframework.data.rest.webmvc.ResourceNotFoundException\","
                        + "\"group\":\"org.springframework.data\",\"artifactId\":\"spring-data-rest-webmvc\",\"version\":\"3.4.2\"}",
                "BookNotFoundException"))
                .isEmpty();
    }

    @Test
    void matchesSimpleName_trailingSegmentMustEqualSymbol() {
        assertThat(LlmSymbolImportLookupService.matchesSimpleName("java.util.List", "List")).isTrue();
        assertThat(LlmSymbolImportLookupService.matchesSimpleName("java.util.List", "ArrayList")).isFalse();
        assertThat(LlmSymbolImportLookupService.matchesSimpleName(null, "X")).isFalse();
    }
}
