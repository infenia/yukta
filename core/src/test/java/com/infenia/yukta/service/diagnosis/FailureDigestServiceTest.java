// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.logging.api.LogStream;
import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.logging.api.PluginLogStore;
import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.plugin.core.LogLevel;
import com.infenia.yukta.service.orchestrator.stream.NodeStreams;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** Tests for FailureDigestService. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
class FailureDigestServiceTest {

  private static final String RUN = "run-1";
  private static final String CHILD = "run-2";

  private PluginLogStore store;
  private TaskTrackerService tracker;
  private FailureDigestService service;

  @BeforeEach
  void setUp() {
    store = mock(PluginLogStore.class);
    tracker = mock(TaskTrackerService.class);
    service =
        new FailureDigestService(
            store,
            tracker,
            List.of(
                new ReportLineExtractor(),
                new GradleFailureExtractor(),
                new GoFailureExtractor(),
                new JsFailureExtractor()));
    when(tracker.getTree(RUN)).thenReturn(List.of());
  }

  private static PluginLogEntry line(
      final String run, final String node, final String text, final LogLevel level) {
    return new PluginLogEntry(
        run, "s", node, "PROCESS", LogStream.STDOUT, text, level, Instant.EPOCH, null, null);
  }

  private static TaskProgress task(
      final String node, final TaskStatus status, final Map<String, Object> metadata) {
    return new TaskProgress(node, "PROCESS_EXECUTOR", status, Instant.EPOCH, null, metadata, null);
  }

  private static WorkflowProgress progress(
      final String run, final TaskStatus status, final TaskProgress... tasks) {
    return new WorkflowProgress(
        run,
        "s",
        "wf",
        status,
        List.of(tasks),
        Instant.EPOCH,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private void logs(final String run, final PluginLogEntry... entries) {
    when(store.readExecution(run)).thenReturn(Flux.just(entries));
  }

  private FailureDigest digest(final WorkflowProgress run) {
    return service.digest(run, UnaryOperator.identity()).block();
  }

  @Test
  void failedGradleNode_isSummarisedWithItsFailingTest() {
    logs(
        RUN,
        line(RUN, "build", "> Task :core:test", LogLevel.INFO),
        line(RUN, "lint", "all good", LogLevel.INFO),
        line(RUN, "build", "com.x.FooTest > adds() FAILED", LogLevel.ERROR),
        line(RUN, "build", "    AssertionFailedError: expected 2", LogLevel.ERROR),
        line(RUN, "build", "> Task :core:test FAILED", LogLevel.ERROR),
        line(RUN, "build", "* What went wrong:", LogLevel.ERROR),
        line(RUN, "build", "Execution failed for task ':core:test'.", LogLevel.ERROR),
        line(RUN, "build", "> There were failing tests.", LogLevel.ERROR));

    final FailureDigest digest =
        digest(
            progress(
                RUN,
                TaskStatus.FAILURE,
                task("lint", TaskStatus.SUCCESS, Map.of()),
                task("build", TaskStatus.FAILURE, Map.of(NodeStreams.ERROR_KEY, "exit 1"))));

    assertThat(digest.complete()).isTrue();
    assertThat(digest.status()).isEqualTo("FAILURE");
    assertThat(digest.runsSearched()).isEqualTo(1);
    assertThat(digest.failedNodes())
        .containsExactly(
            new FailureDigest.FailedNode(
                RUN, "build", "PROCESS_EXECUTOR", "FAILURE", "exit 1", 7, 6));
    assertThat(digest.findings())
        .extracting(Finding::kind, Finding::name, Finding::message)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                Finding.Kind.TEST, "com.x.FooTest > adds()", "AssertionFailedError: expected 2"),
            org.assertj.core.groups.Tuple.tuple(
                Finding.Kind.TASK, ":core:test", "There were failing tests."));
    assertThat(digest.next().getFirst())
        .isEqualTo(
            new FailureDigest.NextRead(
                FailureDigest.NextRead.Kind.NODE_OUTPUT,
                RUN,
                "build",
                0L,
                null,
                "the lines around com.x.FooTest > adds()"));
  }

  @Test
  void unknownOutput_fallsBackToErrorLinesAndSearch() {
    logs(
        RUN,
        line(RUN, "call", "calling", LogLevel.INFO),
        line(RUN, "call", "connection refused", LogLevel.ERROR));

    final FailureDigest digest =
        digest(progress(RUN, TaskStatus.FAILURE, task("call", TaskStatus.TIMED_OUT, Map.of())));

    assertThat(digest.failedNodes().getFirst().error()).isNull();
    assertThat(digest.findings())
        .extracting(Finding::kind, Finding::message, Finding::line)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(Finding.Kind.ERROR, "connection refused", 2L));
    assertThat(digest.next())
        .extracting(FailureDigest.NextRead::kind, FailureDigest.NextRead::level)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(FailureDigest.NextRead.Kind.NODE_OUTPUT, null),
            org.assertj.core.groups.Tuple.tuple(FailureDigest.NextRead.Kind.SEARCH, "ERROR"));
  }

  @Test
  void failedSubRuns_areDigestedToo() {
    final WorkflowProgress child =
        progress(CHILD, TaskStatus.FAILURE, task("test", TaskStatus.FAILURE, Map.of()));
    final WorkflowExecutionSummary root = summary(RUN, TaskStatus.FAILURE);
    final WorkflowExecutionSummary failedChild = summary(CHILD, TaskStatus.FAILURE);
    final WorkflowExecutionSummary okChild = summary("run-3", TaskStatus.SUCCESS);
    final WorkflowExecutionSummary goneChild = summary("run-4", TaskStatus.TIMED_OUT);
    final WorkflowExecutionSummary foreignChild = summary("run-5", TaskStatus.FAILURE);
    when(tracker.getTree(RUN))
        .thenReturn(List.of(root, failedChild, okChild, goneChild, foreignChild));
    when(tracker.getProgressByExecutionId(CHILD)).thenReturn(child);
    final WorkflowProgress foreign =
        new WorkflowProgress(
            "run-5",
            "other",
            "wf",
            TaskStatus.FAILURE,
            List.of(task("x", TaskStatus.FAILURE, Map.of())),
            Instant.EPOCH,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    when(tracker.getProgressByExecutionId("run-5")).thenReturn(foreign);
    logs(RUN, line(RUN, "sub", "starting", LogLevel.INFO));
    logs(CHILD, line(CHILD, "test", "--- FAIL: TestParse (0.00s)", LogLevel.ERROR));

    final FailureDigest digest =
        digest(progress(RUN, TaskStatus.FAILURE, task("sub", TaskStatus.FAILURE, Map.of())));

    assertThat(digest.runsSearched()).isEqualTo(2);
    assertThat(digest.failedNodes())
        .extracting(FailureDigest.FailedNode::executionId)
        .containsExactly(RUN, CHILD);
    assertThat(digest.findings()).extracting(Finding::name).containsExactly("TestParse");
    assertThat(digest.findings().getFirst().executionId()).isEqualTo(CHILD);
  }

  @Test
  void runningRunWithoutFailures_pointsAtItsWarnings() {
    logs(RUN);

    final FailureDigest digest =
        digest(progress(RUN, TaskStatus.RUNNING, task("a", TaskStatus.RUNNING, new HashMap<>())));

    assertThat(digest.complete()).isFalse();
    assertThat(digest.failedNodes()).isEmpty();
    assertThat(digest.next()).extracting(FailureDigest.NextRead::level).containsExactly("WARN");
  }

  @Test
  void succeededRun_hasNothingToReadNext() {
    logs(RUN);

    final FailureDigest digest =
        digest(progress(RUN, TaskStatus.SUCCESS, task("a", TaskStatus.SUCCESS, Map.of())));

    assertThat(digest.findings()).isEmpty();
    assertThat(digest.next()).isEmpty();
  }

  @Test
  void failedRunWithoutFailedNode_suggestsSearchingTheRun() {
    logs(RUN);

    final FailureDigest digest =
        digest(progress(RUN, TaskStatus.FAILURE, task("a", TaskStatus.UPSTREAM_FAILED, Map.of())));

    assertThat(digest.next())
        .containsExactly(
            new FailureDigest.NextRead(
                FailureDigest.NextRead.Kind.SEARCH,
                RUN,
                null,
                null,
                "ERROR",
                "the error lines of the run"));
  }

  @Test
  void manyFindings_stayWithinTheBudget() {
    final List<PluginLogEntry> lines = new ArrayList<>();
    IntStream.range(0, 40)
        .forEach(i ->
            lines.add(
                line(
                    RUN,
                    "build",
                    "/r/F" + i + ".java:1: error: " + "x".repeat(250),
                    LogLevel.ERROR)));
    logs(RUN, lines.toArray(PluginLogEntry[]::new));

    final FailureDigest digest =
        digest(progress(RUN, TaskStatus.FAILURE, task("build", TaskStatus.FAILURE, Map.of())));

    assertThat(digest.findings()).hasSizeBetween(5, 12);
    assertThat(digest.findingsOmitted()).isEqualTo(40 - digest.findings().size());
    assertThat(digest.failedNodes().getFirst().errorLines()).isEqualTo(40);
  }

  @Test
  void fallback_takesTheFirstErrorLinesOnly() {
    logs(
        RUN,
        line(RUN, "n", "e1", LogLevel.ERROR),
        new PluginLogEntry(
            RUN, "s", "n", "P", LogStream.STDOUT, null, LogLevel.ERROR, Instant.EPOCH, null, null),
        line(RUN, "n", "e3", LogLevel.ERROR),
        line(RUN, "n", "e4", LogLevel.ERROR));

    final FailureDigest digest =
        digest(progress(RUN, TaskStatus.FAILURE, task("n", TaskStatus.FAILURE, Map.of())));

    assertThat(digest.findings()).extracting(Finding::message).containsExactly("e1", "", "e3");
  }

  @Test
  void duplicates_keepTheMoreTellingMessage() {
    logs(
        RUN,
        line(RUN, "b", "* What went wrong:", LogLevel.ERROR),
        line(RUN, "b", "Execution failed for task ':a'.", LogLevel.ERROR),
        line(RUN, "b", "> boom", LogLevel.ERROR),
        line(RUN, "b", "", LogLevel.INFO),
        line(RUN, "b", "> Task :a FAILED", LogLevel.ERROR),
        line(RUN, "b", "[ant:checkstyle] [ERROR] F.java:1: first", LogLevel.ERROR),
        line(RUN, "b", "[ant:checkstyle] [ERROR] F.java:1: second", LogLevel.ERROR));

    final FailureDigest digest =
        digest(progress(RUN, TaskStatus.FAILURE, task("b", TaskStatus.FAILURE, Map.of())));

    assertThat(digest.findings())
        .extracting(Finding::name, Finding::message)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(null, "first"),
            org.assertj.core.groups.Tuple.tuple(":a", "boom"));
  }

  @Test
  void longError_isCut() {
    final FailureDigest.FailedNode node =
        new FailureDigest.FailedNode("r", "n", "m", "FAILURE", "e".repeat(400), 0, 0);

    assertThat(node.error()).hasSize(FailureDigest.FailedNode.MAX_ERROR + 1);
  }

  private static WorkflowExecutionSummary summary(final String id, final TaskStatus status) {
    final WorkflowExecutionSummary summary = mock(WorkflowExecutionSummary.class);
    when(summary.executionId()).thenReturn(id);
    when(summary.status()).thenReturn(status);
    return summary;
  }
}
