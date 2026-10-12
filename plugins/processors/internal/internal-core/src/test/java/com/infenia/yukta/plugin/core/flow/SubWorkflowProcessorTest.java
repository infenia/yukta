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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
  "PMD.CommentRequired",
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyMethods",
  "unchecked"
})
class SubWorkflowProcessorTest {

  private final SubWorkflowGateway runner = mock(SubWorkflowGateway.class);
  private final ObjectProvider<SubWorkflowGateway> provider = mock(ObjectProvider.class);
  private final SubWorkflowProcessor processor = new SubWorkflowProcessor(provider);

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
                    .isEqualTo(Map.of("executionId", "child-1", "results", List.of("a", "b"))))
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
  void slowChild_timesOut() {
    available();
    when(runner.run(anyString(), anyString(), anyMap(), anyString(), anyInt()))
        .thenReturn(Mono.never());

    StepVerifier.withVirtualTime(() -> run("x", Map.of("workflowId", "child", "timeoutSeconds", 2)))
        .thenAwait(Duration.ofSeconds(3))
        .verifyError(java.util.concurrent.TimeoutException.class);
  }

  @Test
  void withoutAnEngineGateway_theNodeFails() {
    when(provider.getIfAvailable()).thenReturn(null);

    StepVerifier.create(run("x", Map.of("workflowId", "child")))
        .verifyError(IllegalStateException.class);
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
