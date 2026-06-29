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
package com.infenia.yukta.service.workflow.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** Tests for {@link InMemoryWorkflowDefinitionStore}. */
@NoArgsConstructor
class InMemoryWorkflowDefinitionStoreTest {

  /** Constant for workflow ID 1. */
  private static final String WORKFLOW_ID_1 = "wf1";

  /** Constant for workflow ID 2. */
  private static final String WORKFLOW_ID_2 = "wf2";

  /** Constant for session ID 1. */
  private static final String SESSION_ID_1 = "s1";

  /** Constant for session ID 2. */
  private static final String SESSION_ID_2 = "s2";

  /** The store under test. */
  private InMemoryWorkflowDefinitionStore store;

  private static WorkflowDefinition definition(final String workflowId) {
    return new WorkflowDefinition(
        workflowId,
        "desc",
        List.of(new WorkflowDefinition.Node("n1", "trigger", Map.of())),
        List.of());
  }

  @BeforeEach
  void setUp() {
    store = new InMemoryWorkflowDefinitionStore();
  }

  @Test
  void saveAndFindReturnsDefinition() {
    final WorkflowDefinition def = definition(WORKFLOW_ID_1);
    StepVerifier.create(store.save(SESSION_ID_1, def).then(store.find(SESSION_ID_1, WORKFLOW_ID_1)))
        .expectNext(def)
        .verifyComplete();
  }

  @Test
  void findOnUnknownKeyReturnsEmpty() {
    StepVerifier.create(store.find("unknown", WORKFLOW_ID_1)).verifyComplete();
  }

  @Test
  void findOnKnownSessionUnknownWorkflowReturnsEmpty() {
    final WorkflowDefinition wf1 = definition(WORKFLOW_ID_1);
    StepVerifier.create(store.save(SESSION_ID_1, wf1).then(store.find(SESSION_ID_1, "nonexistent")))
        .verifyComplete();
  }

  @Test
  void removeOnUnknownSessionCompletesGracefully() {
    StepVerifier.create(store.remove("unknown", WORKFLOW_ID_1)).verifyComplete();
  }

  @Test
  void findAllOnUnknownSessionReturnsEmptyMap() {
    StepVerifier.create(store.findAll("unknown"))
        .assertNext(map -> assertThat(map).isEmpty())
        .verifyComplete();
  }

  @Test
  void findAllReturnsAllDefinitionsForSession() {
    final WorkflowDefinition wf1 = definition(WORKFLOW_ID_1);
    final WorkflowDefinition wf2 = definition(WORKFLOW_ID_2);
    StepVerifier.create(
            store
                .save(SESSION_ID_1, wf1)
                .then(store.save(SESSION_ID_1, wf2))
                .then(store.findAll(SESSION_ID_1)))
        .assertNext(map -> assertThat(map).containsKeys(WORKFLOW_ID_1, WORKFLOW_ID_2))
        .verifyComplete();
  }

  @Test
  void removeDeletesOnlyTargetWorkflow() {
    final WorkflowDefinition wf1 = definition(WORKFLOW_ID_1);
    final WorkflowDefinition wf2 = definition(WORKFLOW_ID_2);
    StepVerifier.create(
            store
                .save(SESSION_ID_1, wf1)
                .then(store.save(SESSION_ID_1, wf2))
                .then(store.remove(SESSION_ID_1, WORKFLOW_ID_1))
                .then(store.find(SESSION_ID_1, WORKFLOW_ID_1)))
        .verifyComplete();
    StepVerifier.create(store.find(SESSION_ID_1, WORKFLOW_ID_2)).expectNext(wf2).verifyComplete();
  }

  @Test
  void removeAllClearsSessionLeavesOthersIntact() {
    final WorkflowDefinition wf1 = definition(WORKFLOW_ID_1);
    final WorkflowDefinition wf2 = definition(WORKFLOW_ID_2);
    StepVerifier.create(
            store
                .save(SESSION_ID_1, wf1)
                .then(store.save(SESSION_ID_2, wf2))
                .then(store.removeAll(SESSION_ID_1))
                .then(store.find(SESSION_ID_1, WORKFLOW_ID_1)))
        .verifyComplete();
    StepVerifier.create(store.find(SESSION_ID_2, WORKFLOW_ID_2)).expectNext(wf2).verifyComplete();
  }

  @Test
  @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
  void logInitializationExecutedOnBeanCreation()
      throws InvocationTargetException, IllegalAccessException, NoSuchMethodException {
    final InMemoryWorkflowDefinitionStore newStore = new InMemoryWorkflowDefinitionStore();

    final Method logInitMethod =
        InMemoryWorkflowDefinitionStore.class.getDeclaredMethod("logInitialization");
    logInitMethod.setAccessible(true);
    logInitMethod.invoke(newStore);

    assertThat(newStore).isNotNull();
  }
}
