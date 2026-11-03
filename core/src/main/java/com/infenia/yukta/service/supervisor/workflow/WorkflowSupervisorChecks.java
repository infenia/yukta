// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.workflow;

import com.infenia.yukta.plugin.control.SupervisorProfile;
import com.infenia.yukta.service.session.InternalSessions;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Checks, when a {@code workflow} profile is saved, that its session is internal and its workflow
 * exists.
 */
@Component
@RequiredArgsConstructor
public class WorkflowSupervisorChecks {

  private final InternalSessions internal;

  private final WorkflowDefinitionStore definitions;

  /**
   * Checks a profile; any other type passes.
   *
   * @param profile the profile
   * @return completes if fine; errors with {@link IllegalArgumentException} otherwise
   */
  public Mono<Void> check(final SupervisorProfile profile) {
    if (!WorkflowSupervisorFactory.PROFILE_TYPE.equals(profile.type())) {
      return Mono.empty();
    }
    final String session = String.valueOf(profile.settings().get("sessionId"));
    final String workflow = String.valueOf(profile.settings().get("workflowId"));
    return internal
        .checkInternal(session)
        .filter(Boolean::booleanValue)
        .switchIfEmpty(
            Mono.error(
                new IllegalArgumentException(
                    "Session '"
                        + session
                        + "' is not internal (tag "
                        + InternalSessions.TAG
                        + "=true)")))
        .then(Mono.defer(() -> definitions.find(session, workflow)))
        .switchIfEmpty(
            Mono.error(
                new IllegalArgumentException(
                    "Workflow '" + workflow + "' not found in session '" + session + "'")))
        .then();
  }
}
