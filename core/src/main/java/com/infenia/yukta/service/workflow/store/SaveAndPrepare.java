// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow.store;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.control.CommandSequencer;
import java.util.Optional;
import java.util.function.Function;
import lombok.experimental.UtilityClass;
import reactor.core.publisher.Mono;

/**
 * Saves a workflow definition and prepares it, and puts the old definition back when preparing
 * fails, so the store and the prepared-workflow cache do not disagree.
 */
@UtilityClass
public class SaveAndPrepare {

  /**
   * Applies for the same workflow run one after another, so a failed apply puts back the definition
   * it replaced and not one that a concurrent apply saved in between. ponytail: within this server
   * only; with several servers a compare-and-set on the stored version would be needed.
   */
  private static final CommandSequencer SEQUENCE = new CommandSequencer();

  /**
   * Save the definition, then run {@code prepare} on it. If that fails the definition that was
   * current before is saved again (removed if there was none) and the failure is passed on; the
   * failed save stays in the list of versions.
   *
   * @param store the definition store
   * @param sessionId the session
   * @param definition the definition to make current
   * @param prepare compiles and caches the definition; its failure undoes the save
   * @return completes when the definition is saved and prepared
   */
  public static Mono<Void> run(
      final WorkflowDefinitionStore store,
      final String sessionId,
      final WorkflowDefinition definition,
      final Function<WorkflowDefinition, Mono<Void>> prepare) {
    return SEQUENCE.sequence(
        sessionId + "\0" + definition.workflowId(), apply(store, sessionId, definition, prepare));
  }

  private static Mono<Void> apply(
      final WorkflowDefinitionStore store,
      final String sessionId,
      final WorkflowDefinition definition,
      final Function<WorkflowDefinition, Mono<Void>> prepare) {
    return store
        .find(sessionId, definition.workflowId())
        .map(Optional::of)
        .defaultIfEmpty(Optional.empty())
        .flatMap(
            previous ->
                store
                    .save(sessionId, definition)
                    .then(Mono.defer(() -> prepare.apply(definition)))
                    .onErrorResume(
                        failure ->
                            restore(store, sessionId, definition.workflowId(), previous)
                                .onErrorResume(_ -> Mono.empty())
                                .then(Mono.error(failure))));
  }

  private static Mono<Void> restore(
      final WorkflowDefinitionStore store,
      final String sessionId,
      final String workflowId,
      final Optional<WorkflowDefinition> previous) {
    return previous.isPresent()
        ? store.save(sessionId, previous.get())
        : store.remove(sessionId, workflowId);
  }
}
