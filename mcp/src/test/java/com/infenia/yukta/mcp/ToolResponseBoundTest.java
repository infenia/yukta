// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.logging.api.LogStream;
import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.logging.api.PluginLogStoreConfig;
import com.infenia.yukta.logging.impl.memory.InMemoryPluginLogStore;
import com.infenia.yukta.mcp.provider.DiagnosisProvider;
import com.infenia.yukta.mcp.provider.InputProvider;
import com.infenia.yukta.mcp.provider.LogProvider;
import com.infenia.yukta.mcp.provider.MetricsProvider;
import com.infenia.yukta.mcp.provider.NotificationProvider;
import com.infenia.yukta.mcp.provider.PluginInfoProvider;
import com.infenia.yukta.mcp.provider.ResultProvider;
import com.infenia.yukta.mcp.provider.SessionInfoProvider;
import com.infenia.yukta.mcp.provider.SupervisorProvider;
import com.infenia.yukta.mcp.provider.SystemHealthProvider;
import com.infenia.yukta.mcp.provider.WorkflowControlProvider;
import com.infenia.yukta.mcp.provider.WorkflowExecutionProvider;
import com.infenia.yukta.mcp.provider.WorkflowVersionProvider;
import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.plugin.core.LogLevel;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.diagnosis.FailureDigestService;
import com.infenia.yukta.service.diagnosis.GoFailureExtractor;
import com.infenia.yukta.service.diagnosis.GradleFailureExtractor;
import com.infenia.yukta.service.diagnosis.JsFailureExtractor;
import com.infenia.yukta.service.diagnosis.LogSearchMode;
import com.infenia.yukta.service.diagnosis.ReportLineExtractor;
import com.infenia.yukta.service.diagnosis.RunLogSearcher;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.result.ExecutionResultService;
import com.infenia.yukta.testing.Fixtures;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every tool that reads a run answers in a bounded size with its default arguments, however big the
 * run: 10,000 log lines of 1,500 characters (about 15 MB), 50 results of 100 KB each, 2,000 runs in
 * the session and a 400-node graph. The sizes are the JSON an agent would get.
 */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.CouplingBetweenObjects",
  "PMD.ExcessiveImports"
})
class ToolResponseBoundTest {

  /** What a default read may cost an agent. */
  private static final int READ_BOUND = 20 * 1024;

  /** What the digest may cost: it is read first, on every failure. */
  private static final int DIGEST_BOUND = 6 * 1024;

  private static final String SESSION = "s";
  private static final String RUN = "e";
  private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

  private static AppMcpTools tools;

  @BeforeAll
  static void bigRun() {
    final InMemoryPluginLogStore store = new InMemoryPluginLogStore(new PluginLogStoreConfig());
    final String noise = "x".repeat(1_500);
    for (int i = 0; i < 10_000; i++) {
      final boolean error = i % 500 == 0;
      store
          .write(
              new PluginLogEntry(
                  RUN,
                  SESSION,
                  i % 2 == 0 ? "build" : "lint",
                  "PROCESS_EXECUTOR",
                  LogStream.STDOUT,
                  error ? "com.x.T" + i + "Test > case() FAILED " + noise : noise + i,
                  error ? LogLevel.ERROR : LogLevel.INFO,
                  Instant.EPOCH,
                  null,
                  null))
          .block();
    }
    final List<TaskProgress> tasks = new ArrayList<>();
    IntStream.range(0, 400)
        .forEach(i ->
            tasks.add(
                new TaskProgress(
                    "node-" + i,
                    "PROCESS_EXECUTOR",
                    TaskStatus.SUCCESS,
                    Instant.EPOCH,
                    null,
                    null)));
    tasks.add(
        new TaskProgress(
            "build",
            "PROCESS_EXECUTOR",
            TaskStatus.FAILURE,
            Instant.EPOCH,
            null,
            Map.of("error", "exit 1 " + noise.repeat(5))));
    final WorkflowProgress progress =
        Fixtures.workflowProgress(
            RUN, SESSION, "wf", TaskStatus.FAILURE, tasks, Instant.EPOCH, Instant.EPOCH, null);
    final List<WorkflowExecutionSummary> runs =
        IntStream.range(0, 2_000)
            .mapToObj(i ->
                Fixtures.summary(
                    "run-" + i,
                    "wf",
                    TaskStatus.SUCCESS,
                    Instant.EPOCH.plusSeconds(i),
                    Instant.EPOCH.plusSeconds(i + 1)))
            .toList();

    final ControlBusGateway controlBus = mock(ControlBusGateway.class);
    when(controlBus.getProgress(SESSION, RUN)).thenReturn(progress);
    final TaskTrackerService tracker = mock(TaskTrackerService.class);
    when(tracker.getTree(RUN)).thenReturn(List.of());
    final ExecutionResultService results =
        new ExecutionResultService(1000, Duration.ofMinutes(5), 10);
    IntStream.range(0, 50)
        .forEach(i -> results.publish(RUN, "out", Map.of("stdout", noise.repeat(70), "n", i)));
    results.complete(RUN, "FAILURE");
    final WorkflowExecutionProvider executions = mock(WorkflowExecutionProvider.class);
    when(executions.getWorkflowHistory(SESSION)).thenReturn(Mono.just(runs));
    when(executions.getWorkflowStatus(SESSION, RUN, null)).thenReturn(Mono.just(progress));
    final WorkflowControlProvider control = mock(WorkflowControlProvider.class);
    when(control.getExecutionTree(SESSION, RUN)).thenReturn(Mono.just(runs));

    // the defaults the server runs with (application.yaml leaves these unset)
    tools =
        new AppMcpTools(
            mock(SessionInfoProvider.class),
            new LogProvider(store, controlBus, List.of(), 2_000, 200_000, 16_000),
            new DiagnosisProvider(
                controlBus,
                new RunLogSearcher(store, tracker),
                new FailureDigestService(
                    store,
                    tracker,
                    List.of(
                        new ReportLineExtractor(),
                        new GradleFailureExtractor(),
                        new GoFailureExtractor(),
                        new JsFailureExtractor())),
                List.of(),
                2_000),
            executions,
            control,
            mock(PluginInfoProvider.class),
            mock(SystemHealthProvider.class),
            new ResultProvider(results, controlBus, List.of(), 32_000),
            mock(WorkflowVersionProvider.class),
            mock(MetricsProvider.class),
            mock(SupervisorProvider.class),
            mock(InputProvider.class),
            mock(NotificationProvider.class));
  }

  private static int size(final Mono<?> answer) {
    return JSON.writeValueAsString(answer.block()).getBytes(StandardCharsets.UTF_8).length;
  }

  @Test
  void digest_staysWithinItsBudget() {
    assertThat(size(tools.getFailureDigest(SESSION, RUN))).isLessThan(DIGEST_BOUND);
  }

  @Test
  void search_staysWithinTheReadBound() {
    assertThat(
            size(
                tools.searchLogs(
                    SESSION, RUN, null, null, null, null, null, null, null, null, null)))
        .isLessThan(READ_BOUND);
    assertThat(
            size(
                tools.searchLogs(
                    SESSION,
                    RUN,
                    null,
                    null,
                    null,
                    null,
                    null,
                    2,
                    LogSearchMode.LINES,
                    null,
                    null)))
        .isLessThan(READ_BOUND);
  }

  @Test
  void logsAndNodeOutput_stayWithinTheReadBound() {
    assertThat(size(tools.getExecutionLogs(SESSION, RUN, null, null, null, null, null)))
        .isLessThan(READ_BOUND);
    assertThat(
            size(
                tools.getNodeOutput(
                    SESSION, RUN, "build", null, null, null, null, null, null, null)))
        .isLessThan(READ_BOUND);
  }

  @Test
  void results_stayWithinTheReadBound() {
    // include=stdout is the caller's explicit choice; it is held to yukta.mcp.result-max-chars
    assertThat(size(tools.getExecutionResult(SESSION, RUN, null, null))).isLessThan(READ_BOUND);
  }

  @Test
  void historyTreeAndStatus_stayWithinTheReadBound() {
    assertThat(size(tools.getWorkflowHistory(SESSION, null, null))).isLessThan(READ_BOUND);
    assertThat(size(tools.getExecutionTree(SESSION, RUN, null, null))).isLessThan(READ_BOUND);
    assertThat(size(tools.getWorkflowStatus(SESSION, RUN, null, true))).isLessThan(READ_BOUND);
  }
}
