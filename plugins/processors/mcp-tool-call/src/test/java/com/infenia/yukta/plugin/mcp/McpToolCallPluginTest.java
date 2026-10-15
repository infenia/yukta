// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.PluginMetricsReporter;
import com.infenia.yukta.plugin.core.WorkflowContext;
import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import com.infenia.yukta.util.VariableResolver;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.CommentRequired",
  "PMD.AvoidDuplicateLiterals",
  "PMD.LawOfDemeter",
  "PMD.TooManyStaticImports",
  "unchecked"
})
@NoArgsConstructor
class McpToolCallPluginTest {

  private static final String ACCEPTED = "ACCEPTED";

  private final McpClientGateway gateway = mock(McpClientGateway.class);
  private final VariableResolver resolver = mock(VariableResolver.class);
  private final PluginMetricsReporter reporter = mock(PluginMetricsReporter.class);
  private McpToolCallPlugin plugin;

  @BeforeEach
  void setUp() {
    final ObjectProvider<PluginMetricsReporter> reporters = mock(ObjectProvider.class);
    when(reporters.getIfAvailable(any())).thenReturn(reporter);
    final ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters =
        mock(ObjectProvider.class);
    plugin = new McpToolCallPlugin(gateway, resolver, reporters, meters);
    when(resolver.resolve(any(), any()))
        .thenAnswer(inv -> Mono.justOrEmpty(inv.<Object>getArgument(0)));
  }

  private static Message<?> input() {
    return DefaultMessage.create(UUID.randomUUID(), Map.of("city", "Pune"));
  }

  private static Map<String, Object> config(final Object... extra) {
    final Map<String, Object> config = new java.util.concurrent.ConcurrentHashMap<>();
    config.put("url", "http://localhost/mcp");
    config.put("tool", "weather");
    for (int i = 0; i < extra.length; i += 2) {
      config.put((String) extra[i], extra[i + 1]);
    }
    return config;
  }

  private static McpCallResult success() {
    return new McpCallResult(false, "sunny", List.of(), null, null);
  }

  private static McpCallResult bad() {
    return new McpCallResult(true, "boom", List.of(), null, "boom");
  }

  @Test
  void allowedTools_refuseOtherTools_withoutCallingTheServer() {
    when(gateway.call(any(), any())).thenReturn(Mono.just(success()));

    StepVerifier.create(
            plugin.process(Flux.just(input()), config("allowedTools", List.of("weather"))))
        .expectNextCount(1)
        .verifyComplete();

    StepVerifier.create(
            plugin.process(Flux.just(input()), config("allowedTools", List.of("search", "read"))))
        .verifyErrorSatisfies(
            e ->
                assertThat(e)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'weather' is not in allowedTools"));
    verify(gateway, org.mockito.Mockito.times(1)).call(any(), any());

    // an empty list restricts nothing
    StepVerifier.create(plugin.process(Flux.just(input()), config("allowedTools", List.of())))
        .expectNextCount(1)
        .verifyComplete();
  }

  private static Message<?> decision(final String decision) {
    return DefaultMessage.create(
        UUID.randomUUID(),
        Map.of("executionId", "e", "nodeId", "n", "name", "approve", "decision", decision));
  }

  /** Sends the decision once the node is waiting for it. */
  private void answerWhenWaiting(final String decision) throws InterruptedException {
    for (int i = 0; i < 200; i++) {
      final Message<?> answer =
          java.util.Objects.requireNonNull(plugin.onControlSignal(decision(decision)).block());
      if (ACCEPTED.equals(String.valueOf(answer.payload()))) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("the node never waited for an approval");
  }

  @Test
  void requireApproval_waitsForTheSignalThenCalls() throws InterruptedException {
    when(gateway.call(any(), any())).thenReturn(Mono.just(success()));
    final var result =
        plugin
            .process(Flux.just(input()), config("requireApproval", List.of("weather")))
            .contextWrite(Context.of("executionId", "e", "nodeId", "n"))
            .collectList()
            .toFuture();

    answerWhenWaiting("approve");

    assertThat(result.join()).hasSize(1);
    verify(gateway).call(any(), any());
  }

  @Test
  void requireApproval_rejectRefusesTheCall() throws InterruptedException {
    final var result =
        plugin
            .process(Flux.just(input()), config("requireApproval", List.of("*")))
            .contextWrite(Context.of("executionId", "e", "nodeId", "n"))
            .collectList()
            .toFuture();

    answerWhenWaiting("reject");

    assertThat(result)
        .failsWithin(Duration.ofSeconds(5))
        .withThrowableThat()
        .withMessageContaining("was rejected");
    verify(gateway, org.mockito.Mockito.never()).call(any(), any());
  }

  @Test
  void requireApproval_noAnswerRefusesTheCall_andOtherToolsAreNotHeld() {
    StepVerifier.create(
            plugin.process(
                Flux.just(input()),
                config("requireApproval", List.of("weather"), "approvalTimeoutSeconds", 1)))
        .verifyErrorSatisfies(e -> assertThat(e).hasMessageContaining("within"));

    when(gateway.call(any(), any())).thenReturn(Mono.just(success()));
    StepVerifier.create(
            plugin.process(Flux.just(input()), config("requireApproval", List.of("search"))))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void requireApproval_isValidatedAndExtendsTheNodeTimeout() {
    StepVerifier.create(plugin.validateConfig(config("requireApproval", "weather")))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(
            plugin.validateConfig(
                config("requireApproval", List.of("a"), "approvalTimeoutSeconds", 0)))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(
            plugin.validateConfig(
                config("requireApproval", List.of("a"), "approvalTimeoutSeconds", 30)))
        .verifyComplete();
    assertThat(plugin.getDefaultTimeout(config("requireApproval", List.of("a"))))
        .isEqualTo(Duration.ofSeconds(65 + 3600));
    assertThat(plugin.getDefaultTimeout(config("requireApproval", List.of())))
        .isEqualTo(Duration.ofSeconds(65));
  }

  @Test
  void allowedTools_continueMode_emitsFailure() {
    StepVerifier.create(
            plugin.process(
                Flux.just(input()),
                config("allowedTools", List.of("search"), "failureMode", "CONTINUE")))
        .assertNext(out -> assertThat(out.metadata()).containsEntry("mcpSuccess", false))
        .verifyComplete();
  }

  @Test
  void allowedTools_isValidated() {
    StepVerifier.create(plugin.validateConfig(config("allowedTools", "weather")))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(plugin.validateConfig(config("allowedTools", List.of("a", " "))))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(plugin.validateConfig(config("allowedTools", List.of(5))))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(plugin.validateConfig(config("allowedTools", List.of("a", "b"))))
        .verifyComplete();
    StepVerifier.create(plugin.validateConfig(config("allowedTools", List.of()))).verifyComplete();
  }

  @Test
  void metadataDescribesPlugin() {
    assertThat(plugin.getType()).isEqualTo("MCP_TOOL_CALL");
    assertThat(plugin.describe().usagePattern()).contains("url", "tool");
    assertThat(plugin.describe().uiDesign()).isPresent();
  }

  @Test
  void process_callsToolWithPayloadAsArgumentsAndIdempotencyMeta() {
    when(gateway.call(any(), any())).thenReturn(Mono.just(success()));
    final Message<?> msg = input();
    StepVerifier.create(plugin.process(Flux.just(msg), config()))
        .assertNext(
            out -> {
              assertThat((Map<String, Object>) out.payload()).containsEntry("text", "sunny");
              assertThat(out.metadata()).containsEntry("mcpSuccess", true);
              assertThat(out.sourcePort()).isEqualTo("default");
            })
        .verifyComplete();
    final ArgumentCaptor<McpCallSpec> spec = ArgumentCaptor.forClass(McpCallSpec.class);
    verify(gateway).call(spec.capture(), any());
    assertThat(spec.getValue().arguments()).containsEntry("city", "Pune");
    assertThat(spec.getValue().meta()).containsEntry("yukta/messageId", msg.id());
    verify(reporter).increment("unknown", "mcp_tool_call", "SUCCESS");
  }

  @Test
  void process_authTokenBecomesBearerHeader() {
    when(gateway.call(any(), any())).thenReturn(Mono.just(success()));
    plugin.process(Flux.just(input()), config("authToken", "tok")).blockLast();
    final ArgumentCaptor<McpCallSpec> spec = ArgumentCaptor.forClass(McpCallSpec.class);
    verify(gateway).call(spec.capture(), any());
    assertThat(spec.getValue().headers()).containsEntry("Authorization", "Bearer tok");
  }

  @Test
  void process_argumentsExpressionIsEvaluated() {
    when(gateway.call(any(), any())).thenReturn(Mono.just(success()));
    plugin.process(Flux.just(input()), config("arguments", "payload")).blockLast();
    final ArgumentCaptor<McpCallSpec> spec = ArgumentCaptor.forClass(McpCallSpec.class);
    verify(gateway).call(spec.capture(), any());
    assertThat(spec.getValue().arguments()).containsEntry("city", "Pune");
  }

  @Test
  void process_toolErrorFailsNodeByDefault() {
    when(gateway.call(any(), any())).thenReturn(Mono.just(bad()));
    StepVerifier.create(plugin.process(Flux.just(input()), config()))
        .verifyError(WorkflowExecutionException.class);
  }

  @Test
  void process_continueRoutesFailurePort() {
    when(gateway.call(any(), any())).thenReturn(Mono.just(bad()));
    final Map<String, Object> config = config("failureMode", "CONTINUE", "routeByStatus", true);
    StepVerifier.create(plugin.process(Flux.just(input()), config))
        .assertNext(out -> assertThat(out.sourcePort()).isEqualTo("failure"))
        .verifyComplete();
    assertThat(plugin.getOutputPorts(config)).containsExactly("success", "failure");
  }

  @Test
  void process_transportErrorBecomesFailureWhenContinue() {
    when(gateway.call(any(), any())).thenReturn(Mono.error(new IllegalStateException("down")));
    StepVerifier.create(plugin.process(Flux.just(input()), config("failureMode", "CONTINUE")))
        .assertNext(out -> assertThat(out.metadata()).containsEntry("mcpSuccess", false))
        .verifyComplete();
  }

  @Test
  void process_resultKeyCopiesTextToMetadata() {
    when(gateway.call(any(), any())).thenReturn(Mono.just(success()));
    StepVerifier.create(plugin.process(Flux.just(input()), config("resultKey", "observation")))
        .assertNext(out -> assertThat(out.metadata()).containsEntry("observation", "sunny"))
        .verifyComplete();
  }

  @Test
  void process_passesHeartbeatFromReactorContext() {
    final AtomicInteger beats = new AtomicInteger();
    when(gateway.call(any(), any()))
        .thenAnswer(
            inv -> {
              ((Runnable) inv.getArgument(1)).run();
              return Mono.just(success());
            });
    StepVerifier.create(
            plugin
                .process(Flux.just(input()), config())
                .contextWrite(
                    Context.of(WorkflowContext.HEARTBEAT_KEY, (Runnable) beats::incrementAndGet)))
        .expectNextCount(1)
        .verifyComplete();
    assertThat(beats).hasValue(1);
  }

  @Test
  void process_cancelPropagatesToGateway() {
    final var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    when(gateway.call(any(), any()))
        .thenReturn(Mono.<McpCallResult>never().doOnCancel(() -> cancelled.set(true)));
    StepVerifier.create(plugin.process(Flux.just(input()), config()))
        .expectSubscription()
        .thenAwait(Duration.ofMillis(50))
        .thenCancel()
        .verify();
    assertThat(cancelled).isTrue();
  }

  @Test
  void timeout_acceptsNumbersAndNumericStrings() {
    assertThat(plugin.getDefaultTimeout(config())).isEqualTo(Duration.ofSeconds(65));
    assertThat(plugin.getDefaultTimeout(config("timeout", 10))).isEqualTo(Duration.ofSeconds(15));
    assertThat(plugin.getDefaultTimeout(config("timeout", "20"))).isEqualTo(Duration.ofSeconds(25));
    assertThat(plugin.getDefaultTimeout(config("timeout", "${x}")))
        .isEqualTo(Duration.ofSeconds(65));
  }

  @Test
  void validateConfig_rejectsBadConfig() {
    StepVerifier.create(plugin.validateConfig(config())).verifyComplete();
    StepVerifier.create(plugin.validateConfig(Map.of("tool", "t")))
        .verifyError(IllegalArgumentException.class);
    StepVerifier.create(plugin.validateConfig(config("url", "ftp://x")))
        .verifyError(IllegalArgumentException.class);
    StepVerifier.create(plugin.validateConfig(config("timeout", 0)))
        .verifyError(IllegalArgumentException.class);
    StepVerifier.create(plugin.validateConfig(config("routeByStatus", true)))
        .verifyError(IllegalArgumentException.class);
  }

  @Test
  void validateInContext_routeByStatusRejectsOtherPorts() {
    final Map<String, Object> config = config("failureMode", "CONTINUE", "routeByStatus", true);
    final var good =
        new WorkflowContext(
            "n", List.of(new WorkflowContext.WorkflowEdge("n", "a", "success")), List.of());
    final var bad =
        new WorkflowContext(
            "n", List.of(new WorkflowContext.WorkflowEdge("n", "a", "default")), List.of());
    StepVerifier.create(plugin.validateInContext(good, config)).verifyComplete();
    StepVerifier.create(plugin.validateInContext(bad, config))
        .verifyError(IllegalArgumentException.class);
  }

  @Test
  void onControlSignal_resetDropsSessions() {
    StepVerifier.create(plugin.onControlSignal(DefaultMessage.create(UUID.randomUUID(), "reset")))
        .verifyComplete();
    verify(gateway).reset();
  }

  @Test
  void defaults_noRouteMeansSingleDefaultPortAndNoContextConstraint() {
    assertThat(plugin.getOutputPorts(config())).containsExactly("default");
    final var anyPort =
        new WorkflowContext(
            "n", List.of(new WorkflowContext.WorkflowEdge("n", "a", "whatever")), List.of());
    StepVerifier.create(plugin.validateInContext(anyPort, config())).verifyComplete();
    final var blankPort =
        new WorkflowContext(
            "n", List.of(new WorkflowContext.WorkflowEdge("n", "a", null)), List.of());
    StepVerifier.create(
            plugin.validateInContext(
                blankPort, config("failureMode", "CONTINUE", "routeByStatus", true)))
        .verifyComplete();
  }

  @Test
  void prepare_warmsExpressionsOnlyForStrings() {
    StepVerifier.create(plugin.prepare(config("arguments", "payload"))).verifyComplete();
    StepVerifier.create(plugin.prepare(config("arguments", Map.of("a", "b")))).verifyComplete();
    StepVerifier.create(plugin.prepare(config())).verifyComplete();
  }

  @Test
  void process_argumentsMapIsResolvedAndHeadersPassed() {
    when(gateway.call(any(), any())).thenReturn(Mono.just(success()));
    plugin
        .process(
            Flux.just(input()),
            config("arguments", Map.of("q", "x"), "headers", Map.of("X-A", "1"), "timeout", "7"))
        .blockLast();
    final ArgumentCaptor<McpCallSpec> spec = ArgumentCaptor.forClass(McpCallSpec.class);
    verify(gateway).call(spec.capture(), any());
    assertThat(spec.getValue().arguments()).containsEntry("q", "x");
    assertThat(spec.getValue().headers()).containsEntry("X-A", "1");
    assertThat(spec.getValue().timeout()).isEqualTo(Duration.ofSeconds(7));
  }

  @Test
  void process_nonMapPayloadBecomesInputArgument() {
    when(gateway.call(any(), any())).thenReturn(Mono.just(success()));
    plugin
        .process(Flux.just((Message<?>) DefaultMessage.create(UUID.randomUUID(), "hi")), config())
        .blockLast();
    final ArgumentCaptor<McpCallSpec> spec = ArgumentCaptor.forClass(McpCallSpec.class);
    verify(gateway).call(spec.capture(), any());
    assertThat(spec.getValue().arguments()).containsEntry("input", "hi");
  }

  @Test
  void process_attemptHeaderIsForwarded() {
    when(gateway.call(any(), any())).thenReturn(Mono.just(success()));
    final Message<?> retry = input().withHeader("attempt", 2);
    plugin.process(Flux.just(retry), config()).blockLast();
    final ArgumentCaptor<McpCallSpec> spec = ArgumentCaptor.forClass(McpCallSpec.class);
    verify(gateway).call(spec.capture(), any());
    assertThat(spec.getValue().meta()).containsEntry("yukta/attempt", 2);
  }

  @Test
  void process_badArgumentsOrTimeoutFail() {
    StepVerifier.create(plugin.process(Flux.just(input()), config("arguments", "'text'")))
        .verifyError(IllegalArgumentException.class);
    StepVerifier.create(plugin.process(Flux.just(input()), config("timeout", "0")))
        .verifyError(IllegalArgumentException.class);
  }

  @Test
  void process_hardTransportErrorPropagatesByDefault() {
    when(gateway.call(any(), any())).thenReturn(Mono.error(new IllegalStateException("down")));
    StepVerifier.create(plugin.process(Flux.just(input()), config()))
        .verifyError(IllegalStateException.class);
  }

  @Test
  void process_recordsTimerAndDefaultReporterWhenAvailable() {
    final ObjectProvider<PluginMetricsReporter> reporters = mock(ObjectProvider.class);
    when(reporters.getIfAvailable(any()))
        .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get());
    final ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters =
        mock(ObjectProvider.class);
    final var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    when(meters.getIfAvailable()).thenReturn(registry);
    final var metered = new McpToolCallPlugin(gateway, resolver, reporters, meters);
    when(gateway.call(any(), any())).thenReturn(Mono.just(success()));
    metered.process(Flux.just(input()), config()).blockLast();
    assertThat(registry.find("yukta.mcp.call").tag("tool", "weather").timer()).isNotNull();
  }

  @Test
  void validateConfig_coversUrlRules() {
    StepVerifier.create(plugin.validateConfig(config("url", "${payload.url}"))).verifyComplete();
    StepVerifier.create(
            plugin.validateConfig(
                config("timeout", 5, "failureMode", "CONTINUE", "routeByStatus", true)))
        .verifyComplete();
    StepVerifier.create(plugin.validateConfig(config("url", "no-scheme")))
        .verifyError(IllegalArgumentException.class);
    StepVerifier.create(plugin.validateConfig(config("failureMode", "bogus")))
        .verifyError(IllegalArgumentException.class);
  }

  @Test
  void onControlSignal_ignoresOtherCommands() {
    StepVerifier.create(plugin.onControlSignal(DefaultMessage.create(UUID.randomUUID(), "other")))
        .verifyComplete();
    verify(gateway, org.mockito.Mockito.never()).reset();
  }

  @Test
  void edgeCases_coverRemainingBranches() {
    assertThat(plugin.getDefaultTimeout(config("timeout", 0))).isEqualTo(Duration.ofSeconds(65));
    final Map<String, Object> routed = config("failureMode", "CONTINUE", "routeByStatus", true);
    final var ports =
        new WorkflowContext(
            "n",
            List.of(
                new WorkflowContext.WorkflowEdge("n", "a", ""),
                new WorkflowContext.WorkflowEdge("n", "b", "error")),
            List.of());
    StepVerifier.create(plugin.validateInContext(ports, routed)).verifyComplete();
    when(gateway.call(any(), any())).thenReturn(Mono.just(success()));
    StepVerifier.create(
            plugin.process(
                Flux.just(input()),
                config("resultKey", " ", "routeByStatus", true, "failureMode", "CONTINUE")))
        .assertNext(out -> assertThat(out.sourcePort()).isEqualTo("success"))
        .verifyComplete();
    StepVerifier.create(plugin.validateConfig(Map.of("url", "http://x")))
        .verifyError(IllegalArgumentException.class);
    StepVerifier.create(plugin.validateConfig(config("url", "https://x"))).verifyComplete();
    StepVerifier.create(plugin.validateConfig(config("url", 5))).verifyComplete();
  }

  @Test
  void budget_refusesCallsPastMaxCalls_andHardStopEndsTheRun() {
    when(gateway.call(any(), any())).thenReturn(Mono.just(success()));
    final Map<String, Object> soft = config("budget", Map.of("maxCalls", 1, "agent", "a-soft"));
    final Map<String, Object> hard =
        config(
            "budget",
            Map.of("maxCalls", 1, "agent", "a-hard", "hardStop", true),
            "failureMode",
            "CONTINUE");

    for (final Map<String, Object> cfg : List.of(soft, hard)) {
      StepVerifier.create(
              plugin
                  .process(Flux.just(input()), cfg)
                  .contextWrite(Context.of("executionId", "run-budget")))
          .expectNextCount(1)
          .verifyComplete();
    }
    StepVerifier.create(
            plugin
                .process(Flux.just(input()), soft)
                .contextWrite(Context.of("executionId", "run-budget")))
        .verifyErrorSatisfies(
            e ->
                assertThat(e)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("call budget"));
    StepVerifier.create(
            plugin
                .process(Flux.just(input()), hard)
                .contextWrite(Context.of("executionId", "run-budget")))
        .verifyErrorSatisfies(
            e ->
                assertThat(com.infenia.yukta.plugin.exception.RunAbortedException.isAbort(e))
                    .isTrue());
    verify(gateway, org.mockito.Mockito.times(2)).call(any(), any());
  }

  @Test
  void budget_isValidated() {
    StepVerifier.create(plugin.validateConfig(config("budget", Map.of("maxTokens", 5))))
        .expectErrorMessage("budget of MCP_TOOL_CALL supports maxCalls, agent, hardStop")
        .verify();
    StepVerifier.create(plugin.validateConfig(config("budget", Map.of("maxCalls", 3))))
        .verifyComplete();
  }
}
