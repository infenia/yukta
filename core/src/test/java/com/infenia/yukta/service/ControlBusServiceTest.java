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
package com.infenia.yukta.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.core.WorkflowPlugin;
import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.plugin.message.control.ControlHeartbeat;
import com.infenia.yukta.plugin.message.control.ControlStatistics;
import com.infenia.yukta.service.control.ControlHeartbeatHandler;
import com.infenia.yukta.service.control.ControlSignalHandler;
import com.infenia.yukta.service.control.ControlStatisticsHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ControlBusServiceTest {

  private ControlBusService service;

  @BeforeEach
  void setUp() {
    final List<ControlSignalHandler> handlers =
        List.of(new ControlHeartbeatHandler(), new ControlStatisticsHandler());
    service = new ControlBusService(100, 50, 256, handlers);
    service.init();
  }

  @Test
  void testEmitAndStream() {
    Message<String> msg = DefaultMessage.create(null, "test");

    StepVerifier.create(service.getControlStream())
        .then(() -> service.emit(msg).subscribe())
        .expectNext(msg)
        .thenCancel()
        .verify();
  }

  @Test
  void testHeartbeatAndStatistics() {
    ControlHeartbeat hb = new ControlHeartbeat("node1", 1000L);
    Message<ControlHeartbeat> hbMsg = DefaultMessage.create(null, hb).withSourceNodeId("node1");

    ControlStatistics stats = new ControlStatistics("node1", 100.0, 50.0);
    Message<ControlStatistics> statsMsg =
        DefaultMessage.create(null, stats).withSourceNodeId("node1");

    service.emit(hbMsg).block();
    service.emit(statsMsg).block();

    // Loop to wait for state update
    for (int i = 0; i < 20; i++) {
      if (service.getLastHeartbeat("node1") != null) break;
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
      }
    }

    assertEquals(hbMsg, service.getLastHeartbeat("node1"));
    assertTrue(service.getActiveNodes().contains("node1"));
  }

  @Test
  void testRegisterAndSendCommand() {
    WorkflowPlugin plugin = mock(WorkflowPlugin.class);
    Message<String> cmd = DefaultMessage.create(null, "cmd");
    Message<String> resp = DefaultMessage.create(null, "resp");

    service.registerPlugin("node1", plugin);
    when(plugin.onControlSignal(cmd)).thenReturn(Mono.just(resp));

    StepVerifier.create(service.sendCommand("node1", cmd)).expectNext(resp).verifyComplete();

    service.unregisterPlugin("node1");
    StepVerifier.create(service.sendCommand("node1", cmd))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testInitCustomBufferSize() {
    ControlBusService customService = new ControlBusService(10, 10, 512, List.of());
    customService.init();
    customService.shutdown();
  }

  @Test
  void testInitZeroBufferSize() {
    // bufferSize=0: condition (bufferSize > 0 && bufferSize != 256) is false
    ControlBusService zeroBufferService = new ControlBusService(10, 10, 0, List.of());
    zeroBufferService.init();
    zeroBufferService.shutdown();
  }

  @Test
  void testInitNegativeBufferSize() {
    // bufferSize=-1: condition (bufferSize > 0 && bufferSize != 256) is false
    ControlBusService negativeBufferService = new ControlBusService(10, 10, -1, List.of());
    negativeBufferService.init();
    negativeBufferService.shutdown();
  }

  @Test
  void testBatchProcessingError() throws Exception {
    ControlSignalHandler failingHandler = mock(ControlSignalHandler.class);
    when(failingHandler.canHandle(any())).thenReturn(true);
    // Throw error during handle
    org.mockito.Mockito.doThrow(new RuntimeException("batch fail"))
        .when(failingHandler)
        .handle(any(), any(), any());

    ControlBusService errService = new ControlBusService(1, 1, 256, List.of(failingHandler));
    errService.init();

    Message<String> msg = DefaultMessage.create(null, "payload").withSourceNodeId("node1");
    errService.emit(msg).block();

    // Wait for processing
    Thread.sleep(100);
    errService.shutdown();
  }

  @Test
  void testEmitError() throws Exception {
    // Force a failure in the sink
    ControlBusService serviceWithFullSink = new ControlBusService(100, 50, 256, List.of());
    // Directly close the sink to force emit fail
    java.lang.reflect.Field sinkField = ControlBusService.class.getDeclaredField("controlSink");
    sinkField.setAccessible(true);
    reactor.core.publisher.Sinks.Many<Message<?>> mockSink =
        mock(reactor.core.publisher.Sinks.Many.class);
    org.mockito.Mockito.doThrow(new RuntimeException("sink fail"))
        .when(mockSink)
        .emitNext(any(), any());
    sinkField.set(serviceWithFullSink, mockSink);

    StepVerifier.create(serviceWithFullSink.emit(DefaultMessage.create(null, "d")))
        .expectError(IllegalStateException.class)
        .verify();
  }

  @Test
  void testHandleControlBatchBranches() throws Exception {
    ControlBusService testService = new ControlBusService(100, 50, 256, List.of());
    java.lang.reflect.Method handleBatch =
        ControlBusService.class.getDeclaredMethod("handleControlBatch", List.class);
    handleBatch.setAccessible(true);

    // Case: nodeId is null
    Message<String> msgNoNode = DefaultMessage.create(null, "p");
    handleBatch.invoke(testService, List.of(msgNoNode));

    // Case: payload is null (if it's even possible with DefaultMessage.create)
    // We can't easily create a message with null payload via DefaultMessage.create,
    // but we can mock it.
    Message<?> mockMsg = mock(Message.class);
    when(mockMsg.getPayload()).thenReturn(null);
    when(mockMsg.getSourceNodeId()).thenReturn("node1");
    handleBatch.invoke(testService, List.of(mockMsg));
  }

  @Test
  void testGetActiveNodesEmpty() {
    ControlBusService emptyService = new ControlBusService(100, 50, 256, List.of());
    assertTrue(emptyService.getActiveNodes().isEmpty());
  }

  @Test
  void testGetHeartbeatAndStatsMissing() {
    ControlBusService emptyService = new ControlBusService(100, 50, 256, List.of());
    org.junit.jupiter.api.Assertions.assertNull(emptyService.getLastHeartbeat("n1"));
    org.junit.jupiter.api.Assertions.assertNull(emptyService.getLastStatistics("n1"));
  }
}
