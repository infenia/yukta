// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.service.runtime.store.ActivePluginRegistry;
import com.infenia.yukta.testsupport.ExpectedErrorLog;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Applying control commands and sending a command to a live node. */
@ExtendWith(MockitoExtension.class)
@DisplayName("ControlBusService")
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.TooManyStaticImports"})
@ExpectedErrorLog("ERROR com.infenia.yukta.service.control.ControlBusService - ")
class ControlBusServiceTest {

  private static final String WORKFLOW_ID = "workflow-123";
  private static final String NODE_ID = "node-456";
  private static final String EXEC_ID = "run-1";

  @Mock private Plugin plugin;
  @Mock private Message<?> message;

  private ControlBusService controlBusService;

  private ActivePluginRegistry activePluginRegistry;

  @BeforeEach
  void setUp() {
    activePluginRegistry = new ActivePluginRegistry();
    controlBusService = new ControlBusService(activePluginRegistry);
  }

  private static Message<?> pause() {
    return DefaultMessage.create(null, new PauseWorkflowCommand("e1"))
        .withSourceNodeId("CONTROL_BUS");
  }

  @Test
  void emit_executionCommand_isAppliedByTheExecutorAndCompletesWhenDone() {
    final AtomicBoolean applied = new AtomicBoolean();
    controlBusService.setCommandExecutor(command -> Mono.fromRunnable(() -> applied.set(true)));

    StepVerifier.create(controlBusService.emit(pause())).verifyComplete();

    assertThat(applied).isTrue();
  }

  @Test
  void emit_executionCommand_surfacesTheReasonItWasRejected() {
    controlBusService.setCommandExecutor(
        command -> Mono.error(new IllegalArgumentException("Node not pausable")));

    StepVerifier.create(controlBusService.emit(pause()))
        .expectErrorMessage("Node not pausable")
        .verify();
  }

  @Test
  void emit_commandBeforeAnExecutorIsSet_isRefused() {
    StepVerifier.create(controlBusService.emit(pause()))
        .expectErrorSatisfies(
            e ->
                assertThat(e)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Not an execution control command: PauseWorkflowCommand"))
        .verify();
  }

  @Test
  void emit_nonCommand_isRefusedEvenWithExecutor() {
    controlBusService.setCommandExecutor(
        command -> Mono.error(new IllegalStateException("not for this")));
    final Message<?> signal =
        DefaultMessage.create(null, "just text").withSourceNodeId("n1").withWorkflowId("w1");

    StepVerifier.create(controlBusService.emit(signal))
        .expectErrorMessage("Not an execution control command: String")
        .verify();
  }

  @Test
  void emit_nonCommandBeforeAnExecutorIsSet_isRefused() {
    final Message<?> signal = DefaultMessage.create(null, "just text").withSourceNodeId("n1");

    StepVerifier.create(controlBusService.emit(signal))
        .expectErrorMessage("Not an execution control command: String")
        .verify();
  }

  @Test
  void emit_nullPayload_isRefused() {
    when(message.payload()).thenReturn(null);

    StepVerifier.create(controlBusService.emit(message))
        .expectErrorMessage("Not an execution control command: null")
        .verify();
  }

  @Test
  @DisplayName("sendCommand delegates to the registered plugin")
  void sendCommand_pluginRegistered_delegatesToPlugin() {
    doReturn(Mono.just(message)).when(plugin).onControlSignal(message);
    activePluginRegistry.register(WORKFLOW_ID, EXEC_ID, NODE_ID, plugin);

    StepVerifier.create(controlBusService.sendCommand(WORKFLOW_ID, EXEC_ID, NODE_ID, message))
        .expectNext(message)
        .verifyComplete();

    verify(plugin).onControlSignal(message);
  }

  @Test
  @DisplayName("sendCommand passes a message with a payload to the plugin")
  void sendCommand_withPayload_delegates() {
    final Message<?> command = DefaultMessage.create(null, "flush");
    when(plugin.onControlSignal(command)).thenReturn(Mono.just(command));
    activePluginRegistry.register(WORKFLOW_ID, EXEC_ID, NODE_ID, plugin);

    StepVerifier.create(controlBusService.sendCommand(WORKFLOW_ID, EXEC_ID, NODE_ID, command))
        .expectNext(command)
        .verifyComplete();
  }

  @Test
  @DisplayName("sendCommand fails when no plugin is registered")
  void sendCommand_noPlugin_returnsError() {
    StepVerifier.create(controlBusService.sendCommand(WORKFLOW_ID, EXEC_ID, NODE_ID, message))
        .expectErrorMatches(
            e ->
                e instanceof IllegalArgumentException
                    && e.getMessage().contains("Node not found: workflow-123/node-456"))
        .verify(Duration.ofSeconds(2));
  }

  @Test
  @DisplayName("sendCommand passes the plugin's error to the caller")
  void sendCommand_pluginErrors_propagatesError() {
    final RuntimeException error = new RuntimeException("Plugin failed");
    when(plugin.onControlSignal(message)).thenReturn(Mono.error(error));
    activePluginRegistry.register(WORKFLOW_ID, EXEC_ID, NODE_ID, plugin);

    StepVerifier.create(controlBusService.sendCommand(WORKFLOW_ID, EXEC_ID, NODE_ID, message))
        .expectErrorSatisfies(e -> assertThat(e).isSameAs(error))
        .verify(Duration.ofSeconds(2));
  }

  @Test
  @DisplayName("sendCommand handles a message with no payload")
  void sendCommand_nullPayload_logsAndDelegates() {
    final Message<?> nullPayloadMessage = mock(Message.class);
    when(nullPayloadMessage.payload()).thenReturn(null);
    when(plugin.onControlSignal(nullPayloadMessage)).thenReturn(Mono.just(nullPayloadMessage));
    activePluginRegistry.register(WORKFLOW_ID, EXEC_ID, NODE_ID, plugin);

    StepVerifier.create(
            controlBusService.sendCommand(WORKFLOW_ID, EXEC_ID, NODE_ID, nullPayloadMessage))
        .expectNext(nullPayloadMessage)
        .verifyComplete();

    verify(plugin).onControlSignal(nullPayloadMessage);
  }
}
