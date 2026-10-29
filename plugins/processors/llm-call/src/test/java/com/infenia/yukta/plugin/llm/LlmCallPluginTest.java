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
import com.infenia.yukta.plugin.core.PluginMetricsReporter;
import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import com.infenia.yukta.plugin.util.VariableResolver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.TooManyStaticImports",
  "PMD.AvoidDuplicateLiterals",
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

  private Flux<Message<?>> runInRun(final Map<String, Object> config, final String executionId) {
    return run(config).contextWrite(reactor.util.context.Context.of("executionId", executionId));
  }

  private int peakConcurrency(final Map<String, Object> config, final int count) {
    final AtomicInteger inFlight = new AtomicInteger();
    final AtomicInteger peak = new AtomicInteger();
    when(gateway.call(any()))
        .thenAnswer(
            invocation -> {
              peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
              try {
                // simulated call duration so calls overlap
                Thread.sleep(30); // nosem: yukta-test-thread-sleep
              } finally {
                inFlight.decrementAndGet();
              }
              return new LlmResponse("ok", 1, 1, "m");
            });
    StepVerifier.create(
            plugin.process(
                Flux.range(0, count).map(i -> DefaultMessage.create(UUID.randomUUID(), "in")),
                config))
        .expectNextCount(count)
        .verifyComplete();
    return peak.get();
  }

  @Test
  void handlesAtMostTheConfiguredNumberOfCallsAtOnce() {
    assertThat(peakConcurrency(config("concurrency", 3), 20)).isBetween(2, 3);
  }

  @Test
  void withoutConcurrencySixteenCallsAreInFlightAtMost() {
    assertThat(peakConcurrency(config(), 60)).isLessThanOrEqualTo(16).isGreaterThan(4);
  }

  @Test
  void validateConfigRejectsNonPositiveConcurrency() {
    StepVerifier.create(plugin.validateConfig(config("concurrency", 0)))
        .expectErrorMessage("concurrency must be a positive whole number, got: 0")
        .verify();
  }

  @Test
  void tokenBudget_stopsTheRunOnceUsed_andOtherRunsAreNotAffected() {
    when(gateway.call(any())).thenReturn(new LlmResponse("ok", 60, 40, "m"));
    final Map<String, Object> config = config("budget", Map.of("maxTokens", 150));

    StepVerifier.create(runInRun(config, "r1")).expectNextCount(1).verifyComplete();
    StepVerifier.create(runInRun(config, "r1")).expectNextCount(1).verifyComplete();
    // 200 tokens used now, over the 150 limit: the next call is refused
    StepVerifier.create(runInRun(config, "r1"))
        .verifyErrorSatisfies(
            e -> assertThat(e).hasMessageContaining("budget exceeded").hasMessageContaining("150"));
    StepVerifier.create(runInRun(config, "r2")).expectNextCount(1).verifyComplete();
  }

  @Test
  void costBudget_usesPrices_continueModeEmitsFailure() {
    when(gateway.call(any())).thenReturn(new LlmResponse("ok", 1_000_000, 0, "m"));
    final Map<String, Object> config =
        config(
            "budget", Map.of("maxCost", 1.5),
            "prices", Map.of("input", 2.0, "output", 4.0),
            "failureMode", "CONTINUE");

    StepVerifier.create(runInRun(config, "r1")).expectNextCount(1).verifyComplete();
    StepVerifier.create(runInRun(config, "r1"))
        .assertNext(
            m -> {
              assertThat(m.metadata()).containsEntry("llmSuccess", false);
              assertThat(String.valueOf(m.metadata().get("llmError"))).contains("cost budget");
            })
        .verifyComplete();
  }

  @Test
  void hardStopBudget_endsTheRun_evenWithFailureModeContinue() {
    when(gateway.call(any())).thenReturn(new LlmResponse("ok", 100, 100, "m"));
    final Map<String, Object> config =
        config("budget", Map.of("maxTokens", 150, "hardStop", true), "failureMode", "CONTINUE");

    StepVerifier.create(runInRun(config, "r1")).expectNextCount(1).verifyComplete();
    StepVerifier.create(runInRun(config, "r1"))
        .verifyErrorSatisfies(
            e ->
                assertThat(e)
                    .isInstanceOf(com.infenia.yukta.plugin.exception.RunAbortedException.class)
                    .hasMessageContaining("budget exceeded"));
  }

  @Test
  void hardStopMustBeBoolean() {
    StepVerifier.create(
            plugin.validateConfig(config("budget", Map.of("maxTokens", 10, "hardStop", "yes"))))
        .expectErrorMessage("budget.hardStop must be true or false")
        .verify();
  }

  @Test
  void toolExchangeInMessages_reachesTheGatewayAsToolTurns() {
    when(gateway.call(any())).thenReturn(new LlmResponse("ok", 1, 1, "m"));
    final Map<String, Object> config =
        config(
            "messages",
            List.of(
                Map.of("role", "user", "content", "weather?"),
                Map.of(
                    "role",
                    "assistant",
                    "content",
                    "",
                    "actions",
                    List.of(
                        Map.of("tool", "weather", "arguments", Map.of("c", "Oslo"), "id", "c1"))),
                Map.of(
                    "role", "tool", "content", "rain", "toolCallId", "c1", "toolName", "weather")));

    StepVerifier.create(run(config)).expectNextCount(1).verifyComplete();

    final var captured = ArgumentCaptor.forClass(LlmRequest.class);
    verify(gateway).call(captured.capture());
    final var turns = captured.getValue().messages();
    assertThat(turns.get(1).toolCalls())
        .containsExactly(new ToolCallRequest("c1", "weather", "{\"c\":\"Oslo\"}"));
    assertThat(turns.get(2).toolCallId()).isEqualTo("c1");
    assertThat(turns.get(2).toolName()).isEqualTo("weather");
  }

  @Test
  void turnWithoutContent_isReplayedAsEmptyTextNotTheWordNull() {
    when(gateway.call(any())).thenReturn(new LlmResponse("ok", 1, 1, "m"));
    final Map<String, Object> config =
        config(
            "messages",
            List.of(
                Map.of("role", "user", "content", "weather?"),
                // an assistant turn that only carries tool calls has no content
                Map.of(
                    "role",
                    "assistant",
                    "actions",
                    List.of(
                        Map.of("tool", "weather", "arguments", Map.of("c", "Oslo"), "id", "c1"))),
                // a tool turn without content
                Map.of("role", "tool", "toolCallId", "c1", "toolName", "weather")));

    StepVerifier.create(run(config)).expectNextCount(1).verifyComplete();

    final var captured = ArgumentCaptor.forClass(LlmRequest.class);
    verify(gateway).call(captured.capture());
    final var turns = captured.getValue().messages();
    assertThat(turns.get(1).content()).isEmpty();
    assertThat(turns.get(2).content()).isEmpty();
  }

  @Test
  void budgetIsValidated() {
    StepVerifier.create(plugin.validateConfig(config("budget", Map.of("maxTokens", 0))))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(plugin.validateConfig(config("budget", Map.of("maxTokens", "lots"))))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(plugin.validateConfig(config("budget", Map.of("maxCost", 2.0))))
        .expectErrorMessage("budget.maxCost needs prices to measure cost")
        .verify();
    StepVerifier.create(
            plugin.validateConfig(
                config(
                    "budget",
                    Map.of("maxTokens", 10, "maxCost", 2.0),
                    "prices",
                    Map.of("input", 1.0))))
        .verifyComplete();
    StepVerifier.create(plugin.validateConfig(config("budget", Map.of()))).verifyComplete();
    StepVerifier.create(
            plugin.validateConfig(config("budget", Map.of("maxTokens", 10, "hardStop", true))))
        .verifyComplete();
  }

  @Test
  void tools_areSentToTheModel_andItsToolCallsComeBackAsActions() {
    when(gateway.call(any()))
        .thenReturn(
            new LlmResponse(
                "Looking.<agent_action>{\"x\":1}</agent_action>",
                10,
                5,
                "m",
                List.of(new ToolCallRequest("c1", "weather", "{\"city\":\"Pune\"}"))));

    StepVerifier.create(
            run(config("tools", List.of(Map.of("name", "weather", "description", "Weather")))))
        .assertNext(
            m ->
                assertThat((List<Object>) payload(m).get("actions"))
                    .containsExactly(
                        Map.of("x", 1),
                        Map.of("tool", "weather", "arguments", Map.of("city", "Pune"), "id", "c1")))
        .verifyComplete();

    final ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
    verify(gateway).call(captor.capture());
    assertThat(captor.getValue().tools()).extracting(ToolSpec::name).containsExactly("weather");
  }

  @Test
  void tools_canBeAnExpressionOverTheMessage() {
    when(gateway.call(any())).thenReturn(new LlmResponse("ok", 1, 1, "m"));
    final Message<?> message =
        DefaultMessage.create(
            UUID.randomUUID(), Map.of("tools", List.of(Map.of("name", "from-payload"))));

    StepVerifier.create(plugin.process(Flux.just(message), config("tools", "payload['tools']")))
        .expectNextCount(1)
        .verifyComplete();

    final ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
    verify(gateway).call(captor.capture());
    assertThat(captor.getValue().tools())
        .extracting(ToolSpec::name)
        .containsExactly("from-payload");
  }

  @Test
  void toolsAreValidated() {
    StepVerifier.create(plugin.validateConfig(config("tools", List.of(Map.of("name", "ok")))))
        .verifyComplete();
    StepVerifier.create(plugin.validateConfig(config("tools", List.of(Map.of()))))
        .expectError(IllegalArgumentException.class)
        .verify();
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

  @Test
  void defaultTimeout_coversEveryAttemptAndTheBackoff() {
    // one attempt: (120 + 1) s + 5 s margin + 0.5 s backoff allowance
    assertThat(plugin.getDefaultTimeout(Map.of())).isEqualTo(Duration.ofMillis(126_500));
    // three attempts of 10 s: 3 * 11 + 5 s margin + 0.5 s * 4
    assertThat(plugin.getDefaultTimeout(Map.of("timeout", 10, "retries", 2)))
        .isEqualTo(Duration.ofSeconds(40));
  }

  @Test
  void defaultTimeout_ignoresUnusableValuesAndBoundsTheRetries() {
    assertThat(plugin.getDefaultTimeout(Map.of("timeout", -5, "retries", -1)))
        .isEqualTo(Duration.ofMillis(126_500));
    assertThat(plugin.getDefaultTimeout(Map.of("timeout", "#{x}", "retries", "#{y}")))
        .isEqualTo(Duration.ofMillis(126_500));
    // retries above 10 count as 10: 11 * 121 + 5 s + 0.5 s * 1024
    assertThat(plugin.getDefaultTimeout(Map.of("retries", 500)))
        .isEqualTo(Duration.ofSeconds(11 * 121 + 5 + 512));
  }

  private static ObjectProvider<PluginMetricsReporter> provider(final List<String> counts) {
    final StaticListableBeanFactory factory = new StaticListableBeanFactory();
    factory.addBean(
        "reporter", (PluginMetricsReporter) (node, metric, tag) -> counts.add(metric + ":" + tag));
    return factory.getBeanProvider(PluginMetricsReporter.class);
  }

  @Test
  void springConstructorReportsMetrics() {
    final List<String> counts = new ArrayList<>();
    plugin = new LlmCallPlugin(gateway, resolver, provider(counts));
    when(gateway.call(any())).thenReturn(new LlmResponse("ok", 0, 0, "m"));
    StepVerifier.create(run(config())).expectNextCount(1).verifyComplete();
    assertThat(counts).containsExactly("llm:OK");
    assertThat(
            new LlmCallPlugin(
                gateway,
                resolver,
                new StaticListableBeanFactory().getBeanProvider(PluginMetricsReporter.class)))
        .isNotNull();
  }

  @Test
  void tokensAndCostAreMeteredPerProvider() {
    final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    final StaticListableBeanFactory factory = new StaticListableBeanFactory();
    factory.addBean("meters", meters);
    plugin =
        new LlmCallPlugin(
            gateway,
            resolver,
            new StaticListableBeanFactory().getBeanProvider(PluginMetricsReporter.class),
            factory.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
    when(gateway.call(any())).thenReturn(new LlmResponse("ok", 1_000_000, 500_000, "m"));

    StepVerifier.create(run(config("prices", Map.of("input", 2.0, "output", 4.0))))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(run(config())).expectNextCount(1).verifyComplete();

    assertThat(
            meters
                .get("llm.tokens")
                .tag("provider", "OPENAI")
                .tag("direction", "input")
                .counter()
                .count())
        .isEqualTo(2_000_000.0);
    assertThat(
            meters
                .get("llm.tokens")
                .tag("provider", "OPENAI")
                .tag("direction", "output")
                .counter()
                .count())
        .isEqualTo(1_000_000.0);
    assertThat(meters.get("llm.cost").tag("provider", "OPENAI").counter().count()).isEqualTo(4.0);
  }
}
