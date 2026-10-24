// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Tests for the behaviour described by {@link SaveAndPrepare}. */
@NoArgsConstructor
class SaveAndPrepareTest {

  /** The session used by every test. */
  private static final String SESSION = "s";

  /** The message of the failure the tests make prepare throw. */
  private static final String UNKNOWN_TYPE = "unknown plugin type";

  /** The workflow used by every test. */
  private static final String WORKFLOW = "w";

  /** The store the helper works on. */
  private InMemoryWorkflowDefinitionStore store;

  private static WorkflowDefinition definition(final String description) {
    return new WorkflowDefinition(
        WORKFLOW,
        description,
        List.of(new WorkflowDefinition.Node("n1", "trigger", Map.of())),
        List.of());
  }

  @BeforeEach
  void setUp() {
    store = new InMemoryWorkflowDefinitionStore();
  }

  @Test
  void savesTheDefinitionAndPreparesIt() {
    final var prepared = new java.util.ArrayList<WorkflowDefinition>();
    final WorkflowDefinition next = definition("new");

    StepVerifier.create(
            SaveAndPrepare.run(
                store,
                SESSION,
                next,
                saved -> {
                  prepared.add(saved);
                  return Mono.empty();
                }))
        .verifyComplete();

    assertThat(prepared).containsExactly(next);
    StepVerifier.create(store.find(SESSION, WORKFLOW)).expectNext(next).verifyComplete();
  }

  @Test
  void failedPrepareRestoresThePreviousDefinition() {
    final WorkflowDefinition old = definition("old");
    store.save(SESSION, old).block();

    StepVerifier.create(
            SaveAndPrepare.run(
                store,
                SESSION,
                definition("broken"),
                _ -> Mono.error(new IllegalStateException(UNKNOWN_TYPE))))
        .verifyErrorMessage(UNKNOWN_TYPE);

    StepVerifier.create(store.find(SESSION, WORKFLOW)).expectNext(old).verifyComplete();
  }

  @Test
  void failedPrepareOfFirstSaveLeavesNothingBehind() {
    StepVerifier.create(
            SaveAndPrepare.run(
                store,
                SESSION,
                definition("broken"),
                _ -> Mono.error(new IllegalStateException(UNKNOWN_TYPE))))
        .verifyErrorMessage(UNKNOWN_TYPE);

    StepVerifier.create(store.find(SESSION, WORKFLOW)).verifyComplete();
  }

  @Test
  void failingRestoreDoesNotHideTheOriginalFailure() {
    final WorkflowDefinitionStore failing =
        new InMemoryWorkflowDefinitionStore() {
          /** Saves made so far: the third one (the restore) fails. */
          private final AtomicInteger saves = new AtomicInteger();

          @Override
          public Mono<Void> save(final String sessionId, final WorkflowDefinition definition) {
            return saves.incrementAndGet() > 2
                ? Mono.error(new IllegalStateException("disk full"))
                : super.save(sessionId, definition);
          }
        };
    failing.save(SESSION, definition("old")).block();

    StepVerifier.create(
            SaveAndPrepare.run(
                failing,
                SESSION,
                definition("broken"),
                _ -> Mono.error(new IllegalStateException(UNKNOWN_TYPE))))
        .verifyErrorMessage(UNKNOWN_TYPE);
  }
}
