// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SignalNodeCommand;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputForbiddenException;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.security.ApiRole;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ActivePluginRegistry;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import com.infenia.yukta.service.input.InputBroker;
import com.infenia.yukta.service.input.InputFlow;
import com.infenia.yukta.service.input.InputTestSupport;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** A signal that stands for an open input request is answered through the authoriser. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.ExcessiveImports"})
class SignalNodeCommandProcessorBridgeTest {

  private static final SignalNodeCommand SIGNAL =
      new SignalNodeCommand("exec", "node", "approval", "approve", "fine");

  private final InputFlow flow = new InputFlow();

  private final ExecutionControlStore registry = mock(ExecutionControlStore.class);

  private final ActivePluginRegistry plugins = mock(ActivePluginRegistry.class);

  private final Plugin plugin = mock(Plugin.class);

  private final SignalNodeCommandProcessor processor =
      new SignalNodeCommandProcessor(registry, plugins);

  @SuppressWarnings("unchecked")
  private void bridged() {
    final InputSignalBridge bridge =
        new InputSignalBridge(
            flow.broker, new AnswerInputCommandProcessor(flow.guard, flow.broker));
    final ObjectProvider<InputSignalBridge> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(bridge);
    processor.setInputBridge(provider);
  }

  private InputRequest askNamed(final InputRisk risk, final String signal) {
    flow.ask(
        InputTestSupport.spec(
            "k",
            InputKind.APPROVAL,
            risk,
            Audience.CURRENT_TIER,
            List.of(),
            null,
            Map.of(InputBroker.SIGNAL_CONTEXT_KEY, signal)));
    return flow.onlyOpen();
  }

  private void legacyWaits() {
    final ExecutionControl control = mock(ExecutionControl.class);
    when(registry.findByExecutionId("exec")).thenReturn(Optional.of(control));
    when(control.workflowId()).thenReturn("wf");
    when(plugins.lookup("wf", "exec", "node")).thenReturn(Optional.of(plugin));
    when(plugin.onControlSignal(any()))
        .thenReturn(Mono.just(DefaultMessage.create(UUID.randomUUID(), "ACCEPTED")));
  }

  @Test
  void signalForAnOpenRequestAnswersItAsTheSender() {
    bridged();
    final InputRequest request = askNamed(InputRisk.HIGH, "approval");

    StepVerifier.create(
            processor.process(SIGNAL).contextWrite(InputFlow.asKey("op", ApiRole.OPERATOR)))
        .verifyComplete();

    assertThat(flow.current(request.id()).state()).isEqualTo(InputState.ANSWERED);
    final var response = flow.store.findResponse(request.id()).block();
    assertThat(response.decision()).isEqualTo("approve");
    assertThat(response.text()).isEqualTo("fine");
    assertThat(response.responder().id()).isEqualTo("op");
  }

  @Test
  void signalFromSupervisorOnHighRequestStaysHumanOnly() {
    bridged();
    flow.attach("guard", true, InputRisk.HIGH);
    final InputRequest request = askNamed(InputRisk.HIGH, "approval");

    StepVerifier.create(processor.process(SIGNAL).contextWrite(InputFlow.asSupervisor("guard")))
        .expectError(InputForbiddenException.class)
        .verify();

    assertThat(flow.current(request.id()).state()).isEqualTo(InputState.OPEN);
  }

  @Test
  void signalWithNoRequestForItsNameTakesTheOlderPath() {
    bridged();
    legacyWaits();
    final InputRequest other = askNamed(InputRisk.LOW, "another-name");

    StepVerifier.create(
            processor.process(SIGNAL).contextWrite(InputFlow.asKey("op", ApiRole.OPERATOR)))
        .verifyComplete();

    assertThat(flow.current(other.id()).state()).isEqualTo(InputState.OPEN);
  }

  @Test
  void requestOfAnotherNodeDoesNotCatchTheSignal() {
    bridged();
    legacyWaits();
    final InputRequest request = askNamed(InputRisk.LOW, "approval");
    final SignalNodeCommand otherNode =
        new SignalNodeCommand("exec", "other-node", "approval", "approve", null);
    when(plugins.lookup("wf", "exec", "other-node")).thenReturn(Optional.of(plugin));

    StepVerifier.create(
            processor.process(otherNode).contextWrite(InputFlow.asKey("op", ApiRole.OPERATOR)))
        .verifyComplete();

    assertThat(flow.current(request.id()).state()).isEqualTo(InputState.OPEN);
  }

  @Test
  void withoutBridgeBeanTheOlderPathIsTaken() {
    legacyWaits();
    @SuppressWarnings("unchecked")
    final ObjectProvider<InputSignalBridge> none = mock(ObjectProvider.class);
    processor.setInputBridge(none);

    StepVerifier.create(processor.process(SIGNAL)).verifyComplete();
  }

  @Test
  void anOpenRequestWithoutSignalNameIsLeftAlone() {
    bridged();
    legacyWaits();
    flow.ask(InputTestSupport.spec("k"));
    final InputRequest request = flow.onlyOpen();

    StepVerifier.create(processor.process(SIGNAL).contextWrite(Context.empty())).verifyComplete();

    assertThat(flow.current(request.id()).state()).isEqualTo(InputState.OPEN);
  }
}
