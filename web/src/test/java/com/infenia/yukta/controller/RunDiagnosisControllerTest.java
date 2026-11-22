// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.exception.GlobalExceptionHandler;
import com.infenia.yukta.logging.api.LogStream;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.plugin.core.LogLevel;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.diagnosis.FailureDigest;
import com.infenia.yukta.service.diagnosis.FailureDigestService;
import com.infenia.yukta.service.diagnosis.LogSearchMode;
import com.infenia.yukta.service.diagnosis.RunLogQuery;
import com.infenia.yukta.service.diagnosis.RunLogSearch;
import com.infenia.yukta.service.diagnosis.RunLogSearcher;
import com.infenia.yukta.testing.Fixtures;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Tests for the REST shape of {@link RunDiagnosisController}. */
@NoArgsConstructor
@SuppressWarnings({"PMD.UnitTestShouldIncludeAssert", "PMD.TooManyStaticImports"})
class RunDiagnosisControllerTest {

  private WebTestClient client;
  private FailureDigestService digests;
  private RunLogSearcher searcher;
  private WorkflowProgress progress;

  @BeforeEach
  void setUp() {
    final ControlBusGateway controlBus = mock(ControlBusGateway.class);
    digests = mock(FailureDigestService.class);
    searcher = mock(RunLogSearcher.class);
    progress =
        Fixtures.workflowProgress(
            "e1", "s1", "wf", TaskStatus.FAILURE, List.of(), Instant.now(), null, null);
    when(controlBus.getCurrentProgress("e1")).thenReturn(progress);
    client =
        WebTestClient.bindToController(
                new RunDiagnosisController(new ExecutionAccess(controlBus), digests, searcher))
            .controllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void digest_isReturnedInTheEnvelope() {
    when(digests.digest(eq(progress), any()))
        .thenReturn(
            Mono.just(
                new FailureDigest(
                    "e1", "wf", "FAILURE", true, 1, List.of(), List.of(), 0, List.of())));

    client
        .get()
        .uri("/api/executions/e1/digest")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.status")
        .isEqualTo("FAILURE")
        .jsonPath("$.data.complete")
        .isEqualTo(true);
  }

  @Test
  void search_passesEveryArgument() {
    final ArgumentCaptor<RunLogQuery> query = ArgumentCaptor.forClass(RunLogQuery.class);
    when(searcher.search(query.capture(), any()))
        .thenReturn(
            Mono.just(
                new RunLogSearch(
                    "e1",
                    LogSearchMode.LINES,
                    2,
                    10,
                    1,
                    List.of(),
                    0,
                    List.of(),
                    true,
                    "e1:4",
                    "More lines")));

    client
        .get()
        .uri(
            "/api/executions/e1/logs/search?includeChildren=true&nodes=a,b&stream=stderr"
                + "&level=error&grep=FAIL&context=2&mode=LINES&maxMatches=7&cursor=e1:1")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.nextCursor")
        .isEqualTo("e1:4");

    final RunLogQuery sent = query.getValue();
    assertThat(sent.includeChildren()).isTrue();
    assertThat(sent.nodes()).isEqualTo(Set.of("a", "b"));
    assertThat(sent.stream()).isEqualTo(LogStream.STDERR);
    assertThat(sent.level()).isEqualTo(LogLevel.ERROR);
    assertThat(sent.grep()).isEqualTo("FAIL");
    assertThat(sent.context()).isEqualTo(2);
    assertThat(sent.mode()).isEqualTo(LogSearchMode.LINES);
    assertThat(sent.maxMatches()).isEqualTo(7);
    assertThat(sent.cursor()).isEqualTo("e1:1");
  }

  @Test
  void badArgumentsAndUnknownRuns_areRefused() {
    client
        .get()
        .uri("/api/executions/e1/logs/search?stream=both")
        .exchange()
        .expectStatus()
        .isBadRequest();
    client.get().uri("/api/executions/nope/digest").exchange().expectStatus().isNotFound();
    client.get().uri("/api/executions/nope/logs/search").exchange().expectStatus().isNotFound();
  }
}
