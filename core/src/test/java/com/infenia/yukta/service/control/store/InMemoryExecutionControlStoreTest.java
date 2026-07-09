// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.service.control.ExecutionControl;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
class InMemoryExecutionControlStoreTest {

  private ExecutionControlStore store;

  @BeforeEach
  void setUp() {
    store = new InMemoryExecutionControlStore();
  }

  private ExecutionControl createControl(
      final String sessionId, final String workflowId, final String executionId) {
    return new ExecutionControl(
        sessionId,
        workflowId,
        executionId,
        null,
        Map.of(),
        Sinks.one(),
        Sinks.one(),
        null,
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of());
  }

  @Test
  void testSaveAndFindByExecutionId() {
    final ExecutionControl control = createControl("session-1", "workflow-1", "exec-1");
    store.save(control);

    final var found = store.findByExecutionId("exec-1");
    assertThat(found).isPresent().contains(control);
  }

  @Test
  void testFindByExecutionIdNotFound() {
    final var found = store.findByExecutionId("non-existent");
    assertThat(found).isEmpty();
  }

  @Test
  void testRemove() {
    final ExecutionControl control = createControl("session-1", "workflow-1", "exec-1");
    store.save(control);
    store.remove("exec-1");

    final var found = store.findByExecutionId("exec-1");
    assertThat(found).isEmpty();
  }

  @Test
  void testRemoveNonExistent() {
    store.remove("non-existent");
    assertThat(store.findByExecutionId("non-existent")).isEmpty();
  }

  @Test
  void testFindActiveByWorkflow() {
    final ExecutionControl control = createControl("session-1", "workflow-1", "exec-1");
    store.save(control);

    final var found = store.findActiveByWorkflow("session-1", "workflow-1");
    assertThat(found).isPresent().contains(control);
  }

  @Test
  void testFindActiveByWorkflowNotFound() {
    final var found = store.findActiveByWorkflow("session-1", "workflow-1");
    assertThat(found).isEmpty();
  }

  @Test
  void testFindActiveByWorkflowMultipleSessions() {
    final ExecutionControl control1 = createControl("session-1", "workflow-1", "exec-1");
    final ExecutionControl control2 = createControl("session-2", "workflow-1", "exec-2");

    store.save(control1);
    store.save(control2);

    final var found1 = store.findActiveByWorkflow("session-1", "workflow-1");
    final var found2 = store.findActiveByWorkflow("session-2", "workflow-1");

    assertThat(found1).isPresent().contains(control1);
    assertThat(found2).isPresent().contains(control2);
  }

  @Test
  void testMultipleExecutions() {
    final ExecutionControl control1 = createControl("session-1", "workflow-1", "exec-1");
    final ExecutionControl control2 = createControl("session-1", "workflow-1", "exec-2");

    store.save(control1);
    store.save(control2);

    assertThat(store.findByExecutionId("exec-1")).isPresent().contains(control1);
    assertThat(store.findByExecutionId("exec-2")).isPresent().contains(control2);
  }

  @Test
  void testSaveOverwrite() {
    final ExecutionControl control1 = createControl("session-1", "workflow-1", "exec-1");
    final ExecutionControl control2 = createControl("session-1", "workflow-2", "exec-1");

    store.save(control1);
    store.save(control2);

    final var found = store.findByExecutionId("exec-1");
    assertThat(found).isPresent().contains(control2);
  }

  @Test
  void testFindActiveByWorkflowSameSessionDifferentWorkflow() {
    final ExecutionControl control = createControl("session-1", "workflow-1", "exec-1");
    store.save(control);

    // Now query for same session but different workflow
    final var found = store.findActiveByWorkflow("session-1", "workflow-2");
    assertThat(found).isEmpty();
  }

  @Test
  void testFindActiveByWorkflowDifferentSessionSameWorkflow() {
    final ExecutionControl control = createControl("session-1", "workflow-1", "exec-1");
    store.save(control);

    // Now query for different session but same workflow
    final var found = store.findActiveByWorkflow("session-2", "workflow-1");
    assertThat(found).isEmpty();
  }

  @Test
  void testFindActiveByWorkflowDifferentSessionDifferentWorkflow() {
    final ExecutionControl control = createControl("session-1", "workflow-1", "exec-1");
    store.save(control);

    // Now query for different session and different workflow
    final var found = store.findActiveByWorkflow("session-2", "workflow-2");
    assertThat(found).isEmpty();
  }

  @Test
  void testThreadSafety() throws InterruptedException {
    final int numThreads = 10;
    final int executionsPerThread = 100;
    final Thread[] threads = new Thread[numThreads];

    for (int t = 0; t < numThreads; t++) {
      final int threadId = t;
      threads[t] =
          new Thread(
              () -> {
                for (int i = 0; i < executionsPerThread; i++) {
                  final String execId = "exec-" + threadId + "-" + i;
                  final ExecutionControl control = createControl("session-1", "workflow-1", execId);
                  store.save(control);
                  assertThat(store.findByExecutionId(execId)).isPresent();
                  store.remove(execId);
                  assertThat(store.findByExecutionId(execId)).isEmpty();
                }
              });
      threads[t].start();
    }

    for (final Thread thread : threads) {
      thread.join();
    }

    assertThat(store.findByExecutionId("exec-0-0")).isEmpty();
  }

  @Test
  void testFindAllActiveByWorkflow() {
    final ExecutionControl control1 = createControl("session-1", "workflow-1", "exec-1");
    final ExecutionControl control2 = createControl("session-1", "workflow-1", "exec-2");
    final ExecutionControl control3 = createControl("session-1", "workflow-2", "exec-3");

    store.save(control1);
    store.save(control2);
    store.save(control3);

    final var found = store.findAllActiveByWorkflow("session-1", "workflow-1");
    assertThat(found).hasSize(2).contains(control1, control2);
  }

  @Test
  void testFindAllActiveByWorkflowEmpty() {
    final var found = store.findAllActiveByWorkflow("session-1", "workflow-1");
    assertThat(found).isEmpty();
  }

  @Test
  void testFindAllActiveByWorkflowSingleExecution() {
    final ExecutionControl control = createControl("session-1", "workflow-1", "exec-1");
    store.save(control);

    final var found = store.findAllActiveByWorkflow("session-1", "workflow-1");
    assertThat(found).hasSize(1).contains(control);
  }

  @Test
  void testFindAllActiveByWorkflowDifferentSession() {
    final ExecutionControl control1 = createControl("session-1", "workflow-1", "exec-1");
    final ExecutionControl control2 = createControl("session-2", "workflow-1", "exec-2");

    store.save(control1);
    store.save(control2);

    final var found = store.findAllActiveByWorkflow("session-1", "workflow-1");
    assertThat(found).hasSize(1).contains(control1);
  }

  @Test
  void testFindAllActiveByWorkflowDifferentWorkflow() {
    final ExecutionControl control1 = createControl("session-1", "workflow-1", "exec-1");
    final ExecutionControl control2 = createControl("session-1", "workflow-2", "exec-2");

    store.save(control1);
    store.save(control2);

    final var found = store.findAllActiveByWorkflow("session-1", "workflow-1");
    assertThat(found).hasSize(1).contains(control1);
  }
}
