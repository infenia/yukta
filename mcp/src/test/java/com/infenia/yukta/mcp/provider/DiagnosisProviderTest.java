// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.logging.api.LogStream;
import com.infenia.yukta.mcp.dto.ExecutionLogs;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.plugin.core.LogLevel;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.diagnosis.FailureDigest;
import com.infenia.yukta.service.diagnosis.FailureDigestService;
import com.infenia.yukta.service.diagnosis.LogSearchMode;
import com.infenia.yukta.service.diagnosis.RunLogQuery;
import com.infenia.yukta.service.diagnosis.RunLogSearch;
import com.infenia.yukta.service.diagnosis.RunLogSearcher;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Tests for DiagnosisProvider. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "unchecked"})
class DiagnosisProviderTest {

  private static final String SESSION = "s";
  private static final String RUN = "e";

  private ControlBusGateway controlBus;
  private RunLogSearcher searcher;
  private FailureDigestService digests;
  private DiagnosisProvider provider;
  private WorkflowProgress progress;

  @BeforeEach
  void setUp() {
    controlBus = mock(ControlBusGateway.class);
    searcher = mock(RunLogSearcher.class);
    digests = mock(FailureDigestService.class);
    progress = mock(WorkflowProgress.class);
    provider = new DiagnosisProvider(controlBus, searcher, digests, List.of("tok_\\w+"), 2000);
    when(controlBus.getProgress(SESSION, RUN)).thenReturn(progress);
  }

  private static RunLogSearch answer() {
    return new RunLogSearch(
        RUN, LogSearchMode.COUNT, 1, 0, 0, List.of(), 0, List.of(), false, null, null);
  }

  @Test
  void searchLogs_buildsTheQueryAndRedactsLines() {
    final ArgumentCaptor<RunLogQuery> query = ArgumentCaptor.forClass(RunLogQuery.class);
    final ArgumentCaptor<UnaryOperator<String>> clean =
        ArgumentCaptor.forClass(UnaryOperator.class);
    when(searcher.search(query.capture(), clean.capture())).thenReturn(Mono.just(answer()));

    StepVerifier.create(
            provider.searchLogs(
                SESSION,
                RUN,
                true,
                "build, ,test",
                "STDERR",
                "warn",
                "FAIL",
                2,
                LogSearchMode.LINES,
                10,
                "e:3"))
        .assertNext(result -> {
          assertThat(result.search()).isEqualTo(answer());
          assertThat(result.notice()).isEqualTo(ExecutionLogs.UNTRUSTED_NOTICE);
        })
        .verifyComplete();

    final RunLogQuery sent = query.getValue();
    assertThat(sent.includeChildren()).isTrue();
    assertThat(sent.nodes()).isEqualTo(Set.of("build", "test"));
    assertThat(sent.stream()).isEqualTo(LogStream.STDERR);
    assertThat(sent.level()).isEqualTo(LogLevel.WARN);
    assertThat(sent.grep()).isEqualTo("FAIL");
    assertThat(sent.context()).isEqualTo(2);
    assertThat(sent.mode()).isEqualTo(LogSearchMode.LINES);
    assertThat(sent.maxMatches()).isEqualTo(10);
    assertThat(sent.cursor()).isEqualTo("e:3");
    assertThat(clean.getValue().apply("key tok_abc")).doesNotContain("tok_abc");
  }

  @Test
  void searchLogs_defaultsWhenNothingIsGiven() {
    final ArgumentCaptor<RunLogQuery> query = ArgumentCaptor.forClass(RunLogQuery.class);
    when(searcher.search(query.capture(), any())).thenReturn(Mono.just(answer()));

    StepVerifier.create(
            provider.searchLogs(SESSION, RUN, null, null, null, null, null, null, null, null, null))
        .expectNextCount(1)
        .verifyComplete();

    final RunLogQuery sent = query.getValue();
    assertThat(sent.includeChildren()).isFalse();
    assertThat(sent.nodes()).isEmpty();
    assertThat(sent.stream()).isNull();
    assertThat(sent.level()).isNull();
    assertThat(sent.mode()).isEqualTo(LogSearchMode.COUNT);
    assertThat(sent.maxMatches()).isEqualTo(RunLogQuery.DEFAULT_MATCHES);
  }

  @Test
  void searchLogs_acceptsAllAndStdoutAndBlankLevel() {
    final ArgumentCaptor<RunLogQuery> query = ArgumentCaptor.forClass(RunLogQuery.class);
    when(searcher.search(query.capture(), any())).thenReturn(Mono.just(answer()));

    provider
        .searchLogs(SESSION, RUN, false, null, "all", " ", null, null, null, null, null)
        .block();
    assertThat(query.getValue().stream()).isNull();
    assertThat(query.getValue().level()).isNull();

    provider
        .searchLogs(SESSION, RUN, false, null, "stdout", null, null, null, null, null, null)
        .block();
    assertThat(query.getValue().stream()).isEqualTo(LogStream.STDOUT);
  }

  @Test
  void searchLogs_refusesBadArgumentsAndForeignRuns() {
    StepVerifier.create(
            provider.searchLogs(
                SESSION, RUN, null, null, "both", null, null, null, null, null, null))
        .expectErrorMessage("stream must be stdout, stderr or all")
        .verify();
    StepVerifier.create(
            provider.searchLogs(
                SESSION, RUN, null, null, null, "loud", null, null, null, null, null))
        .expectErrorMessage("level must be ERROR, WARN, INFO or DEBUG")
        .verify();
    StepVerifier.create(
            provider.searchLogs(
                SESSION, "other", null, null, null, null, null, null, null, null, null))
        .expectErrorSatisfies(e -> assertThat(e).hasMessageStartingWith("Execution not found"))
        .verify();
  }

  @Test
  void getFailureDigest_digestsTheOwnedRun() {
    final FailureDigest digest =
        new FailureDigest(RUN, "wf", "FAILURE", true, 1, List.of(), List.of(), 0, List.of());
    final ArgumentCaptor<UnaryOperator<String>> clean =
        ArgumentCaptor.forClass(UnaryOperator.class);
    when(digests.digest(org.mockito.ArgumentMatchers.eq(progress), clean.capture()))
        .thenReturn(Mono.just(digest));

    StepVerifier.create(provider.getFailureDigest(SESSION, RUN))
        .assertNext(result -> {
          assertThat(result.digest()).isEqualTo(digest);
          assertThat(result.notice()).isEqualTo(ExecutionLogs.UNTRUSTED_NOTICE);
        })
        .verifyComplete();
    assertThat(clean.getValue().apply("tok_secret")).doesNotContain("tok_secret");
    verify(controlBus).getProgress(SESSION, RUN);
  }

  @Test
  void getFailureDigest_refusesForeignRun() {
    StepVerifier.create(provider.getFailureDigest(SESSION, "other"))
        .expectErrorSatisfies(e -> assertThat(e).hasMessageStartingWith("Execution not found"))
        .verify();
  }
}
