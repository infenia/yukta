// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.workflow;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.control.SupervisorProfile;
import com.infenia.yukta.service.session.InternalSessions;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class WorkflowSupervisorChecksTest {

  private final InternalSessions internal = mock(InternalSessions.class);

  private final WorkflowDefinitionStore definitions = mock(WorkflowDefinitionStore.class);

  private final WorkflowSupervisorChecks checks =
      new WorkflowSupervisorChecks(internal, definitions);

  private static SupervisorProfile profile() {
    return new SupervisorProfile(
        "p", "workflow", Map.of("sessionId", "sys", "workflowId", "wf"), false);
  }

  @Test
  void otherTypesPass() {
    StepVerifier.create(
            checks.check(new SupervisorProfile("p", "rule-supervisor", Map.of(), false)))
        .verifyComplete();
  }

  @Test
  void theSessionMustBeInternalAndTheWorkflowMustExist() {
    when(internal.checkInternal("sys")).thenReturn(Mono.just(false));
    StepVerifier.create(checks.check(profile()))
        .expectErrorMessage("Session 'sys' is not internal (tag yukta.internal=true)")
        .verify();

    when(internal.checkInternal("sys")).thenReturn(Mono.just(true));
    when(definitions.find("sys", "wf")).thenReturn(Mono.empty());
    StepVerifier.create(checks.check(profile()))
        .expectErrorMessage("Workflow 'wf' not found in session 'sys'")
        .verify();

    when(definitions.find("sys", "wf"))
        .thenReturn(Mono.just(new WorkflowDefinition("wf", "d", List.of(), List.of())));
    StepVerifier.create(checks.check(profile())).verifyComplete();
  }
}
