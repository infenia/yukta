// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.runtime;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.model.workflow.WorkflowAdmission;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.util.VariableResolver;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Works out which concurrency key a run claims ({@code admission.concurrency} of its workflow), by
 * resolving the key expression against the start payload in the usual expression sandbox.
 */
@Component
@RequiredArgsConstructor
public class AdmissionKeys {

  /** The stored definitions, for restarts and resumes, which do not carry theirs. */
  private final WorkflowDefinitionStore definitions;

  /** Resolves the key expression. */
  private final VariableResolver variables;

  /**
   * The claim of a run of a definition.
   *
   * @param definition the workflow
   * @param payload the run's start payload
   * @return the claim, or empty when the workflow has no key limit
   * @throws IllegalArgumentException (as the Mono's error) if the key resolves to nothing
   */
  public Mono<ExecutionScheduler.KeyClaim> claim(
      final WorkflowDefinition definition, final Map<String, Object> payload) {
    final WorkflowAdmission admission = definition.admission();
    final WorkflowAdmission.Concurrency concurrency =
        admission == null ? null : admission.concurrency();
    if (concurrency == null) {
      return Mono.empty();
    }
    return variables
        .resolve(concurrency.key(), DefaultMessage.create(UUID.randomUUID(), payload))
        .map(String::valueOf)
        .filter(key -> !key.isBlank())
        .switchIfEmpty(
            Mono.error(
                new IllegalArgumentException(
                    "admission.concurrency.key "
                        + concurrency.key()
                        + " resolves to nothing for this start")))
        .map(key ->
            new ExecutionScheduler.KeyClaim(key, concurrency.limit(), concurrency.overflow()));
  }

  /**
   * The claim of a restart or resume, from the workflow's stored definition: the limit in force
   * now, not when the run first started.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @param payload the run's start payload
   * @return the claim, or empty when the workflow has no key limit or is gone
   */
  public Mono<ExecutionScheduler.KeyClaim> claim(
      final String sessionId, final String workflowId, final Map<String, Object> payload) {
    return definitions.find(sessionId, workflowId).flatMap(def -> claim(def, payload));
  }
}
