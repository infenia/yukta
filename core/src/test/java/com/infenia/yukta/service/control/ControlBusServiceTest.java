/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.service.control;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.service.control.directive.ControlSignalHandler;
import com.infenia.yukta.service.control.store.ActivePluginRegistry;
import com.infenia.yukta.service.orchestrator.WorkflowOrchestrator;
import com.infenia.yukta.service.workflow.store.PreparedWorkflowCache;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.util.concurrent.Queues;

@SuppressWarnings({
  "PMD.AvoidAccessibilityAlteration",
  "PMD.AvoidDuplicateLiterals",
  "PMD.AvoidThrowingRawExceptionTypes",
  "PMD.CommentDefaultAccessModifier",
  "PMD.CommentRequired",
  "PMD.ExcessiveImports",
  "PMD.FieldDeclarationsShouldBeAtStartOfClass",
  "PMD.LinguisticNaming",
  "PMD.TooManyMethods",
  "PMD.UnitTestShouldIncludeAssert",
  "PMD.UnnecessaryFullyQualifiedName"
})
@ExtendWith(MockitoExtension.class)
@DisplayName("ControlBusService")
@NoArgsConstructor
class ControlBusServiceTest {

  @BeforeEach
  void setUpLogging() {
    final Logger logger =
        (Logger) LoggerFactory.getLogger("com.infenia.yukta.service.control.ControlBusService");
    logger.setLevel(ch.qos.logback.classic.Level.TRACE);
  }

  private static final String WORKFLOW_ID = "workflow-123";
  private static final String NODE_ID = "node-456";
  private static final String SESSION_ID = "session-789";
  private static final String COMPOSITE_KEY = WORKFLOW_ID + "\0" + NODE_ID;

  @Mock private PreparedWorkflowCache preparedWorkflowCache;
  @Mock private WorkflowOrchestrator orchestrator;
  @Mock private ControlSignalHandler handler1;
  @Mock private ControlSignalHandler handler2;
  @Mock private Plugin plugin;
  @Mock private Message<?> message;
  @Mock private WorkflowDefinition workflowDefinition;
  @Mock private PreparedWorkflow preparedWorkflow;

  private ControlBusService controlBusService;

  @BeforeEach
  void setUp() {
    controlBusService =
        new ControlBusService(
            100,
            50,
            256,
            List.of(handler1, handler2),
            preparedWorkflowCache,
            orchestrator,
            new ActivePluginRegistry(List.of(handler1, handler2)));
    controlBusService.init();
  }

  @Nested
  @DisplayName("emit")
  @NoArgsConstructor
  class EmitTests {

    @Test
    @DisplayName("should complete successfully when signal is valid")
    void emit_validSignal_completesSuccessfully() {
      StepVerifier.create(controlBusService.emit(message))
          .expectComplete()
          .verify(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("should handle concurrent emissions without error")
    void emit_concurrent_noErrors() {
      for (int i = 0; i < 10; i++) {
        StepVerifier.create(controlBusService.emit(message))
            .expectComplete()
            .verify(Duration.ofSeconds(1));
      }
    }

    @Test
    @DisplayName("should handle message with null payload")
    void emit_nullPayload_completesSuccessfully() {
      final Message<?> nullPayloadMessage = mock(Message.class);
      when(nullPayloadMessage.getPayload()).thenReturn(null);

      StepVerifier.create(controlBusService.emit(nullPayloadMessage))
          .expectComplete()
          .verify(Duration.ofSeconds(2));
    }
  }

  @Nested
  @DisplayName("compileAndCacheWorkflow")
  @NoArgsConstructor
  class CompileAndCacheWorkflowTests {

    @Test
    @DisplayName("should invalidate cache, compile, and cache workflow on success")
    void compileAndCacheWorkflow_successfulCompilation_invalidatesAndCaches() {
      when(workflowDefinition.workflowId()).thenReturn(WORKFLOW_ID);
      when(orchestrator.prepareWorkflow(workflowDefinition))
          .thenReturn(Mono.just(preparedWorkflow));

      StepVerifier.create(controlBusService.compileAndCacheWorkflow(SESSION_ID, workflowDefinition))
          .expectComplete()
          .verify(Duration.ofSeconds(2));

      verify(preparedWorkflowCache).invalidate(SESSION_ID, WORKFLOW_ID);
      verify(orchestrator).prepareWorkflow(workflowDefinition);
      verify(preparedWorkflowCache).put(SESSION_ID, WORKFLOW_ID, preparedWorkflow);
    }

    @Test
    @DisplayName("should propagate orchestrator error without caching")
    void compileAndCacheWorkflow_orchestratorFails_emitsError() {
      when(workflowDefinition.workflowId()).thenReturn(WORKFLOW_ID);
      final RuntimeException error = new RuntimeException("Orchestration failed");
      when(orchestrator.prepareWorkflow(workflowDefinition)).thenReturn(Mono.error(error));

      StepVerifier.create(controlBusService.compileAndCacheWorkflow(SESSION_ID, workflowDefinition))
          .expectErrorSatisfies(e -> assertThat(e).isSameAs(error))
          .verify(Duration.ofSeconds(2));

      verify(preparedWorkflowCache).invalidate(SESSION_ID, WORKFLOW_ID);
      verify(preparedWorkflowCache, never()).put(any(), any(), any());
    }
  }

  @Nested
  @DisplayName("getLastHeartbeat")
  @NoArgsConstructor
  class GetLastHeartbeatTests {

    @Test
    @DisplayName("should return heartbeat when found in first handler")
    void getLastHeartbeat_foundInFirstHandler_returnsMessage() {
      doReturn(message).when(handler1).getLastHeartbeat(COMPOSITE_KEY);

      final Message<?> result = controlBusService.getLastHeartbeat(WORKFLOW_ID, NODE_ID);

      assertThat(result).isSameAs(message);
      verify(handler1).getLastHeartbeat(COMPOSITE_KEY);
    }

    @Test
    @DisplayName("should check subsequent handlers if first returns null")
    void getLastHeartbeat_firstReturnsNull_checksSecondHandler() {
      when(handler1.getLastHeartbeat(COMPOSITE_KEY)).thenReturn(null);
      doReturn(message).when(handler2).getLastHeartbeat(COMPOSITE_KEY);

      final Message<?> result = controlBusService.getLastHeartbeat(WORKFLOW_ID, NODE_ID);

      assertThat(result).isSameAs(message);
      verify(handler1).getLastHeartbeat(COMPOSITE_KEY);
      verify(handler2).getLastHeartbeat(COMPOSITE_KEY);
    }

    @Test
    @DisplayName("should return null when no handler has heartbeat")
    void getLastHeartbeat_noHandlerHasIt_returnsNull() {
      when(handler1.getLastHeartbeat(COMPOSITE_KEY)).thenReturn(null);
      when(handler2.getLastHeartbeat(COMPOSITE_KEY)).thenReturn(null);

      final Message<?> result = controlBusService.getLastHeartbeat(WORKFLOW_ID, NODE_ID);

      assertThat(result).isNull();
    }
  }

  @Nested
  @DisplayName("getLastStatistics")
  @NoArgsConstructor
  class GetLastStatisticsTests {

    @Test
    @DisplayName("should return statistics when found in first handler")
    void getLastStatistics_foundInFirstHandler_returnsMessage() {
      doReturn(message).when(handler1).getLastStatistics(COMPOSITE_KEY);

      final Message<?> result = controlBusService.getLastStatistics(WORKFLOW_ID, NODE_ID);

      assertThat(result).isSameAs(message);
      verify(handler1).getLastStatistics(COMPOSITE_KEY);
    }

    @Test
    @DisplayName("should check subsequent handlers if first returns null")
    void getLastStatistics_firstReturnsNull_checksSecondHandler() {
      when(handler1.getLastStatistics(COMPOSITE_KEY)).thenReturn(null);
      doReturn(message).when(handler2).getLastStatistics(COMPOSITE_KEY);

      final Message<?> result = controlBusService.getLastStatistics(WORKFLOW_ID, NODE_ID);

      assertThat(result).isSameAs(message);
      verify(handler1).getLastStatistics(COMPOSITE_KEY);
      verify(handler2).getLastStatistics(COMPOSITE_KEY);
    }

    @Test
    @DisplayName("should return null when no handler has statistics")
    void getLastStatistics_noHandlerHasIt_returnsNull() {
      when(handler1.getLastStatistics(COMPOSITE_KEY)).thenReturn(null);
      when(handler2.getLastStatistics(COMPOSITE_KEY)).thenReturn(null);

      final Message<?> result = controlBusService.getLastStatistics(WORKFLOW_ID, NODE_ID);

      assertThat(result).isNull();
    }
  }

  @Nested
  @DisplayName("getActiveNodes")
  @NoArgsConstructor
  class GetActiveNodesTests {

    @Test
    @DisplayName("should return node IDs filtered by workflow ID")
    void getActiveNodes_withWorkflowId_returnsFiltered() {
      final String otherWorkflowKey = "other-workflow\0other-node";
      final List<String> allKeys =
          List.of(COMPOSITE_KEY, WORKFLOW_ID + "\0" + "another-node", otherWorkflowKey);

      when(handler1.getActiveNodes()).thenReturn(allKeys);

      final List<String> result = controlBusService.getActiveNodes(WORKFLOW_ID);

      assertThat(result).containsExactly(NODE_ID, "another-node");
    }

    @Test
    @DisplayName("should return empty list when no nodes for workflow")
    void getActiveNodes_noMatchingNodes_returnsEmptyList() {
      when(handler1.getActiveNodes()).thenReturn(List.of("other-workflow\0node"));

      final List<String> result = controlBusService.getActiveNodes(WORKFLOW_ID);

      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should return empty list when all handlers are empty")
    void getActiveNodes_noActiveNodes_returnsEmptyList() {
      when(handler1.getActiveNodes()).thenReturn(List.of());
      when(handler2.getActiveNodes()).thenReturn(List.of());

      final List<String> result = controlBusService.getActiveNodes(WORKFLOW_ID);

      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should return all active nodes without filtering when workflow ID not provided")
    void getActiveNodes_noFilter_returnsAll() {
      final List<String> allKeys = List.of(COMPOSITE_KEY, "another-workflow\0node-2");
      when(handler1.getActiveNodes()).thenReturn(allKeys);

      final List<String> result = controlBusService.getActiveNodes();

      assertThat(result).isEqualTo(allKeys);
    }

    @Test
    @DisplayName("should check handler2 when handler1 returns empty list")
    void getActiveNodes_handler1Empty_checksHandler2() {
      final List<String> handler2Keys = List.of(COMPOSITE_KEY);
      when(handler1.getActiveNodes()).thenReturn(List.of());
      when(handler2.getActiveNodes()).thenReturn(handler2Keys);

      final List<String> result = controlBusService.getActiveNodes();

      assertThat(result).isEqualTo(handler2Keys);
    }
  }

  @Nested
  @DisplayName("registerPlugin")
  @NoArgsConstructor
  class RegisterPluginTests {

    @Test
    @DisplayName("should store plugin and make it accessible")
    void registerPlugin_storesAndRetrievable() {
      doReturn(Mono.just(message)).when(plugin).onControlSignal(message);

      controlBusService.registerPlugin(WORKFLOW_ID, NODE_ID, plugin);

      StepVerifier.create(controlBusService.sendCommand(WORKFLOW_ID, NODE_ID, message))
          .expectNext(message)
          .verifyComplete();

      verify(plugin).onControlSignal(message);
    }

    @Test
    @DisplayName("should maintain separate plugins for different nodes")
    void registerPlugin_differentNodes_storedSeparately() {
      final String altNodeId = "other-node";
      final Plugin altPlugin = mock(Plugin.class);

      doReturn(Mono.just(message)).when(plugin).onControlSignal(message);

      controlBusService.registerPlugin(WORKFLOW_ID, NODE_ID, plugin);
      controlBusService.registerPlugin(WORKFLOW_ID, altNodeId, altPlugin);

      StepVerifier.create(controlBusService.sendCommand(WORKFLOW_ID, NODE_ID, message))
          .expectNext(message)
          .verifyComplete();
    }
  }

  @Nested
  @DisplayName("unregisterPlugin")
  @NoArgsConstructor
  class UnregisterPluginTests {

    @Test
    @DisplayName("should remove plugin and notify all handlers")
    void unregisterPlugin_cleansUp() {
      controlBusService.registerPlugin(WORKFLOW_ID, NODE_ID, plugin);
      controlBusService.unregisterPlugin(WORKFLOW_ID, NODE_ID);

      verify(handler1).removeNode(COMPOSITE_KEY);
      verify(handler2).removeNode(COMPOSITE_KEY);
    }

    @Test
    @DisplayName("should prevent unregistered plugin from receiving commands")
    void unregisterPlugin_preventsFurtherCalls() {
      controlBusService.registerPlugin(WORKFLOW_ID, NODE_ID, plugin);
      controlBusService.unregisterPlugin(WORKFLOW_ID, NODE_ID);

      StepVerifier.create(controlBusService.sendCommand(WORKFLOW_ID, NODE_ID, message))
          .expectErrorMatches(
              e ->
                  e instanceof IllegalArgumentException
                      && e.getMessage().contains("Node not found"))
          .verify(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("should not notify handlers when unregistering a never-registered node")
    void unregisterPlugin_nonExistent_doesNotNotifyHandlers() {
      controlBusService.unregisterPlugin(WORKFLOW_ID, NODE_ID);

      verify(handler1, never()).removeNode(any());
      verify(handler2, never()).removeNode(any());
    }

    @Test
    @DisplayName("should keep plugin reachable while a concurrent registration is still active")
    void unregisterPlugin_concurrentRegistration_keepsPluginUntilLastRelease() {
      doReturn(Mono.just(message)).when(plugin).onControlSignal(message);

      // Two concurrent "executions" register the same shared plugin instance for the same key.
      controlBusService.registerPlugin(WORKFLOW_ID, NODE_ID, plugin);
      controlBusService.registerPlugin(WORKFLOW_ID, NODE_ID, plugin);

      // First execution finishes and releases its registration; the second is still active.
      controlBusService.unregisterPlugin(WORKFLOW_ID, NODE_ID);
      verify(handler1, never()).removeNode(COMPOSITE_KEY);
      verify(handler2, never()).removeNode(COMPOSITE_KEY);
      StepVerifier.create(controlBusService.sendCommand(WORKFLOW_ID, NODE_ID, message))
          .expectNext(message)
          .verifyComplete();

      // Second execution finishes; now the registration is fully released.
      controlBusService.unregisterPlugin(WORKFLOW_ID, NODE_ID);
      verify(handler1).removeNode(COMPOSITE_KEY);
      verify(handler2).removeNode(COMPOSITE_KEY);
      StepVerifier.create(controlBusService.sendCommand(WORKFLOW_ID, NODE_ID, message))
          .expectErrorMatches(
              e ->
                  e instanceof IllegalArgumentException
                      && e.getMessage().contains("Node not found"))
          .verify(Duration.ofSeconds(2));
    }
  }

  @Nested
  @DisplayName("sendCommand")
  @NoArgsConstructor
  class SendCommandTests {

    @Test
    @DisplayName("should delegate to registered plugin")
    void sendCommand_pluginRegistered_delegatesToPlugin() {
      doReturn(Mono.just(message)).when(plugin).onControlSignal(message);
      controlBusService.registerPlugin(WORKFLOW_ID, NODE_ID, plugin);

      StepVerifier.create(controlBusService.sendCommand(WORKFLOW_ID, NODE_ID, message))
          .expectNext(message)
          .verifyComplete();

      verify(plugin).onControlSignal(message);
    }

    @Test
    @DisplayName("should return error when plugin not registered")
    void sendCommand_noPlugin_returnsError() {
      StepVerifier.create(controlBusService.sendCommand(WORKFLOW_ID, NODE_ID, message))
          .expectErrorMatches(
              e ->
                  e instanceof IllegalArgumentException
                      && e.getMessage().contains("Node not found: workflow-123/node-456"))
          .verify(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("should propagate plugin error to caller")
    void sendCommand_pluginErrors_propagatesError() {
      final RuntimeException error = new RuntimeException("Plugin failed");
      when(plugin.onControlSignal(message)).thenReturn(Mono.error(error));
      controlBusService.registerPlugin(WORKFLOW_ID, NODE_ID, plugin);

      StepVerifier.create(controlBusService.sendCommand(WORKFLOW_ID, NODE_ID, message))
          .expectErrorSatisfies(e -> assertThat(e).isSameAs(error))
          .verify(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("should handle sendCommand with null payload message")
    void sendCommand_nullPayload_logsAndDelegates() {
      final Message<?> nullPayloadMessage = mock(Message.class);
      when(nullPayloadMessage.getPayload()).thenReturn(null);
      when(plugin.onControlSignal(nullPayloadMessage)).thenReturn(Mono.just(nullPayloadMessage));
      controlBusService.registerPlugin(WORKFLOW_ID, NODE_ID, plugin);

      StepVerifier.create(controlBusService.sendCommand(WORKFLOW_ID, NODE_ID, nullPayloadMessage))
          .expectNext(nullPayloadMessage)
          .verifyComplete();

      verify(plugin).onControlSignal(nullPayloadMessage);
    }
  }

  @Nested
  @DisplayName("shutdown")
  @NoArgsConstructor
  class ShutdownTests {

    @Test
    @DisplayName("should complete control stream after shutdown")
    void shutdown_completesStream() {
      final ControlBusService shutdownService =
          new ControlBusService(
              100,
              50,
              256,
              List.of(),
              preparedWorkflowCache,
              orchestrator,
              new ActivePluginRegistry(List.of()));
      shutdownService.init();

      shutdownService.shutdown();

      StepVerifier.create(shutdownService.getControlStream())
          .expectComplete()
          .verify(Duration.ofSeconds(2));
    }
  }

  @Nested
  @DisplayName("init")
  @NoArgsConstructor
  class InitTests {

    @Test
    @DisplayName("should create custom sink when buffer size is larger than minimum")
    void init_customBufferSize_createsSink() {
      final ControlBusService customService =
          new ControlBusService(
              100,
              50,
              512,
              List.of(handler1),
              preparedWorkflowCache,
              orchestrator,
              new ActivePluginRegistry(List.of()));

      customService.init();

      StepVerifier.create(customService.emit(message))
          .expectComplete()
          .verify(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("should use default sink when buffer size equals SMALL_BUFFER_SIZE")
    void init_defaultBufferSize_usesExisting() {
      final ControlBusService defaultService =
          new ControlBusService(
              100,
              50,
              Queues.SMALL_BUFFER_SIZE,
              List.of(handler1),
              preparedWorkflowCache,
              orchestrator,
              new ActivePluginRegistry(List.of()));

      defaultService.init();

      StepVerifier.create(defaultService.emit(message))
          .expectComplete()
          .verify(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("should enforce minimum buffer size when size is too small")
    void init_tinyBufferSize_enforcesMinium() {
      final ControlBusService minService =
          new ControlBusService(
              100,
              50,
              1,
              List.of(handler1),
              preparedWorkflowCache,
              orchestrator,
              new ActivePluginRegistry(List.of()));

      minService.init();

      StepVerifier.create(minService.emit(message)).expectComplete().verify(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("should subscribe to control sink and buffer timeout settings")
    void init_configuresBufferAndTimeout() {
      final int customBatchTimeout = 75;
      final ControlBusService customTimeoutService =
          new ControlBusService(
              100,
              customBatchTimeout,
              256,
              List.of(handler1),
              preparedWorkflowCache,
              orchestrator,
              new ActivePluginRegistry(List.of()));

      customTimeoutService.init();

      StepVerifier.create(customTimeoutService.emit(message))
          .expectComplete()
          .verify(Duration.ofSeconds(2));
    }
  }

  @Nested
  @DisplayName("Composite key management")
  @NoArgsConstructor
  class CompositeKeyTests {

    @Test
    @DisplayName("should use null separator in composite keys")
    void compositeKey_formatCorrect() {
      doReturn(message).when(handler1).getLastHeartbeat(COMPOSITE_KEY);

      controlBusService.getLastHeartbeat(WORKFLOW_ID, NODE_ID);

      verify(handler1).getLastHeartbeat(WORKFLOW_ID + "\0" + NODE_ID);
    }

    @Test
    @DisplayName("should treat different node IDs as independent")
    void compositeKey_differentNodeIds_independent() {
      final String altNodeId = "different-node";
      final String altKey = WORKFLOW_ID + "\0" + altNodeId;

      doReturn(message).when(handler1).getLastHeartbeat(COMPOSITE_KEY);
      doReturn(null).when(handler1).getLastHeartbeat(altKey);

      final Message<?> result1 = controlBusService.getLastHeartbeat(WORKFLOW_ID, NODE_ID);
      final Message<?> result2 = controlBusService.getLastHeartbeat(WORKFLOW_ID, altNodeId);

      assertThat(result1).isSameAs(message);
      assertThat(result2).isNull();
    }
  }

  @Nested
  @DisplayName("Integration scenarios")
  @NoArgsConstructor
  class IntegrationTests {

    @Test
    @DisplayName("should complete full plugin lifecycle: register, use, unregister")
    void fullLifecycle_registerUseUnregister() {
      doReturn(Mono.just(message)).when(plugin).onControlSignal(message);

      controlBusService.registerPlugin(WORKFLOW_ID, NODE_ID, plugin);
      StepVerifier.create(controlBusService.sendCommand(WORKFLOW_ID, NODE_ID, message))
          .expectNext(message)
          .verifyComplete();

      controlBusService.unregisterPlugin(WORKFLOW_ID, NODE_ID);
      StepVerifier.create(controlBusService.sendCommand(WORKFLOW_ID, NODE_ID, message))
          .expectError(IllegalArgumentException.class)
          .verify(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("should retrieve metrics from appropriate handlers")
    void multipleHandlers_metricsRetrieved() {
      final Message<?> heartbeatMsg = mock(Message.class);
      final Message<?> statsMsg = mock(Message.class);

      doReturn(heartbeatMsg).when(handler1).getLastHeartbeat(COMPOSITE_KEY);
      when(handler1.getLastStatistics(COMPOSITE_KEY)).thenReturn(null);
      doReturn(statsMsg).when(handler2).getLastStatistics(COMPOSITE_KEY);

      final Message<?> heartbeat = controlBusService.getLastHeartbeat(WORKFLOW_ID, NODE_ID);
      final Message<?> stats = controlBusService.getLastStatistics(WORKFLOW_ID, NODE_ID);

      assertThat(heartbeat).isSameAs(heartbeatMsg);
      assertThat(stats).isSameAs(statsMsg);
    }

    @Test
    @DisplayName("should manage multiple workflows independently")
    void multipleWorkflows_independent() {
      final String altWorkflowId = "workflow-alt";
      final String altCompositeKey = altWorkflowId + "\0" + NODE_ID;
      final Message<?> altMessage = mock(Message.class);

      doReturn(message).when(handler1).getLastHeartbeat(COMPOSITE_KEY);
      doReturn(altMessage).when(handler1).getLastHeartbeat(altCompositeKey);

      final Message<?> result1 = controlBusService.getLastHeartbeat(WORKFLOW_ID, NODE_ID);
      final Message<?> result2 = controlBusService.getLastHeartbeat(altWorkflowId, NODE_ID);

      assertThat(result1).isSameAs(message);
      assertThat(result2).isSameAs(altMessage);
    }
  }

  @Nested
  @DisplayName("Control stream publishing")
  @NoArgsConstructor
  class ControlStreamTests {

    @Test
    @DisplayName("should return non-null flux from getControlStream")
    void getControlStream_returnsFlux() {
      final var stream = controlBusService.getControlStream();

      assertThat(stream).isNotNull();
    }
  }

  @Nested
  @DisplayName("Error handling and resilience")
  @NoArgsConstructor
  class ErrorHandlingTests {

    @Test
    @DisplayName("should handle messages with various payloads")
    void emit_differentPayloads_allEmitted() {
      StepVerifier.create(controlBusService.emit(message))
          .expectComplete()
          .verify(Duration.ofSeconds(2));

      StepVerifier.create(controlBusService.emit(message))
          .expectComplete()
          .verify(Duration.ofSeconds(2));

      StepVerifier.create(controlBusService.emit(message))
          .expectComplete()
          .verify(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("should emit error when sink throws RuntimeException")
    @SuppressWarnings("unchecked")
    void emit_sinkThrowsRuntimeException_propagatesAsIllegalState() throws Exception {
      final ControlBusService svc =
          new ControlBusService(
              100,
              50,
              256,
              List.of(),
              preparedWorkflowCache,
              orchestrator,
              new ActivePluginRegistry(List.of()));
      svc.init();

      final Sinks.Many<Message<?>> throwingSink = mock(Sinks.Many.class);
      doThrow(new RuntimeException("sink exploded")).when(throwingSink).emitNext(any(), any());

      final Field sinkField = ControlBusService.class.getDeclaredField("controlSink");
      sinkField.setAccessible(true);
      sinkField.set(svc, throwingSink);

      StepVerifier.create(svc.emit(message))
          .expectErrorSatisfies(
              e ->
                  assertThat(e)
                      .isInstanceOf(IllegalStateException.class)
                      .hasMessageContaining("Control bus emit failed"))
          .verify(Duration.ofSeconds(2));
    }
  }

  @Nested
  @DisplayName("handleControlBatch dispatch")
  @MockitoSettings(strictness = Strictness.LENIENT)
  @NoArgsConstructor
  class HandleControlBatchTests {

    private Message<?> makeMessage(final Object payload, final int priority) {
      final Message<?> msg = mock(Message.class);
      when(msg.getWorkflowId()).thenReturn(ControlBusServiceTest.WORKFLOW_ID);
      when(msg.getSourceNodeId()).thenReturn(ControlBusServiceTest.NODE_ID);
      doReturn(payload).when(msg).getPayload();
      when(msg.getPriority()).thenReturn(priority);
      return msg;
    }

    private void emitAndWait(
        final ControlBusService svc, final Message<?> msg, final CountDownLatch latch)
        throws InterruptedException {
      svc.emit(msg).subscribe();
      final boolean completed = latch.await(3, TimeUnit.SECONDS);
      assertThat(completed).as("Message handling timed out").isTrue();
    }

    @Test
    @DisplayName("should dispatch to matching handler for valid message")
    void handleControlBatch_validMessage_dispatchesToHandler() throws InterruptedException {
      final Object payload = new Object();
      final CountDownLatch latch = new CountDownLatch(1);

      when(handler1.canHandle(payload)).thenReturn(true);
      doAnswer(
              _ -> {
                latch.countDown();
                return null;
              })
          .when(handler1)
          .handle(eq(COMPOSITE_KEY), any(), eq(payload));

      final ControlBusService svc =
          new ControlBusService(
              1,
              10,
              256,
              List.of(handler1, handler2),
              preparedWorkflowCache,
              orchestrator,
              new ActivePluginRegistry(List.of()));
      svc.init();

      final Message<?> msg = makeMessage(payload, 0);
      emitAndWait(svc, msg, latch);

      verify(handler1).handle(COMPOSITE_KEY, msg, payload);
      verify(handler2, never()).handle(any(), any(), any());
    }

    @Test
    @DisplayName("should skip dispatch when nodeId is null")
    void handleControlBatch_nullNodeId_skipsDispatch() throws InterruptedException {
      final Object payload = new Object();

      final Message<?> msg = mock(Message.class);
      when(msg.getWorkflowId()).thenReturn(WORKFLOW_ID);
      when(msg.getSourceNodeId()).thenReturn(null);
      doReturn(payload).when(msg).getPayload();
      when(msg.getPriority()).thenReturn(0);

      final ControlBusService svc =
          new ControlBusService(
              1,
              10,
              256,
              List.of(handler1),
              preparedWorkflowCache,
              orchestrator,
              new ActivePluginRegistry(List.of()));
      svc.init();

      svc.emit(msg).subscribe();
      Thread.sleep(200);

      verify(handler1, never()).handle(any(), any(), any());
    }

    @Test
    @DisplayName("should skip dispatch when payload is null")
    void handleControlBatch_nullPayload_skipsDispatch() throws InterruptedException {
      final Message<?> msg = mock(Message.class);
      when(msg.getWorkflowId()).thenReturn(WORKFLOW_ID);
      when(msg.getSourceNodeId()).thenReturn(NODE_ID);
      when(msg.getPayload()).thenReturn(null);
      when(msg.getPriority()).thenReturn(0);

      final ControlBusService svc =
          new ControlBusService(
              1,
              10,
              256,
              List.of(handler1),
              preparedWorkflowCache,
              orchestrator,
              new ActivePluginRegistry(List.of()));
      svc.init();

      svc.emit(msg).subscribe();
      Thread.sleep(200);

      verify(handler1, never()).handle(any(), any(), any());
    }

    @Test
    @DisplayName("should skip dispatch when workflowId is null")
    void handleControlBatch_nullWorkflowId_skipsDispatch() throws InterruptedException {
      final Object payload = new Object();

      final Message<?> msg = mock(Message.class);
      when(msg.getWorkflowId()).thenReturn(null);
      when(msg.getSourceNodeId()).thenReturn(NODE_ID);
      doReturn(payload).when(msg).getPayload();
      when(msg.getPriority()).thenReturn(0);

      final ControlBusService svc =
          new ControlBusService(
              1,
              10,
              256,
              List.of(handler1),
              preparedWorkflowCache,
              orchestrator,
              new ActivePluginRegistry(List.of()));
      svc.init();

      svc.emit(msg).subscribe();
      Thread.sleep(200);

      verify(handler1, never()).handle(any(), any(), any());
    }

    @Test
    @DisplayName("should try second handler when first handler cannot handle")
    void handleControlBatch_firstHandlerSkips_secondHandlerDispatches()
        throws InterruptedException {
      final Object payload = new Object();
      final CountDownLatch latch = new CountDownLatch(1);

      when(handler1.canHandle(payload)).thenReturn(false);
      when(handler2.canHandle(payload)).thenReturn(true);
      doAnswer(
              _ -> {
                latch.countDown();
                return null;
              })
          .when(handler2)
          .handle(eq(COMPOSITE_KEY), any(), eq(payload));

      final ControlBusService svc =
          new ControlBusService(
              1,
              10,
              256,
              List.of(handler1, handler2),
              preparedWorkflowCache,
              orchestrator,
              new ActivePluginRegistry(List.of()));
      svc.init();

      final Message<?> msg = makeMessage(payload, 0);
      emitAndWait(svc, msg, latch);

      verify(handler1, never()).handle(any(), any(), any());
      verify(handler2).handle(COMPOSITE_KEY, msg, payload);
    }

    @Test
    @DisplayName("should not dispatch when no handler matches")
    void handleControlBatch_noHandlerMatches_nothingDispatched() throws InterruptedException {
      final Object payload = new Object();

      when(handler1.canHandle(payload)).thenReturn(false);
      when(handler2.canHandle(payload)).thenReturn(false);

      final ControlBusService svc =
          new ControlBusService(
              1,
              10,
              256,
              List.of(handler1, handler2),
              preparedWorkflowCache,
              orchestrator,
              new ActivePluginRegistry(List.of()));
      svc.init();

      final Message<?> msg = makeMessage(payload, 0);
      svc.emit(msg).subscribe();
      Thread.sleep(200);

      verify(handler1, never()).handle(any(), any(), any());
      verify(handler2, never()).handle(any(), any(), any());
    }

    @Test
    @DisplayName("should process higher-priority messages first within a batch")
    void handleControlBatch_mixedPriority_highPriorityProcessedFirst() throws InterruptedException {
      final Object payload = new Object();
      final List<Integer> dispatchOrder = new ArrayList<>();
      final CountDownLatch latch = new CountDownLatch(2);

      final Message<?> lowMsg = makeMessage(payload, 1);
      final Message<?> highMsg = makeMessage(payload, 10);

      when(handler1.canHandle(payload)).thenReturn(true);
      doAnswer(
              inv -> {
                dispatchOrder.add(((Message<?>) inv.getArgument(1)).getPriority());
                latch.countDown();
                return null;
              })
          .when(handler1)
          .handle(any(), any(), any());

      final ControlBusService svc =
          new ControlBusService(
              2,
              200,
              256,
              List.of(handler1),
              preparedWorkflowCache,
              orchestrator,
              new ActivePluginRegistry(List.of()));
      svc.init();

      svc.emit(lowMsg).subscribe();
      svc.emit(highMsg).subscribe();
      final boolean completed = latch.await(3, TimeUnit.SECONDS);
      assertThat(completed).as("Message handling timed out").isTrue();

      assertThat(dispatchOrder).containsExactly(10, 1);
    }

    @Test
    @DisplayName("should recover from handler exception and continue processing")
    void handleControlBatch_handlerThrows_busRecoversContinues() throws InterruptedException {
      final Object payload = new Object();
      final CountDownLatch errorBatch = new CountDownLatch(1);
      final CountDownLatch successBatch = new CountDownLatch(1);

      final Message<?> msg1 = makeMessage(payload, 0);
      final Message<?> msg2 = makeMessage(payload, 0);

      when(handler1.canHandle(payload)).thenReturn(true);
      doAnswer(
              _ -> {
                errorBatch.countDown();
                throw new RuntimeException("handler failed");
              })
          .doAnswer(
              _ -> {
                successBatch.countDown();
                return null;
              })
          .when(handler1)
          .handle(any(), any(), any());

      final ControlBusService svc =
          new ControlBusService(
              1,
              10,
              256,
              List.of(handler1),
              preparedWorkflowCache,
              orchestrator,
              new ActivePluginRegistry(List.of()));
      svc.init();

      svc.emit(msg1).subscribe();
      final boolean errorHandled = errorBatch.await(3, TimeUnit.SECONDS);
      assertThat(errorHandled).as("Error batch handling timed out").isTrue();

      svc.emit(msg2).subscribe();
      final boolean processed = successBatch.await(3, TimeUnit.SECONDS);

      assertThat(processed).isTrue();
    }

    @Test
    @DisplayName("should handle batch processing success with message in stream")
    void handleControlBatch_successfulProcessing_messagesHandled() throws InterruptedException {
      final String payload = "test-payload";
      final CountDownLatch latch = new CountDownLatch(1);

      when(handler1.canHandle(payload)).thenReturn(true);
      doAnswer(
              _ -> {
                latch.countDown();
                return null;
              })
          .when(handler1)
          .handle(any(), any(), any());

      final ControlBusService svc =
          new ControlBusService(
              1,
              50,
              256,
              List.of(handler1),
              preparedWorkflowCache,
              orchestrator,
              new ActivePluginRegistry(List.of()));
      svc.init();

      final Message<?> msg = makeMessage(payload, 0);
      svc.emit(msg).subscribe();

      final boolean processed = latch.await(3, TimeUnit.SECONDS);
      assertThat(processed).isTrue();
      verify(handler1).handle(WORKFLOW_ID + "\0" + NODE_ID, msg, payload);
    }

    @Test
    @DisplayName("should process large batch with prioritization")
    void handleControlBatch_largeBatch_prioritized() throws InterruptedException {
      final String payload = "test";
      final CountDownLatch latch = new CountDownLatch(5);

      when(handler1.canHandle(payload)).thenReturn(true);
      doAnswer(
              _ -> {
                latch.countDown();
                return null;
              })
          .when(handler1)
          .handle(any(), any(), any());

      final ControlBusService svc =
          new ControlBusService(
              5,
              100,
              256,
              List.of(handler1),
              preparedWorkflowCache,
              orchestrator,
              new ActivePluginRegistry(List.of()));
      svc.init();

      for (int i = 0; i < 5; i++) {
        final Message<?> msg = makeMessage(payload, i * 10);
        svc.emit(msg).subscribe();
      }

      final boolean processed = latch.await(3, TimeUnit.SECONDS);
      assertThat(processed).isTrue();
      verify(handler1, times(5)).handle(any(), any(), any());
    }

    @Test
    @DisplayName("should handle batch with successful processing and trace logging")
    void handleControlBatch_multipleMessages_allProcessedSuccessfully()
        throws InterruptedException {
      final Object payload = new Object();
      final CountDownLatch latch = new CountDownLatch(3);

      when(handler1.canHandle(payload)).thenReturn(true);
      doAnswer(
              _ -> {
                latch.countDown();
                return null;
              })
          .when(handler1)
          .handle(any(), any(), any());

      final ControlBusService svc =
          new ControlBusService(
              3,
              100,
              256,
              List.of(handler1),
              preparedWorkflowCache,
              orchestrator,
              new ActivePluginRegistry(List.of()));
      svc.init();

      final Message<?> msg1 = makeMessage(payload, 0);
      final Message<?> msg2 = makeMessage(payload, 0);
      final Message<?> msg3 = makeMessage(payload, 0);

      svc.emit(msg1).subscribe();
      svc.emit(msg2).subscribe();
      svc.emit(msg3).subscribe();

      final boolean processed = latch.await(3, TimeUnit.SECONDS);
      assertThat(processed).isTrue();
      verify(handler1, times(3)).handle(any(), any(), any());
    }

    @Test
    @DisplayName("should handle fatal error in control stream")
    void handleControlBatch_fatalStreamError_handled() throws Exception {
      final ControlBusService svc =
          new ControlBusService(
              100,
              50,
              256,
              List.of(handler1),
              preparedWorkflowCache,
              orchestrator,
              new ActivePluginRegistry(List.of()));
      svc.init();

      final Field sinkField = ControlBusService.class.getDeclaredField("controlSink");
      sinkField.setAccessible(true);
      @SuppressWarnings("unchecked")
      final Sinks.Many<Message<?>> errorSink = mock(Sinks.Many.class);
      when(errorSink.asFlux()).thenReturn(Flux.error(new RuntimeException("Fatal stream error")));

      sinkField.set(svc, errorSink);

      Thread.sleep(200);
    }
  }

  @Nested
  @DisplayName("Logging branch coverage")
  @MockitoSettings(strictness = Strictness.LENIENT)
  @NoArgsConstructor
  class LoggingBranchTests {

    @Test
    @DisplayName("emit with message containing payload should log debug signal emission")
    void emit_withPayload_logsDebugAndTrace() {
      @SuppressWarnings("unchecked")
      final Message<String> msgWithPayload = mock(Message.class);
      final String testPayload = "testPayload";
      when(msgWithPayload.getPayload()).thenReturn(testPayload);

      StepVerifier.create(controlBusService.emit(msgWithPayload))
          .expectComplete()
          .verify(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("batch processing with multiple messages should handle all scenarios")
    void handleControlBatch_withDifferentPayloads_processesSuccessfully()
        throws InterruptedException {
      final CountDownLatch latch = new CountDownLatch(2);

      final String payload1 = "payload1";
      final String payload2 = "payload2";

      when(handler1.canHandle(payload1)).thenReturn(true);
      when(handler1.canHandle(payload2)).thenReturn(true);
      doAnswer(
              _ -> {
                latch.countDown();
                return null;
              })
          .when(handler1)
          .handle(any(), any(), any());

      final ControlBusService svc =
          new ControlBusService(
              2,
              50,
              256,
              List.of(handler1),
              preparedWorkflowCache,
              orchestrator,
              new ActivePluginRegistry(List.of()));
      svc.init();

      @SuppressWarnings("unchecked")
      final Message<String> msg1 = mock(Message.class);
      when(msg1.getWorkflowId()).thenReturn(WORKFLOW_ID);
      when(msg1.getSourceNodeId()).thenReturn(NODE_ID);
      when(msg1.getPayload()).thenReturn(payload1);
      when(msg1.getPriority()).thenReturn(5);

      @SuppressWarnings("unchecked")
      final Message<String> msg2 = mock(Message.class);
      when(msg2.getWorkflowId()).thenReturn(WORKFLOW_ID);
      when(msg2.getSourceNodeId()).thenReturn(NODE_ID);
      when(msg2.getPayload()).thenReturn(payload2);
      when(msg2.getPriority()).thenReturn(3);

      svc.emit(msg1).subscribe();
      svc.emit(msg2).subscribe();

      final boolean processed = latch.await(3, TimeUnit.SECONDS);
      assertThat(processed).isTrue();
    }

    @Test
    @DisplayName("emit should handle exception gracefully with error logging")
    @SuppressWarnings("unchecked")
    void emit_sinkThrowsException_logsErrorAndPropagates() throws Exception {
      final ControlBusService svc =
          new ControlBusService(
              100,
              50,
              256,
              List.of(),
              preparedWorkflowCache,
              orchestrator,
              new ActivePluginRegistry(List.of()));
      svc.init();

      final Sinks.Many<Message<?>> throwingSink = mock(Sinks.Many.class);
      doThrow(new RuntimeException("sink error")).when(throwingSink).emitNext(any(), any());

      final Field sinkField = ControlBusService.class.getDeclaredField("controlSink");
      sinkField.setAccessible(true);
      sinkField.set(svc, throwingSink);

      final Message<?> testMessage = mock(Message.class);
      doReturn("test").when(testMessage).getPayload();

      StepVerifier.create(svc.emit(testMessage))
          .expectErrorMatches(e -> e instanceof IllegalStateException)
          .verify(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("compileAndCacheWorkflow with error should log appropriately")
    void compileAndCacheWorkflow_withLoggingOnFailure_logsError() {
      when(workflowDefinition.workflowId()).thenReturn(WORKFLOW_ID);
      final RuntimeException testError = new RuntimeException("Compilation failed");
      when(orchestrator.prepareWorkflow(workflowDefinition)).thenReturn(Mono.error(testError));

      StepVerifier.create(controlBusService.compileAndCacheWorkflow(SESSION_ID, workflowDefinition))
          .expectErrorSatisfies(e -> assertThat(e).isSameAs(testError))
          .verify(Duration.ofSeconds(2));

      verify(preparedWorkflowCache).invalidate(SESSION_ID, WORKFLOW_ID);
    }
  }
}
