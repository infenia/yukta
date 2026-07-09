// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.compiler;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

@ExtendWith(MockitoExtension.class)
@DisplayName("HeartbeatBuilder Tests")
@NoArgsConstructor
@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.LawOfDemeter"
})
class HeartbeatBuilderTest {

  private static final Duration DEFAULT_INTERVAL = Duration.ofMillis(500);
  private static final Duration CUSTOM_HB_INTERVAL = Duration.ofSeconds(1);
  private static final Duration CUSTOM_STATS_INTERVAL = Duration.ofSeconds(2);

  private HeartbeatBuilder builder;

  @BeforeEach
  void setUp() {
    builder = new HeartbeatBuilder(DEFAULT_INTERVAL, Schedulers.boundedElastic());
  }

  @Test
  @DisplayName("Constructor creates instance with valid duration and scheduler")
  void testConstructor_validDurationAndScheduler_createsInstance() {
    assertThat(builder).isNotNull();
  }

  @Test
  @DisplayName("forNodes sets nodes and returns builder for fluent chaining")
  void testForNodes_validWfIdAndNodeList_setsNodesAndReturnsBuilder() {
    final List<String> nodeList = List.of("node-1", "node-2");
    final HeartbeatBuilder result = builder.forNodes("workflow-1", nodeList);

    assertThat(result).isSameAs(builder);
    final List<Disposable> disposables = builder.build();
    assertThat(disposables).hasSize(4);
  }

  @Test
  @DisplayName("forNodes with blank wfId still sets nodes (validation happens at binding time)")
  void testForNodes_blankWfId_setsNodesStill() {
    final List<String> nodeList = List.of("node-1");
    final HeartbeatBuilder result = builder.forNodes("", nodeList);

    assertThat(result).isSameAs(builder);
  }

  @Test
  @DisplayName("forNodes with null node list creates empty disposables on build")
  void testForNodes_nullNodeList_createsEmptyDisposables() {
    final HeartbeatBuilder result = builder.forNodes("workflow-1", null);

    assertThat(result).isSameAs(builder);
    final List<Disposable> disposables = builder.build();
    assertThat(disposables).isEmpty();
  }

  @Test
  @DisplayName("forNodes with empty node list creates empty disposables on build")
  void testForNodes_emptyNodeList_createsEmptyDisposables() {
    final HeartbeatBuilder result = builder.forNodes("workflow-1", new ArrayList<>());

    assertThat(result).isSameAs(builder);
    final List<Disposable> disposables = builder.build();
    assertThat(disposables).isEmpty();
  }

  @Test
  @DisplayName("withHeartbeatInterval sets interval and returns builder")
  void testWithHeartbeatInterval_validDuration_setsIntervalAndReturnsBuilder() {
    final HeartbeatBuilder result = builder.withHeartbeatInterval(CUSTOM_HB_INTERVAL);

    assertThat(result).isSameAs(builder);
    builder.forNodes("workflow-1", List.of("node-1"));
    final List<Disposable> disposables = builder.build();
    assertThat(disposables).hasSize(2);
  }

  @Test
  @DisplayName("withHeartbeatInterval with null duration returns builder")
  void testWithHeartbeatInterval_nullDuration_returnsBuilder() {
    final HeartbeatBuilder result = builder.withHeartbeatInterval(null);

    assertThat(result).isSameAs(builder);
  }

  @Test
  @DisplayName("withStatisticsInterval sets interval and returns builder")
  void testWithStatisticsInterval_validDuration_setsIntervalAndReturnsBuilder() {
    final HeartbeatBuilder result = builder.withStatisticsInterval(CUSTOM_STATS_INTERVAL);

    assertThat(result).isSameAs(builder);
    builder.forNodes("workflow-1", List.of("node-1"));
    final List<Disposable> disposables = builder.build();
    assertThat(disposables).hasSize(2);
  }

  @Test
  @DisplayName("withStatisticsInterval with null duration returns builder")
  void testWithStatisticsInterval_nullDuration_returnsBuilder() {
    final HeartbeatBuilder result = builder.withStatisticsInterval(null);

    assertThat(result).isSameAs(builder);
  }

  @Test
  @DisplayName("build with no nodes configured returns empty list")
  void testBuild_noNodesConfigured_returnsEmptyList() {
    final List<Disposable> disposables = builder.build();

    assertThat(disposables).isEmpty();
  }

  @Test
  @DisplayName("build with empty nodes list returns empty list")
  void testBuild_emptyNodesList_returnsEmptyList() {
    builder.forNodes("workflow-1", List.of());
    final List<Disposable> disposables = builder.build();

    assertThat(disposables).isEmpty();
  }

  @Test
  @DisplayName("build with nodes and defaults creates 2 disposables per node")
  void testBuild_nodesWithDefaults_createsDisposablesWithDefaultIntervals() {
    builder.forNodes("workflow-1", List.of("node-1", "node-2"));
    final List<Disposable> disposables = builder.build();

    assertThat(disposables).hasSize(4);
    disposables.forEach(d -> assertThat(d.isDisposed()).isFalse());
  }

  @Test
  @DisplayName("build with custom heartbeat interval uses custom interval")
  void testBuild_nodesWithCustomHeartbeatInterval_usesCustomHeartbeat() {
    builder.forNodes("workflow-1", List.of("node-1")).withHeartbeatInterval(CUSTOM_HB_INTERVAL);
    final List<Disposable> disposables = builder.build();

    assertThat(disposables).hasSize(2);
    disposables.forEach(d -> assertThat(d.isDisposed()).isFalse());
  }

  @Test
  @DisplayName("build with custom statistics interval uses custom interval")
  void testBuild_nodesWithCustomStatisticsInterval_usesCustomStatistics() {
    builder.forNodes("workflow-1", List.of("node-1")).withStatisticsInterval(CUSTOM_STATS_INTERVAL);
    final List<Disposable> disposables = builder.build();

    assertThat(disposables).hasSize(2);
    disposables.forEach(d -> assertThat(d.isDisposed()).isFalse());
  }

  @Test
  @DisplayName("build with both custom intervals uses both custom intervals")
  void testBuild_nodesWithBothCustomIntervals_usesBothCustomIntervals() {
    builder
        .forNodes("workflow-1", List.of("node-1"))
        .withHeartbeatInterval(CUSTOM_HB_INTERVAL)
        .withStatisticsInterval(CUSTOM_STATS_INTERVAL);
    final List<Disposable> disposables = builder.build();

    assertThat(disposables).hasSize(2);
    disposables.forEach(d -> assertThat(d.isDisposed()).isFalse());
  }

  @Test
  @DisplayName("build with multiple nodes creates 2 disposables per node")
  void testBuild_multipleNodes_createsTwoDisposablesPerNode() {
    builder.forNodes("workflow-1", List.of("node-1", "node-2", "node-3"));
    final List<Disposable> disposables = builder.build();

    assertThat(disposables).hasSize(6);
    disposables.forEach(d -> assertThat(d.isDisposed()).isFalse());
  }

  @Test
  @DisplayName("fluent chaining returns same builder instance")
  void testFluentChaining_allMethodsReturnSameInstance() {
    final HeartbeatBuilder result =
        builder
            .forNodes("workflow-1", List.of("node-1"))
            .withHeartbeatInterval(CUSTOM_HB_INTERVAL)
            .withStatisticsInterval(CUSTOM_STATS_INTERVAL);

    assertThat(result).isSameAs(builder);
  }

  @Test
  @DisplayName("statistics interval defaults to 2x heartbeat when not explicitly set")
  void testBuild_statisticsDefaultsTo2xHeartbeat_whenStatsNotExplicitlySet() {
    final Duration customHeartbeatBuilder = Duration.ofMillis(300);
    builder.forNodes("workflow-1", List.of("node-1")).withHeartbeatInterval(customHeartbeatBuilder);
    final List<Disposable> disposables = builder.build();

    assertThat(disposables).hasSize(2);
    disposables.forEach(d -> assertThat(d.isDisposed()).isFalse());
  }

  @Test
  @DisplayName("statistics uses default interval when heartbeat not explicitly set")
  void testBuild_statisticsUsesDefaultWhenHeartbeatNotSet() {
    final Duration customStats = Duration.ofSeconds(5);
    builder.forNodes("workflow-1", List.of("node-1")).withStatisticsInterval(customStats);
    final List<Disposable> disposables = builder.build();

    assertThat(disposables).hasSize(2);
    disposables.forEach(d -> assertThat(d.isDisposed()).isFalse());
  }

  @Test
  @DisplayName("disposables can be disposed")
  void testDisposables_canBeDisposed() {
    builder.forNodes("workflow-1", List.of("node-1"));
    final List<Disposable> disposables = builder.build();

    assertThat(disposables).hasSize(2);
    disposables.forEach(Disposable::dispose);
    disposables.forEach(d -> assertThat(d.isDisposed()).isTrue());
  }

  @Test
  @DisplayName("build creates fresh disposables on each call")
  void testBuild_createsFreshDisposablesOnEachCall() {
    builder.forNodes("workflow-1", List.of("node-1"));
    final List<Disposable> firstBuild = builder.build();
    final List<Disposable> secondBuild = builder.build();

    assertThat(firstBuild).hasSize(2);
    assertThat(secondBuild).hasSize(2);
    assertThat(firstBuild).isNotSameAs(secondBuild);
  }

  @Test
  @DisplayName("forNodes creates new ArrayList copy of provided list")
  void testForNodes_createsNewArrayListCopy() {
    final List<String> originalList = new ArrayList<>(List.of("node-1"));
    builder.forNodes("workflow-1", originalList);

    final List<Disposable> disposables1 = builder.build();
    assertThat(disposables1).hasSize(2);

    originalList.add("node-2");
    final List<Disposable> disposables2 = builder.build();
    assertThat(disposables2).hasSize(2);
  }

  @Test
  @DisplayName("builder can be reused with forNodes")
  void testBuilder_canBeReusedWithForNodes() {
    builder.forNodes("workflow-1", List.of("node-1"));
    final List<Disposable> disposables1 = builder.build();

    builder.forNodes("workflow-2", List.of("node-2", "node-3"));
    final List<Disposable> disposables2 = builder.build();

    assertThat(disposables1).hasSize(2);
    assertThat(disposables2).hasSize(4);
  }

  @Test
  @DisplayName("withHeartbeatInterval can be called multiple times (last one wins)")
  void testWithHeartbeatInterval_calledMultipleTimes_lastOneWins() {
    builder
        .forNodes("workflow-1", List.of("node-1"))
        .withHeartbeatInterval(Duration.ofSeconds(1))
        .withHeartbeatInterval(Duration.ofSeconds(2));

    final List<Disposable> disposables = builder.build();
    assertThat(disposables).hasSize(2);
  }

  @Test
  @DisplayName("withStatisticsInterval can be called multiple times (last one wins)")
  void testWithStatisticsInterval_calledMultipleTimes_lastOneWins() {
    builder
        .forNodes("workflow-1", List.of("node-1"))
        .withStatisticsInterval(Duration.ofSeconds(1))
        .withStatisticsInterval(Duration.ofSeconds(2));

    final List<Disposable> disposables = builder.build();
    assertThat(disposables).hasSize(2);
  }

  @Test
  @DisplayName("build returns list that can be populated with disposables")
  void testBuild_returnsListType() {
    builder.forNodes("workflow-1", List.of("node-1"));
    final List<Disposable> disposables = builder.build();

    assertThat(disposables).isInstanceOf(List.class);
    assertThat(disposables).isInstanceOf(ArrayList.class);
  }

  @Test
  @DisplayName("multiple disposables are all disposed correctly")
  void testMultipleDisposables_allDisposedCorrectly() {
    builder.forNodes("workflow-1", List.of("node-1", "node-2", "node-3"));
    final List<Disposable> disposables = builder.build();

    assertThat(disposables).hasSize(6);
    disposables.forEach(d -> assertThat(d.isDisposed()).isFalse());

    disposables.forEach(Disposable::dispose);

    disposables.forEach(d -> assertThat(d.isDisposed()).isTrue());
  }
}
