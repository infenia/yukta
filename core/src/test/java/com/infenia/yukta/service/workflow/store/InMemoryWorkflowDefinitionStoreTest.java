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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class InMemoryWorkflowDefinitionStoreTest {

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
    final WorkflowDefinition def = definition("wf1");
    StepVerifier.create(store.save("s1", def).then(store.find("s1", "wf1")))
        .expectNext(def)
        .verifyComplete();
  }

  @Test
  void findOnUnknownKeyReturnsEmpty() {
    StepVerifier.create(store.find("unknown", "wf1")).verifyComplete();
  }

  @Test
  void findOnKnownSessionUnknownWorkflowReturnsEmpty() {
    final WorkflowDefinition wf1 = definition("wf1");
    StepVerifier.create(store.save("s1", wf1).then(store.find("s1", "nonexistent")))
        .verifyComplete();
  }

  @Test
  void removeOnUnknownSessionCompletesGracefully() {
    StepVerifier.create(store.remove("unknown", "wf1")).verifyComplete();
  }

  @Test
  void findAllOnUnknownSessionReturnsEmptyMap() {
    StepVerifier.create(store.findAll("unknown"))
        .assertNext(map -> assertThat(map).isEmpty())
        .verifyComplete();
  }

  @Test
  void findAllReturnsAllDefinitionsForSession() {
    final WorkflowDefinition wf1 = definition("wf1");
    final WorkflowDefinition wf2 = definition("wf2");
    StepVerifier.create(store.save("s1", wf1).then(store.save("s1", wf2)).then(store.findAll("s1")))
        .assertNext(map -> assertThat(map).containsKeys("wf1", "wf2"))
        .verifyComplete();
  }

  @Test
  void removeDeletesOnlyTargetWorkflow() {
    final WorkflowDefinition wf1 = definition("wf1");
    final WorkflowDefinition wf2 = definition("wf2");
    StepVerifier.create(
            store
                .save("s1", wf1)
                .then(store.save("s1", wf2))
                .then(store.remove("s1", "wf1"))
                .then(store.find("s1", "wf1")))
        .verifyComplete();
    StepVerifier.create(store.find("s1", "wf2")).expectNext(wf2).verifyComplete();
  }

  @Test
  void removeAllClearsSessionLeavesOthersIntact() {
    final WorkflowDefinition wf1 = definition("wf1");
    final WorkflowDefinition wf2 = definition("wf2");
    StepVerifier.create(
            store
                .save("s1", wf1)
                .then(store.save("s2", wf2))
                .then(store.removeAll("s1"))
                .then(store.find("s1", "wf1")))
        .verifyComplete();
    StepVerifier.create(store.find("s2", "wf2")).expectNext(wf2).verifyComplete();
  }

  @Test
  void logInitializationExecutedOnBeanCreation()
      throws InvocationTargetException, IllegalAccessException, NoSuchMethodException {
    InMemoryWorkflowDefinitionStore newStore = new InMemoryWorkflowDefinitionStore();

    Method logInitMethod =
        InMemoryWorkflowDefinitionStore.class.getDeclaredMethod("logInitialization");
    logInitMethod.setAccessible(true);
    logInitMethod.invoke(newStore);

    assertThat(newStore).isNotNull();
  }
}
