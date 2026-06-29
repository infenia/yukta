/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
}
