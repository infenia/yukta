// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
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
@SuppressWarnings("PMD.TooManyMethods")
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

  private static WorkflowDefinition version(final String description) {
    return new WorkflowDefinition(
        WORKFLOW_ID_1,
        description,
        List.of(new WorkflowDefinition.Node("n1", "trigger", Map.of())),
        List.of());
  }

  @Test
  void distinctSavesAreVersions_newestFirst_andFetchable() throws InterruptedException {
    store.save(SESSION_ID_1, version("one")).block();
    store.save(SESSION_ID_1, version("one")).block();
    Thread.sleep(2);
    store.save(SESSION_ID_1, version("two")).block();

    final List<DefinitionRevision> revisions =
        store.revisions(SESSION_ID_1, WORKFLOW_ID_1).collectList().block();

    assertThat(revisions).hasSize(2);
    assertThat(store.findRevision(SESSION_ID_1, WORKFLOW_ID_1, revisions.get(0).id()).block())
        .isEqualTo(version("two"));
    assertThat(store.findRevision(SESSION_ID_1, WORKFLOW_ID_1, revisions.get(1).id()).block())
        .isEqualTo(version("one"));
    assertThat(store.findRevision(SESSION_ID_1, WORKFLOW_ID_1, "nope").block()).isNull();
    assertThat(store.revisions(SESSION_ID_2, WORKFLOW_ID_1).collectList().block()).isEmpty();
  }

  @Test
  void onlyTheNewestVersionsAreKept_andRemovingDropsTheHistory() throws InterruptedException {
    for (int i = 0; i < InMemoryWorkflowDefinitionStore.MAX_REVISIONS + 5; i++) {
      store.save(SESSION_ID_1, version("v" + i)).block();
      Thread.sleep(1);
    }
    assertThat(store.revisions(SESSION_ID_1, WORKFLOW_ID_1).collectList().block())
        .hasSize(InMemoryWorkflowDefinitionStore.MAX_REVISIONS);

    store.remove(SESSION_ID_1, WORKFLOW_ID_1).block();
    assertThat(store.revisions(SESSION_ID_1, WORKFLOW_ID_1).collectList().block()).isEmpty();

    store.save(SESSION_ID_1, version("again")).block();
    store.removeAll(SESSION_ID_1).block();
    assertThat(store.revisions(SESSION_ID_1, WORKFLOW_ID_1).collectList().block()).isEmpty();
  }

  @Test
  void noteShowsOnVersion_blankClearsIt_unknownIsNotFound() {
    store.save(SESSION_ID_1, version("noted")).block();
    final String revisionId =
        java.util.Objects.requireNonNull(store.revisions(SESSION_ID_1, WORKFLOW_ID_1).blockFirst())
            .id();

    store.saveNote(SESSION_ID_1, WORKFLOW_ID_1, revisionId, "added the audit sink").block();
    assertThat(store.revisions(SESSION_ID_1, WORKFLOW_ID_1).blockFirst().note())
        .isEqualTo("added the audit sink");

    store.saveNote(SESSION_ID_1, WORKFLOW_ID_1, revisionId, " ").block();
    assertThat(store.revisions(SESSION_ID_1, WORKFLOW_ID_1).blockFirst().note()).isNull();

    StepVerifier.create(store.saveNote(SESSION_ID_1, WORKFLOW_ID_1, "nope", "x"))
        .expectError(com.infenia.yukta.service.NotFoundException.class)
        .verify();
  }

  @Test
  void nullNoteClearsIt_noteGoesWithPrunedVersion() throws InterruptedException {
    store.save(SESSION_ID_1, version("first")).block();
    final String revisionId =
        java.util.Objects.requireNonNull(store.revisions(SESSION_ID_1, WORKFLOW_ID_1).blockFirst())
            .id();
    store.saveNote(SESSION_ID_1, WORKFLOW_ID_1, revisionId, "keep me").block();
    store.saveNote(SESSION_ID_1, WORKFLOW_ID_1, revisionId, null).block();
    assertThat(store.revisions(SESSION_ID_1, WORKFLOW_ID_1).blockFirst().note()).isNull();

    store.saveNote(SESSION_ID_1, WORKFLOW_ID_1, revisionId, "old note").block();
    for (int i = 0; i < InMemoryWorkflowDefinitionStore.MAX_REVISIONS; i++) {
      Thread.sleep(2);
      store.save(SESSION_ID_1, version("v" + i)).block();
    }
    assertThat(
            store
                .revisions(SESSION_ID_1, WORKFLOW_ID_1)
                .map(DefinitionRevision::id)
                .collectList()
                .block())
        .doesNotContain(revisionId);
  }
}
