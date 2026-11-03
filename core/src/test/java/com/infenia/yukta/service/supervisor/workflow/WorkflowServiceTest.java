// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.SupervisorBinding;
import com.infenia.yukta.plugin.control.SupervisorProfile;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.redact.InputRedactor;
import com.infenia.yukta.service.session.InternalSessions;
import com.infenia.yukta.service.supervisor.BoundSupervisor;
import com.infenia.yukta.service.supervisor.InMemorySupervisorStore;
import com.infenia.yukta.service.supervisor.SupervisorHost;
import com.infenia.yukta.service.supervisor.SupervisorService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SuppressWarnings("PMD.TooManyStaticImports")
class WorkflowServiceTest {

  private final SupervisorHost host = mock(SupervisorHost.class);

  private final SupervisorWorkflowRunner runner = mock(SupervisorWorkflowRunner.class);

  private final WorkflowSupervisorChecks checks = mock(WorkflowSupervisorChecks.class);

  private SupervisorService service() {
    when(runner.reservedSlots()).thenReturn(4);
    final SupervisorService service =
        new SupervisorService(
            new InMemorySupervisorStore(),
            List.of(
                new WorkflowSupervisorFactory(
                    runner, mock(InternalSessions.class), new InputRedactor(List.of()))),
            host,
            new InMemoryExecutionLogStore());
    service.setWorkflowChecks(checks);
    return service;
  }

  private static SupervisorProfile profile() {
    return new SupervisorProfile(
        "wfp",
        "workflow",
        Map.of("sessionId", "sys", "workflowId", "wf", "events", List.of("InputRequested")),
        false);
  }

  @Test
  void profileFailingChecksIsNotSaved() {
    when(checks.check(any())).thenReturn(Mono.error(new IllegalArgumentException("not internal")));

    StepVerifier.create(service().saveProfile(profile()))
        .expectErrorMessage("not internal")
        .verify();
  }

  @Test
  void theBindingIdBecomesTheSupervisorsActor() {
    when(checks.check(any())).thenReturn(Mono.empty());
    final SupervisorService service = service();
    service.saveProfile(profile()).block();

    service
        .attach(new SupervisorBinding("bind-1", "wfp", new SupervisorScope.Global(), true))
        .block();

    final ArgumentCaptor<BoundSupervisor> bound = ArgumentCaptor.forClass(BoundSupervisor.class);
    verify(host).attach(bound.capture());
    assertThat(bound.getValue().delegate()).isInstanceOf(WorkflowSupervisor.class);
    assertThat(bound.getValue().id()).isEqualTo("bind-1");
  }
}
