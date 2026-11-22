// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.infenia.yukta.logging.api.IndexedLog;
import com.infenia.yukta.logging.api.LogStream;
import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.logging.api.PluginLogStore;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.plugin.core.LogLevel;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Tests for RunLogSearcher. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
class RunLogSearcherTest {

  private static final String RUN = "run-1";
  private static final String CHILD = "run-2";

  private PluginLogStore store;
  private TaskTrackerService tracker;
  private RunLogSearcher searcher;

  @BeforeEach
  void setUp() {
    store = mock(PluginLogStore.class);
    tracker = mock(TaskTrackerService.class);
    searcher = new RunLogSearcher(store, tracker);
    inSession(RUN, "s");
    inSession(CHILD, "s");
  }

  private void inSession(final String run, final String session) {
    final WorkflowProgress progress = mock(WorkflowProgress.class);
    when(progress.sessionId()).thenReturn(session);
    when(tracker.getProgressByExecutionId(run)).thenReturn(progress);
  }

  @Test
  void children_ofAnotherSessionOrOfAnUnknownRun_areNotSearched() {
    logs(RUN, List.of(entry(RUN, "a", "x", LogLevel.INFO)));
    inSession(CHILD, "other");
    final List<WorkflowExecutionSummary> tree = List.of(summary(RUN), summary(CHILD));
    when(tracker.getTree(RUN)).thenReturn(tree);
    final RunLogQuery query = RunLogQuery.builder().executionId(RUN).includeChildren(true).build();

    assertThat(search(query).executionsScanned()).isEqualTo(1);

    when(tracker.getProgressByExecutionId(RUN)).thenReturn(null);
    assertThat(search(query).executionsScanned()).isEqualTo(1);
  }

  private static PluginLogEntry entry(
      final String run, final String node, final String text, final LogLevel level) {
    return new PluginLogEntry(
        run, "s", node, "PROCESS", LogStream.STDOUT, text, level, Instant.EPOCH, null, null);
  }

  private void logs(final String run, final List<PluginLogEntry> entries) {
    final List<IndexedLog> indexed = new ArrayList<>();
    for (int i = 0; i < entries.size(); i++) {
      indexed.add(new IndexedLog(i, entries.get(i)));
    }
    when(store.readIndexed(run)).thenReturn(Flux.fromIterable(indexed));
  }

  /** A build node with one failure among noise, and a quiet second node. */
  private void gradleRun() {
    logs(
        RUN,
        List.of(
            entry(RUN, "build", "> Task :core:compileJava", LogLevel.INFO),
            entry(RUN, "lint", "lint ok", LogLevel.INFO),
            entry(RUN, "build", "FooTest > adds() FAILED", LogLevel.ERROR),
            entry(RUN, "build", "    expected 2 but was 3", LogLevel.INFO),
            entry(RUN, "build", "deprecated API", LogLevel.WARN),
            entry(RUN, "build", "BUILD FAILED", LogLevel.ERROR)));
  }

  private RunLogSearch search(final RunLogQuery query) {
    return searcher.search(query, UnaryOperator.identity()).block();
  }

  @Test
  void count_listsEveryNodeWithItsLevels() {
    gradleRun();

    final RunLogSearch out = search(RunLogQuery.builder().executionId(RUN).build());

    assertThat(out.mode()).isEqualTo(LogSearchMode.COUNT);
    assertThat(out.linesScanned()).isEqualTo(6);
    assertThat(out.lines()).isEmpty();
    assertThat(out.nodes())
        .containsExactly(
            new RunLogSearch.NodeCount(RUN, "build", 5, 2, 1, 5),
            new RunLogSearch.NodeCount(RUN, "lint", 1, 0, 0, 1));
    assertThat(out.hint()).contains("mode=LINES", "nodes=build", "level=ERROR");
    verifyNoInteractions(tracker);
  }

  @Test
  void nodes_listsOnlyTheNodesThatMatched() {
    gradleRun();

    final RunLogSearch out =
        search(
            RunLogQuery.builder()
                .executionId(RUN)
                .level(LogLevel.ERROR)
                .mode(LogSearchMode.NODES)
                .build());

    assertThat(out.matches()).isEqualTo(2);
    assertThat(out.nodes()).extracting(RunLogSearch.NodeCount::nodeId).containsExactly("build");
    assertThat(out.hint()).doesNotContain("level=ERROR");
  }

  @Test
  void lines_returnsMatchesWithTheirContextFromTheSameNode() {
    gradleRun();

    final RunLogSearch out =
        search(
            RunLogQuery.builder()
                .executionId(RUN)
                .grep("FAILED")
                .context(1)
                .mode(LogSearchMode.LINES)
                .build());

    assertThat(out.lines())
        .extracting(RunLogSearch.Line::text)
        .containsExactly(
            "> Task :core:compileJava",
            "FooTest > adds() FAILED",
            "    expected 2 but was 3",
            "deprecated API",
            "BUILD FAILED");
    assertThat(out.lines())
        .extracting(RunLogSearch.Line::match)
        .containsExactly(false, true, false, false, true);
    assertThat(out.lines().get(1).level()).isEqualTo("ERROR");
    assertThat(out.lines().get(1).stream()).isEqualTo("stdout");
    assertThat(out.truncated()).isFalse();
    assertThat(out.nextCursor()).isNull();
    assertThat(out.hint()).isNull();
  }

  @Test
  void lines_pageThroughTheMatchesWithTheCursor() {
    logs(
        RUN,
        IntStream.range(0, 120)
            .mapToObj(i -> entry(RUN, "n", "error " + i, LogLevel.ERROR))
            .toList());
    final RunLogQuery.RunLogQueryBuilder query =
        RunLogQuery.builder()
            .executionId(RUN)
            .level(LogLevel.ERROR)
            .mode(LogSearchMode.LINES)
            .maxMatches(50);

    final RunLogSearch first = search(query.build());
    final RunLogSearch second = search(query.cursor(first.nextCursor()).build());
    final RunLogSearch third = search(query.cursor(second.nextCursor()).build());

    assertThat(first.lines()).hasSize(50);
    assertThat(first.truncated()).isTrue();
    assertThat(first.nextCursor()).isEqualTo(RUN + ":49");
    assertThat(first.hint()).contains("cursor=" + RUN + ":49");
    assertThat(second.lines().getFirst().text()).isEqualTo("error 50");
    assertThat(third.lines()).hasSize(20);
    assertThat(third.truncated()).isFalse();
    assertThat(third.nextCursor()).isNull();
  }

  @Test
  void lines_stopAtTheByteBudget() {
    logs(
        RUN,
        IntStream.range(0, 100)
            .mapToObj(i -> entry(RUN, "n", "x".repeat(1999), LogLevel.INFO))
            .toList());

    final RunLogSearch out =
        search(RunLogQuery.builder().executionId(RUN).mode(LogSearchMode.LINES).build());

    assertThat(out.lines()).hasSize(8);
    assertThat(out.truncated()).isTrue();
  }

  @Test
  void filters_byNodeAndStream() {
    logs(
        RUN,
        List.of(
            entry(RUN, "a", "one", LogLevel.INFO),
            new PluginLogEntry(
                RUN,
                "s",
                "a",
                "P",
                LogStream.STDERR,
                "two",
                LogLevel.INFO,
                Instant.EPOCH,
                null,
                null),
            entry(RUN, "b", "three", LogLevel.INFO)));

    final RunLogSearch out =
        search(
            RunLogQuery.builder().executionId(RUN).nodes(Set.of("a")).stream(LogStream.STDERR)
                .mode(LogSearchMode.LINES)
                .build());

    assertThat(out.lines()).extracting(RunLogSearch.Line::text).containsExactly("two");
    assertThat(out.lines().getFirst().stream()).isEqualTo("stderr");
  }

  @Test
  void children_areSearchedAfterTheRunWhenAskedFor() {
    logs(RUN, List.of(entry(RUN, "call", "starting child", LogLevel.INFO)));
    logs(CHILD, List.of(entry(CHILD, "test", "Exception in test", LogLevel.ERROR)));
    final List<WorkflowExecutionSummary> tree = List.of(summary(RUN), summary(CHILD));
    when(tracker.getTree(RUN)).thenReturn(tree);

    final RunLogSearch out =
        search(
            RunLogQuery.builder()
                .executionId(RUN)
                .includeChildren(true)
                .level(LogLevel.ERROR)
                .build());

    assertThat(out.executionsScanned()).isEqualTo(2);
    assertThat(out.nodes())
        .containsExactly(
            new RunLogSearch.NodeCount(CHILD, "test", 1, 1, 0, 1),
            new RunLogSearch.NodeCount(RUN, "call", 1, 0, 0, 0));
    assertThat(out.hint()).contains("in run " + CHILD);
  }

  @Test
  void lines_pageAcrossTheRunAndItsChildren() {
    logs(
        RUN,
        IntStream.range(0, 3)
            .mapToObj(i -> entry(RUN, "a", "run error " + i, LogLevel.ERROR))
            .toList());
    logs(
        CHILD,
        IntStream.range(0, 3)
            .mapToObj(i -> entry(CHILD, "b", "child error " + i, LogLevel.ERROR))
            .toList());
    final List<WorkflowExecutionSummary> tree = List.of(summary(RUN), summary(CHILD));
    when(tracker.getTree(RUN)).thenReturn(tree);
    final RunLogQuery.RunLogQueryBuilder query =
        RunLogQuery.builder()
            .executionId(RUN)
            .includeChildren(true)
            .mode(LogSearchMode.LINES)
            .maxMatches(2);

    final RunLogSearch first = search(query.build());
    final RunLogSearch second = search(query.cursor(first.nextCursor()).build());
    final RunLogSearch third = search(query.cursor(second.nextCursor()).build());

    assertThat(first.lines())
        .extracting(RunLogSearch.Line::text)
        .containsExactly("run error 0", "run error 1");
    assertThat(second.lines())
        .extracting(RunLogSearch.Line::text)
        .containsExactly("run error 2", "child error 0");
    assertThat(second.nextCursor()).isEqualTo(CHILD + ":0");
    assertThat(third.lines())
        .extracting(RunLogSearch.Line::text)
        .containsExactly("child error 1", "child error 2");
  }

  @Test
  void lines_lineOverByteBudgetEndsAnswerWithoutCursor() {
    logs(RUN, List.of(entry(RUN, "n", "long line", LogLevel.INFO)));

    final RunLogSearch out =
        search(
            RunLogQuery.builder().executionId(RUN).mode(LogSearchMode.LINES).maxBytes(4).build());

    assertThat(out.lines()).isEmpty();
    assertThat(out.truncated()).isTrue();
    assertThat(out.nextCursor()).isNull();
  }

  @Test
  void count_withGrepPointsAtTheLinesWithoutLevel() {
    gradleRun();

    final RunLogSearch out =
        search(RunLogQuery.builder().executionId(RUN).grep("FAILED").cursor(" ").build());

    assertThat(out.matches()).isEqualTo(2);
    assertThat(out.hint()).contains("nodes=build").doesNotContain("level=ERROR");
  }

  @Test
  void noMatchWithChildren_onlySuggestsWidening() {
    gradleRun();
    when(tracker.getTree(RUN)).thenReturn(List.of());

    final RunLogSearch out =
        search(
            RunLogQuery.builder().executionId(RUN).includeChildren(true).grep("nothing").build());

    assertThat(out.hint()).isEqualTo("No line matched: widen grep or level.");
  }

  @Test
  void noMatch_suggestsWideningTheSearch() {
    gradleRun();

    final RunLogSearch out = search(RunLogQuery.builder().executionId(RUN).grep("nothing").build());

    assertThat(out.matches()).isZero();
    assertThat(out.hint()).contains("includeChildren");
  }

  @Test
  void clean_isAppliedBeforeMatching() {
    logs(RUN, List.of(entry(RUN, "n", "token=secret", LogLevel.INFO)));

    final RunLogSearch out =
        searcher
            .search(
                RunLogQuery.builder().executionId(RUN).grep("secret").build(),
                text -> text.replace("secret", "***"))
            .block();

    assertThat(out.matches()).isZero();
  }

  @Test
  void manyNodes_areCappedInTheAnswer() {
    logs(
        RUN,
        IntStream.range(0, 60)
            .mapToObj(i -> entry(RUN, "node-" + i, "line", LogLevel.INFO))
            .toList());

    final RunLogSearch out = search(RunLogQuery.builder().executionId(RUN).build());

    assertThat(out.nodes()).hasSize(RunLogSearch.MAX_NODES);
    assertThat(out.nodesOmitted()).isEqualTo(10);
  }

  @Test
  void badCursorOrPattern_isRefused() {
    gradleRun();

    StepVerifier.create(
            searcher.search(
                RunLogQuery.builder().executionId(RUN).cursor("other:3").build(),
                UnaryOperator.identity()))
        .expectErrorMessage("cursor is not a nextCursor of this search")
        .verify();
    StepVerifier.create(
            searcher.search(
                RunLogQuery.builder().executionId(RUN).cursor("nonsense").build(),
                UnaryOperator.identity()))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(
            searcher.search(
                RunLogQuery.builder().executionId(RUN).grep("(").build(), UnaryOperator.identity()))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void entriesWithoutLevelStreamOrText_readAsPlainOutput() {
    logs(
        RUN,
        List.of(
            new PluginLogEntry(RUN, "s", "n", "P", null, null, null, Instant.EPOCH, null, null)));

    final RunLogSearch out =
        search(RunLogQuery.builder().executionId(RUN).mode(LogSearchMode.LINES).build());

    assertThat(out.lines().getFirst())
        .isEqualTo(new RunLogSearch.Line(RUN, "n", 0, "stdout", "INFO", true, ""));
  }

  private static WorkflowExecutionSummary summary(final String id) {
    final WorkflowExecutionSummary summary = mock(WorkflowExecutionSummary.class);
    when(summary.executionId()).thenReturn(id);
    return summary;
  }
}
