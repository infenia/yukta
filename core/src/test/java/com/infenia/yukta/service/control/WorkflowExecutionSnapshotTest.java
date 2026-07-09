// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.CommentRequired", "PMD.TooManyMethods"})
@DisplayName("WorkflowExecutionSnapshot Tests")
@NoArgsConstructor
class WorkflowExecutionSnapshotTest {

  @Test
  void testSnapshotCreation() {
    final Instant now = Instant.now();
    final WorkflowExecutionSnapshot snapshot =
        new WorkflowExecutionSnapshot(
            "exec-1",
            "session-1",
            "workflow-1",
            false,
            Set.of("node-1", "node-2"),
            Set.of("node-3"),
            Set.of(),
            now,
            now);

    assertThat(snapshot.executionId()).isEqualTo("exec-1");
    assertThat(snapshot.sessionId()).isEqualTo("session-1");
    assertThat(snapshot.workflowId()).isEqualTo("workflow-1");
    assertThat(snapshot.isGlobalPaused()).isFalse();
    assertThat(snapshot.pausedNodes()).containsExactlyInAnyOrder("node-1", "node-2");
    assertThat(snapshot.skippedNodes()).containsExactly("node-3");
    assertThat(snapshot.stoppedNodes()).isEmpty();
    assertThat(snapshot.createdAt()).isEqualTo(now);
    assertThat(snapshot.lastUpdatedAt()).isEqualTo(now);
  }

  @Test
  void testSnapshotGlobalPausedTrue() {
    final Instant now = Instant.now();
    final WorkflowExecutionSnapshot snapshot =
        new WorkflowExecutionSnapshot(
            "exec-1", "session-1", "workflow-1", true, Set.of(), Set.of(), Set.of(), now, now);

    assertThat(snapshot.isGlobalPaused()).isTrue();
  }

  @Test
  void testSnapshotEmptyNodeSets() {
    final Instant now = Instant.now();
    final WorkflowExecutionSnapshot snapshot =
        new WorkflowExecutionSnapshot(
            "exec-1", "session-1", "workflow-1", false, Set.of(), Set.of(), Set.of(), now, now);

    assertThat(snapshot.pausedNodes()).isEmpty();
    assertThat(snapshot.skippedNodes()).isEmpty();
    assertThat(snapshot.stoppedNodes()).isEmpty();
  }

  @Test
  void testSnapshotMultipleStoppedNodes() {
    final Instant now = Instant.now();
    final WorkflowExecutionSnapshot snapshot =
        new WorkflowExecutionSnapshot(
            "exec-1",
            "session-1",
            "workflow-1",
            false,
            Set.of(),
            Set.of(),
            Set.of("node-1", "node-2", "node-3"),
            now,
            now);

    assertThat(snapshot.stoppedNodes()).containsExactlyInAnyOrder("node-1", "node-2", "node-3");
  }

  @Test
  void testSnapshotAllNodesWithDifferentStates() {
    final Instant created = Instant.now().minusSeconds(60);
    final Instant updated = Instant.now();

    final WorkflowExecutionSnapshot snapshot =
        new WorkflowExecutionSnapshot(
            "exec-1",
            "session-1",
            "workflow-1",
            true,
            Set.of("node-1"),
            Set.of("node-2"),
            Set.of("node-3"),
            created,
            updated);

    assertThat(snapshot.isGlobalPaused()).isTrue();
    assertThat(snapshot.pausedNodes()).containsExactly("node-1");
    assertThat(snapshot.skippedNodes()).containsExactly("node-2");
    assertThat(snapshot.stoppedNodes()).containsExactly("node-3");
    assertThat(snapshot.createdAt()).isEqualTo(created);
    assertThat(snapshot.lastUpdatedAt()).isEqualTo(updated);
    assertThat(snapshot.createdAt()).isBefore(snapshot.lastUpdatedAt());
  }

  @Test
  void testSnapshotWithSameNodeInMultipleSets() {
    final Instant now = Instant.now();
    final WorkflowExecutionSnapshot snapshot =
        new WorkflowExecutionSnapshot(
            "exec-1",
            "session-1",
            "workflow-1",
            false,
            Set.of("node-1"),
            Set.of("node-1"),
            Set.of("node-1"),
            now,
            now);

    assertThat(snapshot.pausedNodes()).contains("node-1");
    assertThat(snapshot.skippedNodes()).contains("node-1");
    assertThat(snapshot.stoppedNodes()).contains("node-1");
  }

  @Test
  void testSnapshotRecordEquality() {
    final Instant now = Instant.now();
    final WorkflowExecutionSnapshot snapshot1 =
        new WorkflowExecutionSnapshot(
            "exec-1",
            "session-1",
            "workflow-1",
            false,
            Set.of("node-1"),
            Set.of("node-2"),
            Set.of(),
            now,
            now);

    final WorkflowExecutionSnapshot snapshot2 =
        new WorkflowExecutionSnapshot(
            "exec-1",
            "session-1",
            "workflow-1",
            false,
            Set.of("node-1"),
            Set.of("node-2"),
            Set.of(),
            now,
            now);

    assertThat(snapshot1).isEqualTo(snapshot2);
  }

  @Test
  void testSnapshotRecordInequalityDifferentExecution() {
    final Instant now = Instant.now();
    final WorkflowExecutionSnapshot snapshot1 =
        new WorkflowExecutionSnapshot(
            "exec-1", "session-1", "workflow-1", false, Set.of(), Set.of(), Set.of(), now, now);

    final WorkflowExecutionSnapshot snapshot2 =
        new WorkflowExecutionSnapshot(
            "exec-2", "session-1", "workflow-1", false, Set.of(), Set.of(), Set.of(), now, now);

    assertThat(snapshot1).isNotEqualTo(snapshot2);
  }

  @Test
  void testSnapshotToString() {
    final Instant now = Instant.now();
    final WorkflowExecutionSnapshot snapshot =
        new WorkflowExecutionSnapshot(
            "exec-1",
            "session-1",
            "workflow-1",
            false,
            Set.of("node-1"),
            Set.of(),
            Set.of(),
            now,
            now);

    final String str = snapshot.toString();
    assertThat(str).contains("exec-1");
    assertThat(str).contains("session-1");
    assertThat(str).contains("workflow-1");
  }

  @Test
  void testSnapshotHashCode() {
    final Instant now = Instant.now();
    final WorkflowExecutionSnapshot snapshot1 =
        new WorkflowExecutionSnapshot(
            "exec-1", "session-1", "workflow-1", false, Set.of(), Set.of(), Set.of(), now, now);

    final WorkflowExecutionSnapshot snapshot2 =
        new WorkflowExecutionSnapshot(
            "exec-1", "session-1", "workflow-1", false, Set.of(), Set.of(), Set.of(), now, now);

    assertThat(snapshot1.hashCode()).isEqualTo(snapshot2.hashCode());
  }

  @Test
  @DisplayName("Compact constructor makes defensive copies of sets")
  void testSnapshotDefensiveCopy_makesImmutableCopies() {
    final Instant now = Instant.now();
    final Set<String> pausedNodes = new HashSet<>(Set.of("node-1"));
    final Set<String> skippedNodes = new HashSet<>(Set.of("node-2"));
    final Set<String> stoppedNodes = new HashSet<>(Set.of("node-3"));

    final WorkflowExecutionSnapshot snapshot =
        new WorkflowExecutionSnapshot(
            "exec-1",
            "session-1",
            "workflow-1",
            true,
            pausedNodes,
            skippedNodes,
            stoppedNodes,
            now,
            now);

    assertThat(snapshot.pausedNodes()).isNotSameAs(pausedNodes);
    assertThat(snapshot.skippedNodes()).isNotSameAs(skippedNodes);
    assertThat(snapshot.stoppedNodes()).isNotSameAs(stoppedNodes);
    assertThat(snapshot.pausedNodes()).isEqualTo(pausedNodes);
    assertThat(snapshot.skippedNodes()).isEqualTo(skippedNodes);
    assertThat(snapshot.stoppedNodes()).isEqualTo(stoppedNodes);
  }

  @Test
  @DisplayName("Modifications to original set don't affect snapshot")
  void testSnapshotDefensiveCopy_originalSetModificationDoesNotAffect() {
    final Instant now = Instant.now();
    final Set<String> pausedNodes = new HashSet<>(Set.of("node-1"));

    final WorkflowExecutionSnapshot snapshot =
        new WorkflowExecutionSnapshot(
            "exec-1", "session-1", "workflow-1", false, pausedNodes, Set.of(), Set.of(), now, now);

    pausedNodes.add("node-2");
    pausedNodes.remove("node-1");

    assertThat(snapshot.pausedNodes()).containsExactly("node-1");
    assertThat(snapshot.pausedNodes()).doesNotContain("node-2");
  }

  @Test
  @DisplayName("Snapshot sets are immutable and cannot be modified")
  void testSnapshotSetImmutability_throwsUnsupportedOperationException() {
    final Instant now = Instant.now();
    final WorkflowExecutionSnapshot snapshot =
        new WorkflowExecutionSnapshot(
            "exec-1",
            "session-1",
            "workflow-1",
            false,
            Set.of("node-1"),
            Set.of(),
            Set.of(),
            now,
            now);

    assertThatThrownBy(() -> snapshot.pausedNodes().add("node-2"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("Null sets are converted to empty sets by compact constructor")
  void testSnapshotNullHandling_convertsNullToEmptySet() {
    final Instant now = Instant.now();
    final WorkflowExecutionSnapshot snapshot =
        new WorkflowExecutionSnapshot(
            "exec-1", "session-1", "workflow-1", false, null, null, null, now, now);

    assertThat(snapshot.pausedNodes()).isEmpty();
    assertThat(snapshot.skippedNodes()).isEmpty();
    assertThat(snapshot.stoppedNodes()).isEmpty();
  }

  @Test
  @DisplayName("Non-null empty sets are preserved as empty sets")
  void testSnapshotNullHandling_preservesEmptySets() {
    final Instant now = Instant.now();
    final Set<String> empty = Set.of();
    final WorkflowExecutionSnapshot snapshot =
        new WorkflowExecutionSnapshot(
            "exec-1", "session-1", "workflow-1", false, empty, empty, empty, now, now);

    assertThat(snapshot.pausedNodes()).isEmpty();
    assertThat(snapshot.skippedNodes()).isEmpty();
    assertThat(snapshot.stoppedNodes()).isEmpty();
  }

  @Test
  @DisplayName("All fields are accessible via record accessor methods")
  void testSnapshotAccessors_allFieldsAccessible() {
    final Instant now = Instant.now();
    final WorkflowExecutionSnapshot snapshot =
        new WorkflowExecutionSnapshot(
            "exec-123",
            "session-456",
            "workflow-789",
            true,
            Set.of("p1", "p2"),
            Set.of("s1"),
            Set.of("st1", "st2", "st3"),
            now,
            now);

    assertThat(snapshot.executionId()).isEqualTo("exec-123");
    assertThat(snapshot.sessionId()).isEqualTo("session-456");
    assertThat(snapshot.workflowId()).isEqualTo("workflow-789");
    assertThat(snapshot.isGlobalPaused()).isTrue();
    assertThat(snapshot.pausedNodes()).hasSize(2);
    assertThat(snapshot.skippedNodes()).hasSize(1);
    assertThat(snapshot.stoppedNodes()).hasSize(3);
    assertThat(snapshot.createdAt()).isEqualTo(now);
    assertThat(snapshot.lastUpdatedAt()).isEqualTo(now);
  }

  @Test
  @DisplayName("Different timestamps are properly stored")
  void testSnapshotTimestamps_storesBothCreatedAndUpdated() {
    final Instant created = Instant.parse("2026-01-01T00:00:00Z");
    final Instant updated = Instant.parse("2026-01-02T12:30:45Z");

    final WorkflowExecutionSnapshot snapshot =
        new WorkflowExecutionSnapshot(
            "exec-1",
            "session-1",
            "workflow-1",
            false,
            Set.of(),
            Set.of(),
            Set.of(),
            created,
            updated);

    assertThat(snapshot.createdAt()).isEqualTo(created);
    assertThat(snapshot.lastUpdatedAt()).isEqualTo(updated);
    assertThat(snapshot.createdAt()).isBefore(snapshot.lastUpdatedAt());
  }

  @Test
  @DisplayName("Record provides meaningful toString representation")
  void testSnapshotToStringRepresentation_containsAllFields() {
    final Instant now = Instant.now();
    final WorkflowExecutionSnapshot snapshot =
        new WorkflowExecutionSnapshot(
            "exec-1",
            "session-1",
            "workflow-1",
            false,
            Set.of("node-1"),
            Set.of(),
            Set.of(),
            now,
            now);

    final String str = snapshot.toString();
    assertThat(str)
        .contains("exec-1", "session-1", "workflow-1")
        .containsIgnoringCase("WorkflowExecutionSnapshot");
  }

  @Test
  @DisplayName("Record equality is based on all field values")
  void testSnapshotEquality_allFieldsAffectEquality() {
    final Instant now = Instant.now();

    final WorkflowExecutionSnapshot snapshot1 =
        new WorkflowExecutionSnapshot(
            "exec-1",
            "session-1",
            "workflow-1",
            true,
            Set.of("node-1"),
            Set.of("node-2"),
            Set.of("node-3"),
            now,
            now);

    final WorkflowExecutionSnapshot snapshot2 =
        new WorkflowExecutionSnapshot(
            "exec-1",
            "session-1",
            "workflow-1",
            false,
            Set.of("node-1"),
            Set.of("node-2"),
            Set.of("node-3"),
            now,
            now);

    assertThat(snapshot1).isNotEqualTo(snapshot2);
  }

  @Test
  @DisplayName("Record equality with different node sets")
  void testSnapshotEquality_differentNodeSetsMakeUnequal() {
    final Instant now = Instant.now();

    final WorkflowExecutionSnapshot snapshot1 =
        new WorkflowExecutionSnapshot(
            "exec-1",
            "session-1",
            "workflow-1",
            false,
            Set.of("node-1"),
            Set.of(),
            Set.of(),
            now,
            now);

    final WorkflowExecutionSnapshot snapshot2 =
        new WorkflowExecutionSnapshot(
            "exec-1",
            "session-1",
            "workflow-1",
            false,
            Set.of("node-2"),
            Set.of(),
            Set.of(),
            now,
            now);

    assertThat(snapshot1).isNotEqualTo(snapshot2);
  }

  @Test
  @DisplayName("Snapshot with mixed null and non-null node sets")
  void testSnapshotMixedNullHandling_nullAndNonNullSets() {
    final Instant now = Instant.now();
    final WorkflowExecutionSnapshot snapshot =
        new WorkflowExecutionSnapshot(
            "exec-1", "session-1", "workflow-1", false, null, Set.of("node-1"), null, now, now);

    assertThat(snapshot.pausedNodes()).isEmpty();
    assertThat(snapshot.skippedNodes()).containsExactly("node-1");
    assertThat(snapshot.stoppedNodes()).isEmpty();
  }

  @Test
  @DisplayName("Record hashCode is consistent for equal objects")
  void testSnapshotHashCode_consistentForEqualObjects() {
    final Instant now = Instant.now();
    final Set<String> nodes = Set.of("node-1");

    final WorkflowExecutionSnapshot snapshot1 =
        new WorkflowExecutionSnapshot(
            "exec-1", "session-1", "workflow-1", false, nodes, Set.of(), Set.of(), now, now);

    final WorkflowExecutionSnapshot snapshot2 =
        new WorkflowExecutionSnapshot(
            "exec-1", "session-1", "workflow-1", false, nodes, Set.of(), Set.of(), now, now);

    assertThat(snapshot1).isEqualTo(snapshot2);
    assertThat(snapshot1.hashCode()).isEqualTo(snapshot2.hashCode());
  }

  @Test
  @DisplayName("Record hashCode is different for unequal objects")
  void testSnapshotHashCode_differentForUnequalObjects() {
    final Instant now = Instant.now();

    final WorkflowExecutionSnapshot snapshot1 =
        new WorkflowExecutionSnapshot(
            "exec-1", "session-1", "workflow-1", false, Set.of(), Set.of(), Set.of(), now, now);

    final WorkflowExecutionSnapshot snapshot2 =
        new WorkflowExecutionSnapshot(
            "exec-2", "session-1", "workflow-1", false, Set.of(), Set.of(), Set.of(), now, now);

    assertThat(snapshot1).isNotEqualTo(snapshot2);
    assertThat(snapshot1.hashCode()).isNotEqualTo(snapshot2.hashCode());
  }

  @Test
  @DisplayName("Large node sets are properly copied and stored")
  void testSnapshotLargeNodeSets_handlesLargeSets() {
    final Instant now = Instant.now();
    final Set<String> largeSet =
        new HashSet<>(
            Set.of(
                "node-1", "node-2", "node-3", "node-4", "node-5", "node-6", "node-7", "node-8",
                "node-9", "node-10"));

    final WorkflowExecutionSnapshot snapshot =
        new WorkflowExecutionSnapshot(
            "exec-1", "session-1", "workflow-1", false, largeSet, Set.of(), Set.of(), now, now);

    assertThat(snapshot.pausedNodes()).hasSize(10);
    assertThat(snapshot.pausedNodes()).isNotSameAs(largeSet);
    assertThat(snapshot.pausedNodes()).containsAll(largeSet);
  }
}
