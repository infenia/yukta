// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.control.OnLimit;
import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.security.AuditRecord;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.archive.ExecutionArchiveFixture;
import com.infenia.yukta.service.control.ControlBusService;
import com.infenia.yukta.service.control.ExecutionSchedulerFixture;
import com.infenia.yukta.service.control.store.InMemoryExecutionControlStore;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.orchestrator.WorkflowOrchestrator;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import com.infenia.yukta.service.supervisor.ControlRejectedException;
import com.infenia.yukta.service.supervisor.ControlReviewer;
import com.infenia.yukta.service.workflow.store.PreparedWorkflowCache;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** A start is a command: supervisors review it and the audit records it with its channel. */
@NoArgsConstructor
class DefaultControlBusGatewayStartTest {

  private final WorkflowDefinitionStore definitions = mock(WorkflowDefinitionStore.class);
  private final ControlReviewer reviewer = mock(ControlReviewer.class);
  private final List<AuditRecord> audit = new ArrayList<>();

  private DefaultControlBusGateway gateway() {
    final DefaultControlBusGateway gateway =
        new DefaultControlBusGateway(
            mock(ControlBusService.class),
            mock(DefaultTaskTrackerService.class),
            new InMemoryExecutionControlStore(),
            ExecutionArchiveFixture.create(),
            mock(WorkflowOrchestrator.class),
            definitions,
            mock(PreparedWorkflowCache.class),
            ExecutionSchedulerFixture.create(0, 0, 100, OnLimit.QUEUE, Duration.ofSeconds(30)),
            StartIdempotencyFixture.create(Duration.ofMinutes(10)),
            new InMemoryExecutionLogStore());
    gateway.setReviewer(reviewer);
    gateway.setAuditSink(audit::add);
    return gateway;
  }

  @Test
  void refusedStartNeverLoadsTheWorkflowAndIsAuditedWithItsChannel() {
    when(reviewer.review(any())).thenReturn(Mono.error(new ControlRejectedException("no starts")));

    StepVerifier.create(
            gateway()
                .startWorkflow("s", "w", Map.of(), null)
                .contextWrite(
                    Context.of(
                        ControlActor.CONTEXT_KEY, "key:ci", ControlActor.CHANNEL_KEY, "OPERATOR")))
        .expectError(ControlRejectedException.class)
        .verify();

    verify(definitions, never()).find(any(), any());
    assertThat(audit)
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.applied()).isFalse();
              assertThat(r.command()).isEqualTo("StartWorkflowCommand");
              assertThat(r.actor()).isEqualTo("key:ci");
              assertThat(r.channel()).isEqualTo("OPERATOR");
              assertThat(r.reason()).isEqualTo("no starts");
            });
  }

  @Test
  void theSupervisorsSeeTheStartWithItsWorkflowAsTarget() {
    when(reviewer.review(any())).thenReturn(Mono.empty());
    when(definitions.find("s", "w")).thenReturn(Mono.empty());

    StepVerifier.create(gateway().startWorkflow("s", "w", Map.of(), null))
        .expectError(NotFoundException.class)
        .verify();

    final ArgumentCaptor<ControlRequest> request = ArgumentCaptor.forClass(ControlRequest.class);
    verify(reviewer).review(request.capture());
    assertThat(request.getValue().command())
        .isInstanceOf(ExecutionControlCommand.StartWorkflowCommand.class);
    assertThat(request.getValue().sessionId()).isEqualTo("s");
    assertThat(request.getValue().workflowId()).isEqualTo("w");
    // no channel in the context: the server's own start
    assertThat(audit).singleElement().satisfies(r -> assertThat(r.channel()).isEqualTo("SYSTEM"));
  }
}
