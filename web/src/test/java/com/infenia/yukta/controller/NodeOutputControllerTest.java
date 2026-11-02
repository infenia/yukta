// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.exception.GlobalExceptionHandler;
import com.infenia.yukta.logging.api.LogStream;
import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.logging.api.PluginLogStore;
import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.plugin.core.LogLevel;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

/** Tests for the REST shape of {@link NodeOutputController}. */
@NoArgsConstructor
@SuppressWarnings({"PMD.UnitTestShouldIncludeAssert", "PMD.AvoidDuplicateLiterals"})
class NodeOutputControllerTest {

  /** The client. */
  private WebTestClient client;

  private static PluginLogEntry line(final String node, final LogStream stream, final String text) {
    return new PluginLogEntry(
        "e1",
        "s1",
        node,
        "PROCESS_EXECUTOR",
        stream,
        text,
        LogLevel.INFO,
        Instant.now(),
        null,
        Map.of());
  }

  @BeforeEach
  void setUp() {
    final ControlBusGateway controlBus = mock(ControlBusGateway.class);
    final PluginLogStore logStore = mock(PluginLogStore.class);
    final TaskProgress task = mock(TaskProgress.class);
    when(task.nodeId()).thenReturn("run");
    when(controlBus.getCurrentProgress("e1"))
        .thenReturn(
            new WorkflowProgress(
                "e1", "s1", "wf", TaskStatus.SUCCESS, List.of(task), Instant.now(), null, null));
    when(logStore.readExecution("e1"))
        .thenReturn(
            Flux.just(
                line("run", LogStream.STDOUT, "one"),
                line("run", LogStream.STDERR, "boom"),
                line("run", LogStream.STDOUT, "two"),
                line("other", LogStream.STDOUT, "not mine")));
    client =
        WebTestClient.bindToController(
                new NodeOutputController(logStore, new ExecutionAccess(controlBus)))
            .controllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void tail_ofOneNode_leavesOtherNodesOut() {
    client
        .get()
        .uri("/api/executions/e1/nodes/run/output?tail=2")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.totalLines")
        .isEqualTo(3)
        .jsonPath("$.data.lines.length()")
        .isEqualTo(2)
        .jsonPath("$.data.lines[1].text")
        .isEqualTo("two")
        .jsonPath("$.data.lines[1].number")
        .isEqualTo(3);
  }

  @Test
  void stream_stderr_picksOnlyStderr() {
    client
        .get()
        .uri("/api/executions/e1/nodes/run/output?stream=stderr")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.totalLines")
        .isEqualTo(1)
        .jsonPath("$.data.lines[0].text")
        .isEqualTo("boom");
  }

  @Test
  void stream_stdout_picksOnlyStdout() {
    client
        .get()
        .uri("/api/executions/e1/nodes/run/output?stream=STDOUT&grep=tw")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.matchedLines")
        .isEqualTo(1)
        .jsonPath("$.data.lines[0].number")
        .isEqualTo(2);
  }

  @Test
  void badStreamOrArgument_is400() {
    client
        .get()
        .uri("/api/executions/e1/nodes/run/output?stream=x")
        .exchange()
        .expectStatus()
        .isBadRequest();
    client
        .get()
        .uri("/api/executions/e1/nodes/run/output?tail=1&head=1")
        .exchange()
        .expectStatus()
        .isBadRequest();
    client
        .get()
        .uri("/api/executions/e1/nodes/run/output?grep=(")
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void unknownNodeOrExecution_is404() {
    client.get().uri("/api/executions/e1/nodes/nope/output").exchange().expectStatus().isNotFound();
    client.get().uri("/api/executions/zz/nodes/run/output").exchange().expectStatus().isNotFound();
  }
}
