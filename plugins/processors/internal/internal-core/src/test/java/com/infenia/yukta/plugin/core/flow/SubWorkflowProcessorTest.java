// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.gateway.SubWorkflowGateway;
import com.infenia.yukta.plugin.util.VariableResolver;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

@NoArgsConstructor
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.CommentRequired",
  "PMD.TooManyMethods",
  "PMD.TooManyStaticImports",
  "unchecked"
})
class SubWorkflowProcessorTest {

  private final SubWorkflowGateway runner = mock(SubWorkflowGateway.class);
  private final ObjectProvider<SubWorkflowGateway> provider = mock(ObjectProvider.class);
  private final ObjectProvider<VariableResolver> variables = mock(ObjectProvider.class);
  private final SubWorkflowProcessor processor = new SubWorkflowProcessor(provider, variables);

  private Flux<Message<?>> run(final Object payload, final Map<String, Object> config) {
    return processor
        .process(Flux.just(DefaultMessage.create(UUID.randomUUID(), payload)), config)
        .contextWrite(
            Context.of(
                "sessionId", "s1", "executionId", "e1", "payload", Map.of("yukta.depth", 2)));
  }

  private void available() {
    when(provider.getIfAvailable()).thenReturn(runner);
  }

  @Test
  void runsTheChildWithTheMessagePayload_andEmitsItsResults() {
    available();
    when(runner.run(anyString(), anyString(), anyMap(), anyString(), anyInt()))
        .thenReturn(Mono.just(new SubWorkflowGateway.Result("child-1", List.of("a", "b"))));

    StepVerifier.create(run(Map.of("k", "v"), Map.of("workflowId", "child")))
        .assertNext(
            out ->
                assertThat(out.payload())
                    .isEqualTo(
                        Map.of(
                            "executionId",
                            "child-1",
                            "results",
                            List.of("a", "b"),
                            "status",
                            "SUCCESS")))
        .verifyComplete();

    final ArgumentCaptor<Map<String, Object>> start = ArgumentCaptor.forClass(Map.class);
    verify(runner).run(eq("s1"), eq("child"), start.capture(), eq("e1"), eq(3));
    assertThat(start.getValue()).isEqualTo(Map.of("k", "v"));
  }

  @Test
  void nonMapPayloadBecomesInput_andConfiguredPayloadWins() {
    available();
    when(runner.run(anyString(), anyString(), anyMap(), anyString(), anyInt()))
        .thenReturn(Mono.just(new SubWorkflowGateway.Result("c", List.of())));

    StepVerifier.create(run("text", Map.of("workflowId", "child")))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(run("text", Map.of("workflowId", "child", "payload", Map.of("x", 1))))
        .expectNextCount(1)
        .verifyComplete();

    final ArgumentCaptor<Map<String, Object>> start = ArgumentCaptor.forClass(Map.class);
    verify(runner, org.mockito.Mockito.times(2))
        .run(anyString(), anyString(), start.capture(), anyString(), anyInt());
    assertThat(start.getAllValues()).containsExactly(Map.of("input", "text"), Map.of("x", 1));
  }

  @Test
  void configuredPayloadValues_areResolved_andValuesThatResolveToNothingAreLeftOut() {
    available();
    final VariableResolver resolver = mock(VariableResolver.class);
    when(variables.getIfAvailable()).thenReturn(resolver);
    when(resolver.resolve(eq("${start.project}"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(Mono.just("/work"));
    when(resolver.resolve(eq("${start.none}"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(Mono.empty());
    when(runner.run(anyString(), anyString(), anyMap(), anyString(), anyInt()))
        .thenReturn(Mono.just(new SubWorkflowGateway.Result("c", List.of())));
    final Map<String, Object> payload =
        Map.of("project", "${start.project}", "missing", "${start.none}");

    StepVerifier.create(run("x", Map.of("workflowId", "child", "payload", payload)))
        .expectNextCount(1)
        .verifyComplete();

    final ArgumentCaptor<Map<String, Object>> start = ArgumentCaptor.forClass(Map.class);
    verify(runner).run(anyString(), anyString(), start.capture(), anyString(), anyInt());
    assertThat(start.getValue()).isEqualTo(Map.of("project", "/work"));
  }

  @Test
  void depthStartsAtOne_whenTheRunHasNone() {
    available();
    when(runner.run(anyString(), anyString(), anyMap(), anyString(), anyInt()))
        .thenReturn(Mono.just(new SubWorkflowGateway.Result("c", List.of())));

    StepVerifier.create(
            processor
                .process(
                    Flux.just(DefaultMessage.create(UUID.randomUUID(), "x")),
                    Map.of("workflowId", "child"))
                .contextWrite(Context.empty()))
        .expectNextCount(1)
        .verifyComplete();

    verify(runner).run(eq("unknown"), eq("child"), anyMap(), eq("unknown"), eq(1));
  }

  @Test
  void routeByOutcome_stillFailsTheNode_whenItsOwnWaitTimesOut() {
    available();
    when(runner.run(anyString(), anyString(), anyMap(), anyString(), anyInt()))
        .thenReturn(Mono.never());

    StepVerifier.withVirtualTime(
            () ->
                run(
                    "x",
                    Map.of("workflowId", "child", "timeoutSeconds", 2, "routeByOutcome", true)))
        .thenAwait(Duration.ofSeconds(3))
        .verifyError(java.util.concurrent.TimeoutException.class);
  }

  @Test
  void slowChild_timesOut() {
    available();
    when(runner.run(anyString(), anyString(), anyMap(), anyString(), anyInt()))
        .thenReturn(Mono.never());

    StepVerifier.withVirtualTime(() -> run("x", Map.of("workflowId", "child", "timeoutSeconds", 2)))
        .thenAwait(Duration.ofSeconds(3))
        .verifyError(java.util.concurrent.TimeoutException.class);
  }

  private void childEnds(final String outcome) {
    when(runner.run(anyString(), anyString(), anyMap(), anyString(), anyInt()))
        .thenReturn(
            Mono.just(new SubWorkflowGateway.Result("c", List.of(), "SUCCESS", outcome, "why")));
  }

  @Test
  void routeByOutcome_sendsTheResultOutOnThePortOfTheReachedOutcome() {
    available();
    final Map<String, Object> config = Map.of("workflowId", "child", "routeByOutcome", true);
    final Map<String, String> ports =
        Map.of("SUCCESS", "success", "RETRY", "retry", "ESCALATE", "escalate", "FAILED", "failure");

    ports.forEach(
        (outcome, port) -> {
          childEnds(outcome);
          StepVerifier.create(run("x", config))
              .assertNext(
                  out -> {
                    assertThat(out.sourcePort()).isEqualTo(port);
                    assertThat((Map<String, Object>) out.payload())
                        .containsEntry("outcome", outcome)
                        .containsEntry("reason", "why")
                        .containsEntry("status", "SUCCESS");
                  })
              .verifyComplete();
        });
    childEnds(null);
    StepVerifier.create(run("x", config))
        .assertNext(
            out -> {
              assertThat(out.sourcePort()).isEqualTo("success");
              assertThat((Map<String, Object>) out.payload()).doesNotContainKeys("outcome");
            })
        .verifyComplete();
  }

  @Test
  void routeByOutcome_turnsFailedChildIntoMessageOnTheFailurePort() {
    available();
    when(runner.run(anyString(), anyString(), anyMap(), anyString(), anyInt()))
        .thenReturn(
            Mono.error(
                new SubWorkflowGateway.ChildFailed(
                    "c9", "TIMED_OUT", new IllegalStateException("too slow"))));

    StepVerifier.create(run("x", Map.of("workflowId", "child", "routeByOutcome", "true")))
        .assertNext(
            out -> {
              assertThat(out.sourcePort()).isEqualTo("failure");
              assertThat((Map<String, Object>) out.payload())
                  .containsEntry("executionId", "c9")
                  .containsEntry("status", "TIMED_OUT")
                  .containsEntry("reason", "too slow");
            })
        .verifyComplete();
  }

  @Test
  void withoutRouteByOutcome_failedChildFailsTheNode_withTheChildsError() {
    available();
    when(runner.run(anyString(), anyString(), anyMap(), anyString(), anyInt()))
        .thenReturn(
            Mono.error(
                new SubWorkflowGateway.ChildFailed(
                    "c9", "FAILURE", new IllegalStateException("child broke"))));

    StepVerifier.create(run("x", Map.of("workflowId", "child")))
        .verifyErrorSatisfies(
            e ->
                assertThat(e)
                    .isInstanceOf(SubWorkflowGateway.ChildFailed.class)
                    .hasMessage("child broke"));
  }

  @Test
  void portsAreDeclaredOnlyWhenRoutingByOutcome() {
    assertThat(processor.getOutputPorts(Map.of("workflowId", "c", "routeByOutcome", true)))
        .containsExactly("success", "failure", "retry", "escalate");
    assertThat(processor.getOutputPorts(Map.of("workflowId", "c")))
        .isEqualTo(processor.getOutputPorts());
  }

  @Test
  void withoutAnEngineGateway_theNodeFails() {
    when(provider.getIfAvailable()).thenReturn(null);

    StepVerifier.create(run("x", Map.of("workflowId", "child")))
        .verifyError(IllegalStateException.class);
  }

  @Test
  void concurrency_runsThatManyChildrenAtOnce_andKeepsTheOrderOfTheMessages() {
    available();
    final AtomicInteger running = new AtomicInteger();
    final AtomicInteger most = new AtomicInteger();
    when(runner.run(anyString(), anyString(), anyMap(), anyString(), anyInt()))
        .thenAnswer(
            call -> {
              final Object id = ((Map<String, Object>) call.getArgument(2)).get("n");
              return Mono.defer(
                  () -> {
                    most.accumulateAndGet(running.incrementAndGet(), Math::max);
                    // the first message is the slowest, so a wrong order would show
                    return Mono.delay(Duration.ofMillis("1".equals(id) ? 150 : 50))
                        .map(
                            _ -> {
                              // done before the result goes on: the next child may start right
                              // after
                              running.decrementAndGet();
                              return new SubWorkflowGateway.Result("child-" + id, List.of());
                            });
                  });
            });
    final Flux<Message<?>> input =
        Flux.range(1, 4)
            .map(i -> (Message<?>) DefaultMessage.create(UUID.randomUUID(), Map.of("n", "" + i)));

    StepVerifier.create(
            processor
                .process(input, Map.of("workflowId", "child", "concurrency", 2))
                .contextWrite(Context.of("sessionId", "s1", "executionId", "e1")))
        .assertNext(out -> assertThat(out.payload()).isInstanceOf(Map.class))
        .expectNextCount(3)
        .verifyComplete();

    assertThat(most).hasValue(2);
  }

  @Test
  void concurrencyOfOne_isTheDefault_andRunsOneChildAtATime() {
    available();
    final AtomicInteger running = new AtomicInteger();
    final AtomicInteger most = new AtomicInteger();
    when(runner.run(anyString(), anyString(), anyMap(), anyString(), anyInt()))
        .thenAnswer(
            call ->
                Mono.defer(
                    () -> {
                      most.accumulateAndGet(running.incrementAndGet(), Math::max);
                      return Mono.delay(Duration.ofMillis(20))
                          .map(
                              _ -> {
                                running.decrementAndGet();
                                return new SubWorkflowGateway.Result("c", List.of());
                              });
                    }));
    final Flux<Message<?>> input =
        Flux.range(1, 3).map(i -> (Message<?>) DefaultMessage.create(UUID.randomUUID(), "x"));

    StepVerifier.create(
            processor
                .process(input, Map.of("workflowId", "child"))
                .contextWrite(Context.of("sessionId", "s1", "executionId", "e1")))
        .expectNextCount(3)
        .verifyComplete();

    assertThat(most).hasValue(1);
  }

  @Test
  void concurrencyIsValidated() {
    StepVerifier.create(processor.validateConfig(Map.of("workflowId", "c", "concurrency", 16)))
        .verifyComplete();
    for (final Object bad : List.of(0, 17, "many")) {
      assertThatThrownBy(
              () -> processor.validateConfig(Map.of("workflowId", "c", "concurrency", bad)).block())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("concurrency");
    }
    assertThat(processor.getConfigSchema()).extracting("name").contains("concurrency");
  }

  @Test
  void configIsValidated_andMetadataIsDescribed() {
    StepVerifier.create(processor.validateConfig(Map.of("workflowId", "child"))).verifyComplete();
    StepVerifier.create(
            processor.validateConfig(Map.of("workflowId", "child", "payload", Map.of("a", 1))))
        .verifyComplete();
    assertThatThrownBy(
            () ->
                processor
                    .validateConfig(Map.of("workflowId", "c", "timeoutSeconds", "soon"))
                    .block())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> processor.validateConfig(Map.of("workflowId", " ")).block())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> processor.validateConfig(Map.of()).block())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> processor.validateConfig(Map.of("workflowId", "c", "timeoutSeconds", 0)).block())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> processor.validateConfig(Map.of("workflowId", "c", "payload", "text")).block())
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(processor.getType()).isEqualTo("SUB_WORKFLOW");
    assertThat(processor.getDefaultTimeout(Map.of("workflowId", "c")))
        .isEqualTo(Duration.ofSeconds(310));
    assertThat(processor.describe().description()).contains("workflow");
    assertThat(processor.getConfigSchema()).extracting("name").contains("workflowId");
  }
}
