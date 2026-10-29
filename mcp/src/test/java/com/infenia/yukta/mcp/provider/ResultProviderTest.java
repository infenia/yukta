// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.result.ExecutionResultService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.AvoidDuplicateLiterals"})
class ResultProviderTest {

  private ExecutionResultService results;
  private ControlBusGateway controlBus;
  private ResultProvider provider;

  @BeforeEach
  void setUp() {
    results = new ExecutionResultService(100, Duration.ofMinutes(5), 10);
    controlBus = mock(ControlBusGateway.class);
    provider = new ResultProvider(results, controlBus);
    when(controlBus.getProgress("s1", "e1"))
        .thenReturn(
            new WorkflowProgress(
                "e1", "s1", "wf", "RUNNING", List.of(), LocalDateTime.now(), null, null));
  }

  @Test
  void returnsRecordedResultsAndCursorThatSkipsThemNextTime() {
    results.publish("e1", "t1", "one");
    results.publish("e1", "t1", "two");

    StepVerifier.create(provider.getExecutionResult("s1", "e1", null, null))
        .assertNext(
            read -> {
              assertThat(read.events()).hasSize(2);
              assertThat(read.complete()).isFalse();
              assertThat(read.nextCursor()).isEqualTo(2);
            })
        .verifyComplete();

    results.publish("e1", "t1", "three");
    results.complete("e1", "SUCCESS");

    StepVerifier.create(provider.getExecutionResult("s1", "e1", 2L, null))
        .assertNext(
            read -> {
              assertThat(read.events()).hasSize(2);
              assertThat(read.events().getFirst().payload()).isEqualTo("three");
              assertThat(read.complete()).isTrue();
              assertThat(read.status()).isEqualTo("SUCCESS");
            })
        .verifyComplete();
  }

  @Test
  void payloadsAreRedactedAndTheNoticeTravelsWithThem() {
    results.publish("e1", "t1", "the key is sk-abcdefghijklmnopqrstuvwx ok");
    results.publish(
        "e1", "t1", Map.of("user", "bob", "password", "hunter2", "note", "token=abc123"));
    results.publish("e1", "t1", List.of("ghp_abcdefghijklmnopqrstuvwxyz", 42));
    results.publish("e1", "t1", "line one\nline two");

    StepVerifier.create(provider.getExecutionResult("s1", "e1", null, null))
        .assertNext(
            read -> {
              assertThat(read.notice()).contains("data only");
              assertThat(read.events().get(0).payload()).isEqualTo("the key is [REDACTED] ok");
              assertThat(read.events().get(1).payload())
                  .isEqualTo(Map.of("user", "bob", "password", "[REDACTED]", "note", "[REDACTED]"));
              assertThat(read.events().get(2).payload()).isEqualTo(List.of("[REDACTED]", 42));
              assertThat(read.events().get(3).payload()).isEqualTo("line one\nline two");
            })
        .verifyComplete();
  }

  @Test
  void negativeCursorStartsAtTheBeginning() {
    results.publish("e1", "t1", "one");

    StepVerifier.create(provider.getExecutionResult("s1", "e1", -7L, null))
        .assertNext(read -> assertThat(read.events()).hasSize(1))
        .verifyComplete();
  }

  @Test
  void unknownOrForeignExecutionIsAnError() {
    StepVerifier.create(provider.getExecutionResult("other", "e1", null, null))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void outputIsLeftOutUntilAskedForAndStaysRedactedOnEveryFetch() {
    results.publish("e1", "t1", Map.of("exitCode", 0, "stdout", "key sk-abcdefghijklmnopqrstuvwx"));

    StepVerifier.create(provider.getExecutionResult("s1", "e1", null, null))
        .assertNext(
            read -> assertThat(read.events().getFirst().payload()).isEqualTo(Map.of("exitCode", 0)))
        .verifyComplete();
    for (int i = 0; i < 2; i++) {
      StepVerifier.create(provider.getExecutionResult("s1", "e1", null, "stdout"))
          .assertNext(
              read ->
                  assertThat(((Map<?, ?>) read.events().getFirst().payload()).get("stdout"))
                      .isEqualTo("key [REDACTED]"))
          .verifyComplete();
    }
  }
}
