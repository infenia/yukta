// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.control.PendingApproval;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.AnswerInputCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.EscalateInputCommand;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.util.SignalWaits;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.archive.ExecutionArchive;
import com.infenia.yukta.service.control.ControlBusService;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.ExecutionScheduler;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import com.infenia.yukta.service.control.valve.ReactiveControlValve;
import com.infenia.yukta.service.input.InputBroker;
import com.infenia.yukta.service.input.InputTestSupport;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.orchestrator.WorkflowOrchestrator;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import com.infenia.yukta.service.supervisor.ControlRejectedException;
import com.infenia.yukta.service.workflow.store.PreparedWorkflowCache;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Answering and handing up input requests through the gateway, and listing the pending ones. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.ExcessiveImports",
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyStaticImports"
})
class DefaultControlBusGatewayInputTest {

  private final ControlBusService controlBusService = mock(ControlBusService.class);

  private final DefaultTaskTrackerService taskTracker = mock(DefaultTaskTrackerService.class);

  private final ExecutionControlStore store = mock(ExecutionControlStore.class);

  private final InputBroker broker = mock(InputBroker.class);

  private final DefaultControlBusGateway gateway =
      new DefaultControlBusGateway(
          controlBusService,
          taskTracker,
          store,
          new ExecutionArchive(),
          mock(WorkflowOrchestrator.class),
          mock(WorkflowDefinitionStore.class),
          mock(PreparedWorkflowCache.class),
          new ExecutionScheduler(
              0, 0, 100, ExecutionScheduler.OnLimit.QUEUE, Duration.ofSeconds(30)),
          new StartIdempotency(Duration.ofMinutes(10)),
          new InMemoryExecutionLogStore());

  @SuppressWarnings("unchecked")
  private void withBroker() {
    final ObjectProvider<InputBroker> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(broker);
    gateway.setInputBroker(provider);
  }

  private void runExists(final String executionId) {
    final ExecutionControl control = mock(ExecutionControl.class);
    when(control.nodePauseValves()).thenReturn(Map.of("n", mock(ReactiveControlValve.class)));
    when(store.findByExecutionId(executionId)).thenReturn(Optional.of(control));
  }

  private static InputRequest approval(
      final String id, final String key, final Map<String, Object> context) {
    return InputTestSupport.request(
        id,
        InputTestSupport.spec(
            key,
            InputKind.APPROVAL,
            InputRisk.LOW,
            Audience.CURRENT_TIER,
            List.of(),
            null,
            context));
  }

  @Test
  void answerInputEmitsTheAnswerCommand() {
    runExists("exec");
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    StepVerifier.create(
            gateway.answerInput("exec", "r1", 3L, "approve", "text", Map.of("a", 1), "why", 0.5))
        .verifyComplete();

    final ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    assertThat(captor.getValue().payload())
        .isEqualTo(
            new AnswerInputCommand(
                "exec", "r1", 3L, "approve", "text", Map.of("a", 1), "why", 0.5));
  }

  @Test
  void escalateInputEmitsTheEscalateCommand() {
    runExists("exec");
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    StepVerifier.create(gateway.escalateInput("exec", "r1", "needs a person", "careful"))
        .verifyComplete();

    final ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    assertThat(captor.getValue().payload())
        .isEqualTo(new EscalateInputCommand("exec", "r1", "needs a person", "careful"));
  }

  @Test
  void answeringOrHandingUpForAnUnknownRunIsNotFound() {
    when(store.findByExecutionId("gone")).thenReturn(Optional.empty());

    StepVerifier.create(gateway.answerInput("gone", "r1", null, "go", null, null, null, null))
        .expectError(NotFoundException.class)
        .verify();
    StepVerifier.create(gateway.escalateInput("gone", "r1", "why", null))
        .expectError(NotFoundException.class)
        .verify();
  }

  @Test
  void rawCommandPayloadThatCarriesRequestIdIsRefused() {
    final Message<?> raw = DefaultMessage.create(null, Map.of("requestId", "r1", "text", "x"));

    StepVerifier.create(gateway.sendCommand("s", "wf", null, "n", raw))
        .expectError(ControlRejectedException.class)
        .verify();
  }

  @Test
  void pendingApprovalsAlsoListsOpenApprovalRequestsOnce() {
    withBroker();
    final Disposable wait =
        SignalWaits.shared().await("exec", "node", "gate", Duration.ofMinutes(1)).subscribe();
    final WorkflowProgress progress = mock(WorkflowProgress.class);
    when(progress.sessionId()).thenReturn("session");
    when(progress.workflowId()).thenReturn("workflow");
    when(taskTracker.getProgressByExecutionId("exec")).thenReturn(progress);
    final InputRequest named =
        approval("r1", "key-1", Map.of(InputBroker.SIGNAL_CONTEXT_KEY, "gate"));
    final InputRequest unnamed = approval("r2", "key-2", Map.of());
    final InputRequest text = InputTestSupport.request("r3", InputTestSupport.spec("key-3"));
    when(broker.listOpen("session", null)).thenReturn(Flux.just(named, unnamed, text));

    StepVerifier.create(gateway.pendingApprovals("session"))
        // the signal wait, then the request without a signal name; the named one is the same wait
        .assertNext(approval -> assertThat(approval.name()).isEqualTo("gate"))
        .assertNext(
            approval -> {
              assertThat(approval).extracting(PendingApproval::name).isEqualTo("key-2");
              assertThat(approval.sessionId()).isEqualTo("session");
              assertThat(approval.workflowId()).isEqualTo("workflow");
              assertThat(approval.since()).isEqualTo(unnamed.createdAt());
            })
        .verifyComplete();
    wait.dispose();
  }

  @Test
  void withoutBrokerOnlyTheSignalWaitsAreListed() {
    @SuppressWarnings("unchecked")
    final ObjectProvider<InputBroker> none = mock(ObjectProvider.class);
    gateway.setInputBroker(none);

    StepVerifier.create(gateway.pendingApprovals("session-none")).verifyComplete();
  }
}
