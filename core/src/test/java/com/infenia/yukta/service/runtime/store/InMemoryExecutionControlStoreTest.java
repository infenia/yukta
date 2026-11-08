// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.runtime.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.service.runtime.ExecutionControl;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
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
        null,
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        new AtomicBoolean());
  }

  @Test
  void testSaveAndFindByExecutionId() {
    final ExecutionControl control = createControl("session-1", "workflow-1", "exec-1");
    store.save(control);

    final var found = store.findByExecutionId("exec-1");
    assertThat(found).isPresent().contains(control);
  }

  @Test
  void testRunSavedWhileTheOtherOneOfItsWorkflowEndsStaysFindableByWorkflow() {
    final ExecutionControl first = createControl("session-1", "workflow-1", "exec-1");
    final ExecutionControl second = createControl("session-1", "workflow-1", "exec-2");
    store.save(first);

    store.save(second);
    store.remove("exec-1");

    assertThat(store.findAllActiveByWorkflow("session-1", "workflow-1")).containsExactly(second);
    store.remove("exec-2");
    assertThat(store.findAllActiveByWorkflow("session-1", "workflow-1")).isEmpty();
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
  void testRemoveKeepsSiblingRunsOfTheWorkflow() {
    final ExecutionControl control1 = createControl("session-1", "workflow-1", "exec-1");
    final ExecutionControl control2 = createControl("session-1", "workflow-1", "exec-2");
    store.save(control1);
    store.save(control2);

    store.remove("exec-1");

    assertThat(store.findAllActiveByWorkflow("session-1", "workflow-1")).containsExactly(control2);
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

  @Test
  void testFindAllReturnsEveryExecution() {
    final ExecutionControl control1 = createControl("session-1", "workflow-1", "exec-1");
    final ExecutionControl control2 = createControl("session-2", "workflow-2", "exec-2");

    store.save(control1);
    store.save(control2);

    assertThat(store.findAll()).hasSize(2).contains(control1, control2);
  }

  @Test
  void testFindAllEmpty() {
    assertThat(store.findAll()).isEmpty();
  }

  @Test
  void testFindAllIsSnapshot() {
    final ExecutionControl control = createControl("session-1", "workflow-1", "exec-1");
    store.save(control);

    final var snapshot = store.findAll();
    store.remove("exec-1");

    assertThat(snapshot).containsExactly(control);
    assertThat(store.findAll()).isEmpty();
  }
}
