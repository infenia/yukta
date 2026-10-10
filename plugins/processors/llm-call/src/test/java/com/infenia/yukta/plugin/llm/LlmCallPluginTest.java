// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import com.infenia.yukta.util.VariableResolver;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.TooManyStaticImports",
  "PMD.CommentRequired",
  "PMD.AvoidDuplicateLiterals",
  "PMD.LawOfDemeter",
  "PMD.UseConcurrentHashMap",
  "unchecked"
})
@NoArgsConstructor
class LlmCallPluginTest {

  private final LlmGateway gateway = mock(LlmGateway.class);
  private final VariableResolver resolver = mock(VariableResolver.class);
  private LlmCallPlugin plugin;

  @BeforeEach
  void setUp() {
    plugin = new LlmCallPlugin(gateway, resolver);
    when(resolver.resolve(any(), any()))
        .thenAnswer(
            inv -> {
              final Object arg = inv.getArgument(0);
              return Mono.justOrEmpty(arg);
            });
  }

  private static Map<String, Object> config(final Object... extra) {
    final Map<String, Object> config = new HashMap<>();
    config.put("provider", "openai");
    config.put("model", "m");
    config.put("prompt", "hello");
    for (int i = 0; i + 1 < extra.length; i += 2) {
      config.put((String) extra[i], extra[i + 1]);
    }
    return config;
  }

  private Flux<Message<?>> run(final Map<String, Object> config) {
    return plugin.process(Flux.just(DefaultMessage.create(UUID.randomUUID(), "in")), config);
  }

  private static Map<String, Object> payload(final Message<?> message) {
    return (Map<String, Object>) message.payload();
  }

  @Test
  void metadataDescribesPlugin() {
    assertThat(plugin.getType()).isEqualTo("LLM_CALL");
    assertThat(plugin.describe().description()).isNotBlank();
    assertThat(plugin.describe().usagePattern()).contains("provider");
    assertThat(plugin.isBlocking()).isTrue();
  }

  @Test
  void outputPortsFollowRouteByStatus() {
    assertThat(plugin.getOutputPorts(Map.of())).containsExactly("default");
    assertThat(plugin.getOutputPorts(Map.of("routeByStatus", true)))
        .containsExactly("success", "failure");
  }

  @Test
  void successBuildsPayloadAndRequest() {
    when(gateway.call(any()))
        .thenReturn(
            new LlmResponse("Sure<agent_action>{\"a\":1}</agent_action>", 1_000, 500, "served"));
    final Map<String, Object> config =
        config(
            "baseUrl",
            "http://x",
            "apiKey",
            "k",
            "system",
            "sys",
            "temperature",
            0.5,
            "maxTokens",
            99,
            "timeout",
            7,
            "messages",
            List.of(Map.of("role", "assistant", "content", "earlier")),
            "prices",
            Map.of("input", 2.0, "output", 4.0));

    StepVerifier.create(run(config))
        .assertNext(
            m -> {
              final Map<String, Object> out = payload(m);
              assertThat(out).containsEntry("success", true).containsEntry("content", "Sure");
              assertThat(out).containsEntry("actions", List.of(Map.of("a", 1)));
              assertThat(out).containsEntry("model", "served");
              assertThat(out.get("usage"))
                  .isEqualTo(Map.of("inputTokens", 1000L, "outputTokens", 500L));
              assertThat((Double) out.get("cost")).isEqualTo(0.004);
              assertThat((Long) out.get("latencyMs")).isNotNegative();
              assertThat(m.metadata()).containsEntry("llmSuccess", true);
              assertThat(m.sourcePort()).isEqualTo("default");
            })
        .verifyComplete();

    final ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
    verify(gateway).call(captor.capture());
    final LlmRequest request = captor.getValue();
    assertThat(request.provider()).isEqualTo(Provider.OPENAI);
    assertThat(request.baseUrl()).isEqualTo("http://x");
    assertThat(request.apiKey()).isEqualTo("k");
    assertThat(request.model()).isEqualTo("m");
    assertThat(request.system()).isEqualTo("sys");
    assertThat(request.temperature()).isEqualTo(0.5);
    assertThat(request.maxTokens()).isEqualTo(99);
    assertThat(request.timeout()).isEqualTo(Duration.ofSeconds(7));
    assertThat(request.messages())
        .containsExactly(new ChatTurn("assistant", "earlier"), new ChatTurn("user", "hello"));
  }

  @Test
  void defaultsLeaveOptionalFieldsUnset() {
    when(gateway.call(any())).thenReturn(new LlmResponse("ok", 0, 0, "m"));

    StepVerifier.create(run(config()))
        .assertNext(m -> assertThat(payload(m)).doesNotContainKey("cost"))
        .verifyComplete();

    final ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
    verify(gateway).call(captor.capture());
    final LlmRequest request = captor.getValue();
    assertThat(request.baseUrl()).isNull();
    assertThat(request.apiKey()).isNull();
    assertThat(request.system()).isNull();
    assertThat(request.temperature()).isNull();
    assertThat(request.maxTokens()).isNull();
    assertThat(request.timeout()).isEqualTo(Duration.ofSeconds(120));
  }

  @Test
  void missingPriceCountsAsZero() {
    when(gateway.call(any())).thenReturn(new LlmResponse("ok", 1_000_000, 1_000_000, "m"));

    StepVerifier.create(run(config("prices", Map.of("output", 3.0))))
        .assertNext(m -> assertThat(payload(m)).containsEntry("cost", 3.0))
        .verifyComplete();
  }

  @Test
  void messagesWithoutMapsAreIgnoredAndPromptIsOptional() {
    when(gateway.call(any())).thenReturn(new LlmResponse("ok", 0, 0, "m"));
    final Map<String, Object> config =
        config("messages", List.of("junk", Map.of("role", "user", "content", "q")));
    config.remove("prompt");

    StepVerifier.create(run(config)).expectNextCount(1).verifyComplete();

    final ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
    verify(gateway).call(captor.capture());
    assertThat(captor.getValue().messages()).containsExactly(new ChatTurn("user", "q"));
  }

  @Test
  void unresolvedExpressionsCountAsUnset() {
    when(resolver.resolve(any(), any())).thenReturn(Mono.empty());
    when(gateway.call(any())).thenReturn(new LlmResponse("ok", 0, 0, "m"));
    final Map<String, Object> config = config("provider", "openai", "system", "${payload.none}");
    when(resolver.resolve(org.mockito.ArgumentMatchers.eq("openai"), any()))
        .thenReturn(Mono.just("openai"));
    when(resolver.resolve(org.mockito.ArgumentMatchers.eq("m"), any())).thenReturn(Mono.just("m"));
    when(resolver.resolve(org.mockito.ArgumentMatchers.eq("hello"), any()))
        .thenReturn(Mono.just("hello"));

    StepVerifier.create(run(config)).expectNextCount(1).verifyComplete();

    final ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
    verify(gateway).call(captor.capture());
    assertThat(captor.getValue().system()).isNull();
    assertThat(captor.getValue().timeout()).isEqualTo(Duration.ofSeconds(120));
  }

  @Test
  void messagesStringIsSpelOverTheMessage() {
    when(gateway.call(any())).thenReturn(new LlmResponse("ok", 0, 0, "m"));
    final Map<String, Object> payload =
        Map.of("history", List.of(Map.of("role", "user", "content", "earlier")));

    StepVerifier.create(
            plugin.process(
                Flux.just(DefaultMessage.create(UUID.randomUUID(), payload)),
                config("messages", "payload['history']")))
        .expectNextCount(1)
        .verifyComplete();

    final ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
    verify(gateway).call(captor.capture());
    assertThat(captor.getValue().messages())
        .containsExactly(new ChatTurn("user", "earlier"), new ChatTurn("user", "hello"));
  }

  @Test
  void messagesExpressionYieldingNothingCountsAsUnset() {
    when(gateway.call(any())).thenReturn(new LlmResponse("ok", 0, 0, "m"));

    StepVerifier.create(
            plugin.process(
                Flux.just(DefaultMessage.create(UUID.randomUUID(), Map.of())),
                config("messages", "payload['history']")))
        .expectNextCount(1)
        .verifyComplete();

    final ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
    verify(gateway).call(captor.capture());
    assertThat(captor.getValue().messages()).containsExactly(new ChatTurn("user", "hello"));
  }

  @Test
  void blankMessagesStringCountsAsUnset() {
    when(gateway.call(any())).thenReturn(new LlmResponse("ok", 0, 0, "m"));

    StepVerifier.create(run(config("messages", " "))).expectNextCount(1).verifyComplete();

    final ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
    verify(gateway).call(captor.capture());
    assertThat(captor.getValue().messages()).containsExactly(new ChatTurn("user", "hello"));
  }

  @Test
  void noTurnsFails() {
    final Map<String, Object> config = config();
    config.remove("prompt");

    StepVerifier.create(run(config)).expectError(IllegalArgumentException.class).verify();
  }

  @Test
  void nonPositiveTimeoutFails() {
    StepVerifier.create(run(config("timeout", 0)))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void errorModeWrapsProviderFailure() {
    when(gateway.call(any())).thenThrow(new IllegalStateException("429 rate limited"));

    StepVerifier.create(run(config()))
        .expectErrorSatisfies(
            e ->
                assertThat(e)
                    .isInstanceOf(WorkflowExecutionException.class)
                    .hasMessageContaining("429 rate limited"))
        .verify();
  }

  @Test
  void continueModeRoutesFailureAndSuccess() {
    final Map<String, Object> config = config("failureMode", "continue", "routeByStatus", true);

    when(gateway.call(any())).thenThrow(new IllegalStateException("boom"));
    StepVerifier.create(run(config))
        .assertNext(
            m -> {
              assertThat(m.sourcePort()).isEqualTo("failure");
              assertThat(m.payload()).isEqualTo("in");
              assertThat(m.metadata())
                  .containsEntry("llmSuccess", false)
                  .containsEntry("llmError", "boom");
            })
        .verifyComplete();

    org.mockito.Mockito.reset(gateway);
    when(gateway.call(any())).thenReturn(new LlmResponse("ok", 1, 1, "m"));
    StepVerifier.create(run(config))
        .assertNext(m -> assertThat(m.sourcePort()).isEqualTo("success"))
        .verifyComplete();
  }

  @Test
  void retriesThenSucceeds() {
    when(gateway.call(any()))
        .thenThrow(new IllegalStateException("flaky"))
        .thenReturn(new LlmResponse("ok", 1, 1, "m"));

    StepVerifier.create(run(config("retries", 1)))
        .assertNext(m -> assertThat(payload(m)).containsEntry("success", true))
        .verifyComplete();
    verify(gateway, times(2)).call(any());
  }

  @Test
  void exhaustedRetriesKeepOriginalError() {
    when(gateway.call(any())).thenThrow(new IllegalStateException("still down"));

    StepVerifier.create(run(config("retries", 1)))
        .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("still down"))
        .verify();
    verify(gateway, times(2)).call(any());
  }

  @Test
  void validateConfigAcceptsValid() {
    StepVerifier.create(plugin.validateConfig(config("timeout", 5))).verifyComplete();
    StepVerifier.create(plugin.validateConfig(config("provider", "${payload.provider}")))
        .verifyComplete();
    StepVerifier.create(
            plugin.validateConfig(
                config(
                    "messages",
                    "payload['history']",
                    "failureMode",
                    "CONTINUE",
                    "routeByStatus",
                    true)))
        .verifyComplete();
  }

  @Test
  void validateConfigRejectsInvalid() {
    final Map<String, Object> noModel = config();
    noModel.remove("model");
    final Map<String, Object> noInput = config();
    noInput.remove("prompt");
    final Map<String, Object> noProvider = config();
    noProvider.remove("provider");
    for (final Map<String, Object> bad :
        List.of(
            noModel,
            noInput,
            noProvider,
            config("provider", "gemini"),
            config("timeout", 0),
            config("retries", -1),
            config("routeByStatus", true),
            config("failureMode", "nope"))) {
      StepVerifier.create(plugin.validateConfig(bad))
          .expectError(IllegalArgumentException.class)
          .verify();
    }
  }
}
