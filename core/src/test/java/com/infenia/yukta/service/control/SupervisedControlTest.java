// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.StopMode;
import com.infenia.yukta.service.control.directive.DirectiveDispatcher;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.orchestrator.ExecutionResumer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Every operator entry reaches the gateway on the OPERATOR channel; engines and the system too. */
@NoArgsConstructor
class SupervisedControlTest {

  /** Gateway calls seen as {@code method:channel}. */
  private final List<String> seen = new ArrayList<>();

  private final ControlBusGateway gateway =
      mock(
          ControlBusGateway.class,
          Mockito.withSettings()
              .defaultAnswer(
                  call ->
                      Mono.deferContextual(
                          context -> {
                            seen.add(
                                call.getMethod().getName()
                                    + ":"
                                    + context.getOrDefault(ControlActor.CHANNEL_KEY, "none"));
                            return Mono.empty();
                          })));

  private final DirectiveDispatcher dispatcher =
      mock(
          DirectiveDispatcher.class,
          Mockito.withSettings()
              .defaultAnswer(
                  call ->
                      Mono.deferContextual(
                          context -> {
                            seen.add(
                                call.getMethod().getName()
                                    + ":"
                                    + context.getOrDefault(ControlActor.CHANNEL_KEY, "none"));
                            return Mono.empty();
                          })));

  private final ExecutionScheduler scheduler = mock(ExecutionScheduler.class);

  private final ExecutionResumer resumer = mock(ExecutionResumer.class);

  private final SupervisedControl control =
      new SupervisedControl(gateway, dispatcher, scheduler, resumer);

  @Test
  void drainIsReviewedAsAServerCommandAndRefusesStartsWhenApplied() {
    StepVerifier.create(control.drain(true)).verifyComplete();

    assertThat(seen).containsExactly("reviewedAdmin:OPERATOR");
    final var command = ArgumentCaptor.forClass(ExecutionControlCommand.class);
    final var action = ArgumentCaptor.forClass(java.util.function.Supplier.class);
    Mockito.verify(gateway).reviewedAdmin(command.capture(), Mockito.eq("Drain"), action.capture());
    assertThat(command.getValue()).isEqualTo(new ExecutionControlCommand.DrainCommand(true));
    StepVerifier.create((Mono<?>) action.getValue().get()).verifyComplete();
    Mockito.verify(scheduler).setDraining(true);
  }

  @Test
  void undrainAdmitsStartsAgain() {
    StepVerifier.create(control.drain(false)).verifyComplete();

    final var action = ArgumentCaptor.forClass(java.util.function.Supplier.class);
    Mockito.verify(gateway)
        .reviewedAdmin(
            Mockito.eq(new ExecutionControlCommand.DrainCommand(false)),
            Mockito.eq("Undrain"),
            action.capture());
    StepVerifier.create((Mono<?>) action.getValue().get()).verifyComplete();
    Mockito.verify(scheduler).setDraining(false);
  }

  @Test
  @SuppressWarnings("unchecked")
  void recoverIsReviewedAndContinuesTheAbortedRunAsANewOne() {
    Mockito.when(resumer.resume("old")).thenReturn(Mono.just("new"));

    StepVerifier.create(control.recover("old")).verifyComplete();

    assertThat(seen).containsExactly("reviewedAdmin:OPERATOR");
    final var action = ArgumentCaptor.forClass(java.util.function.Supplier.class);
    Mockito.verify(gateway)
        .reviewedAdmin(
            Mockito.eq(new ExecutionControlCommand.RecoverCommand("old")),
            Mockito.eq("Recover"),
            action.capture());
    StepVerifier.create(((Mono<Object>) action.getValue().get()))
        .expectNext("new")
        .verifyComplete();
  }

  @Test
  void everyOperatorEntryReachesTheGatewayAsAnOperator() {
    final Map<String, Function<SupervisedControl, Mono<?>>> entries =
        Map.ofEntries(
            Map.entry("startWorkflow", c -> c.startWorkflow("s", "w", Map.of(), "k")),
            Map.entry("pauseWorkflow", c -> c.pauseWorkflow("e")),
            Map.entry("resumeWorkflow", c -> c.resumeWorkflow("e")),
            Map.entry("pauseNode", c -> c.pauseNode("e", "n")),
            Map.entry("resumeNode", c -> c.resumeNode("e", "n")),
            Map.entry("stopNode", c -> c.stopNode("e", "n", true, "r")),
            Map.entry("skipNode", c -> c.skipNode("e", "n", true)),
            Map.entry("addBreakpoint", c -> c.addBreakpoint("e", "n", "payload", "x")),
            Map.entry("clearBreakpoint", c -> c.clearBreakpoint("e", "n")),
            Map.entry("enableStepMode", c -> c.enableStepMode("e", "n")),
            Map.entry("disableStepMode", c -> c.disableStepMode("e", "n")),
            Map.entry("stepNode", c -> c.stepNode("e", "n")),
            Map.entry("retryNode", c -> c.retryNode("e", "n")),
            Map.entry("stopWorkflow", c -> c.stopWorkflow("s", "w", "r", StopMode.DRAIN)),
            Map.entry("stopExecution", c -> c.stopExecution("e", "r", StopMode.DRAIN)),
            Map.entry("restartWorkflow", c -> c.restartWorkflow("e")),
            Map.entry("restartFromNode", c -> c.restartFromNode("e", "n")),
            Map.entry("changeLimits", c -> c.changeLimits(1, 1)),
            Map.entry("changePriority", c -> c.changePriority("e", 1)),
            Map.entry(
                "answerInput", c -> c.answerInput("e", "r", null, "yes", null, null, null, null)),
            Map.entry("escalateInput", c -> c.escalateInput("e", "r", "why", null)),
            Map.entry("recordAdminAction", c -> c.recordAdminAction("X", "t", true, null)));

    entries.values().forEach(call -> StepVerifier.create(call.apply(control)).verifyComplete());

    assertThat(seen)
        .containsExactlyInAnyOrderElementsOf(
            entries.keySet().stream().map(name -> name + ":OPERATOR").toList());
  }

  @Test
  void anEnginesCommandIsQueuedOnTheEngineChannel() {
    StepVerifier.create(control.engine(new ExecutionControlCommand.PauseWorkflowCommand("e")))
        .verifyComplete();
    assertThat(seen).containsExactly("dispatchOrdered:ENGINE");
  }

  @Test
  void theServersOwnStopIsOnTheSystemChannel() {
    StepVerifier.create(control.stopWorkflowAsSystem("s", "w", "Deleted", StopMode.CANCEL))
        .verifyComplete();
    assertThat(seen).containsExactly("stopWorkflow:SYSTEM");
  }
}
