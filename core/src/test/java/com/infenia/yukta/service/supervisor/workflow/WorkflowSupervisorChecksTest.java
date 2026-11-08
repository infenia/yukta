// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.workflow;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.service.sessionstore.InternalSessions;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import com.infenia.yukta.testing.Fixtures;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class WorkflowSupervisorChecksTest {

  private final InternalSessions internal = mock(InternalSessions.class);

  private final WorkflowDefinitionStore definitions = mock(WorkflowDefinitionStore.class);

  private final WorkflowSupervisorChecks checks =
      new WorkflowSupervisorChecks(internal, definitions);

  private static Supervisor supervisor() {
    return new Supervisor("p", "workflow", Map.of("sessionId", "sys", "workflowId", "wf"), false);
  }

  @Test
  void otherTypesPass() {
    StepVerifier.create(checks.check(new Supervisor("p", "rule-supervisor", Map.of(), false)))
        .verifyComplete();
  }

  @Test
  void theSessionMustBeInternalAndTheWorkflowMustExist() {
    when(internal.checkInternal("sys")).thenReturn(Mono.just(false));
    StepVerifier.create(checks.check(supervisor()))
        .expectErrorMessage("Session 'sys' is not internal (tag yukta.internal=true)")
        .verify();

    when(internal.checkInternal("sys")).thenReturn(Mono.just(true));
    when(definitions.find("sys", "wf")).thenReturn(Mono.empty());
    StepVerifier.create(checks.check(supervisor()))
        .expectErrorMessage("Workflow 'wf' not found in session 'sys'")
        .verify();

    when(definitions.find("sys", "wf"))
        .thenReturn(Mono.just(Fixtures.workflow("wf", "d", List.of(), List.of())));
    StepVerifier.create(checks.check(supervisor())).verifyComplete();
  }
}
