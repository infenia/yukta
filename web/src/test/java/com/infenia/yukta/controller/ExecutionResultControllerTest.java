// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.exception.GlobalExceptionHandler;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.result.ExecutionResultService;
import com.infenia.yukta.service.result.ExecutionResults;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Tests for the behaviour described by {@link ExecutionResultController}. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.UnitTestShouldIncludeAssert"})
class ExecutionResultControllerTest {

  private static final String URI = "/api/sessions/s1/executions/e1/results";

  private ControlBusGateway controlBus;
  private ExecutionResultService results;
  private WebTestClient client;

  private static WorkflowProgress progress(final TaskStatus status) {
    return new WorkflowProgress("e1", "s1", "wf", status, List.of(), Instant.now(), null, null);
  }

  @BeforeEach
  void setUp() {
    controlBus = mock(ControlBusGateway.class);
    results = new ExecutionResultService(100, Duration.ofMinutes(5), 10);
    client =
        WebTestClient.bindToController(
                new ExecutionResultController(results, new ExecutionAccess(controlBus)))
            .controllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  private String stream(final String uri, final String lastEventId) {
    return String.join(
        "\n",
        client
            .get()
            .uri(uri)
            .accept(org.springframework.http.MediaType.TEXT_EVENT_STREAM)
            .header("Last-Event-ID", lastEventId == null ? "" : lastEventId)
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(String.class)
            .getResponseBody()
            .collectList()
            .block(Duration.ofSeconds(5)));
  }

  @Test
  void finishedExecutionReplaysResultsThenEnds() {
    when(controlBus.getProgress("s1", "e1")).thenReturn(progress(TaskStatus.SUCCESS));
    results.publish("e1", "t1", "the-answer");
    results.nodeEnd("e1", "t1", "SUCCESS");
    results.complete("e1", "SUCCESS");

    final String body = stream(URI, null);

    assertThat(body).contains("the-answer").contains("NODE_END").contains("EXECUTION_END");
  }

  @Test
  void nodeFilterKeepsOnlyThatTerminalAndTheExecutionEnd() {
    when(controlBus.getProgress("s1", "e1")).thenReturn(progress(TaskStatus.SUCCESS));
    results.publish("e1", "t1", "from-one");
    results.publish("e1", "t2", "from-two");
    results.complete("e1", "SUCCESS");

    final String body = stream(URI + "?nodeId=t2", null);

    assertThat(body).contains("from-two").contains("EXECUTION_END").doesNotContain("from-one");
  }

  @Test
  void lastEventIdSkipsWhatTheClientAlreadyHas() {
    when(controlBus.getProgress("s1", "e1")).thenReturn(progress(TaskStatus.SUCCESS));
    results.publish("e1", "t1", "first");
    results.publish("e1", "t1", "second");
    results.complete("e1", "SUCCESS");

    final String body = stream(URI, "0");

    assertThat(body).contains("second").doesNotContain("first");
  }

  @Test
  void evictedFinishedExecutionStreamsNothingInsteadOfHanging() {
    when(controlBus.getProgress("s1", "e1")).thenReturn(progress(TaskStatus.SUCCESS));

    assertThat(stream(URI, null)).doesNotContain("result");
  }

  @Test
  void runningExecutionStreamsWhatIsRecordedAndEndsWithTheExecution() {
    when(controlBus.getProgress("s1", "e1")).thenReturn(progress(TaskStatus.RUNNING));
    results.publish("e1", "t1", "live-answer");
    results.complete("e1", "SUCCESS");

    assertThat(stream(URI, null)).contains("live-answer").contains("EXECUTION_END");
  }

  @Test
  void jsonReadReturnsWhatIsRecordedAndCursor() {
    when(controlBus.getProgress("s1", "e1")).thenReturn(progress(TaskStatus.RUNNING));
    results.publish("e1", "t1", "partial");

    final ExecutionResults body =
        client
            .get()
            .uri(URI)
            .accept(org.springframework.http.MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(ExecutionResults.class)
            .returnResult()
            .getResponseBody();

    assertThat(body.complete()).isFalse();
    assertThat(body.events()).hasSize(1);
    assertThat(body.nextCursor()).isEqualTo(1);
  }

  @Test
  void unknownOrForeignExecutionIsNotFound() {
    when(controlBus.getProgress("s1", "e1")).thenReturn(null);

    client.get().uri(URI).exchange().expectStatus().isNotFound();
  }
}
