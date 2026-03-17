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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.core.WorkflowPlugin;
import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class DefaultControlBusGatewayTest {

  private ControlBusService controlBusService;
  private DefaultControlBusGateway gateway;

  @BeforeEach
  void setUp() {
    controlBusService = mock(ControlBusService.class);
    gateway = new DefaultControlBusGateway(controlBusService);
  }

  @Test
  void testEmit() {
    Message<String> message = DefaultMessage.create(UUID.randomUUID(), "test");
    when(controlBusService.emit(message)).thenReturn(Mono.empty());

    StepVerifier.create(gateway.emit(message)).verifyComplete();

    verify(controlBusService).emit(message);
  }

  @Test
  void testRegisterPlugin() {
    String nodeId = "node1";
    WorkflowPlugin plugin = mock(WorkflowPlugin.class);

    gateway.registerPlugin(nodeId, plugin);

    verify(controlBusService).registerPlugin(nodeId, plugin);
  }

  @Test
  void testUnregisterPlugin() {
    String nodeId = "node1";

    gateway.unregisterPlugin(nodeId);

    verify(controlBusService).unregisterPlugin(nodeId);
  }

  @Test
  @SuppressWarnings("unchecked")
  void testSendCommand() {
    String nodeId = "node1";
    Message<String> command = DefaultMessage.create(UUID.randomUUID(), "cmd");
    Message<String> response = DefaultMessage.create(UUID.randomUUID(), "resp");
    when(controlBusService.sendCommand(eq(nodeId), any(Message.class)))
        .thenReturn(Mono.just(response));

    StepVerifier.create(gateway.sendCommand(nodeId, command)).expectNext(response).verifyComplete();

    verify(controlBusService).sendCommand(eq(nodeId), any(Message.class));
  }

  @Test
  void testGetLastHeartbeat() {
    String nodeId = "node1";
    Message<?> heartbeat = mock(Message.class);
    doReturn(heartbeat).when(controlBusService).getLastHeartbeat(nodeId);

    Message<?> result = gateway.getLastHeartbeat(nodeId);

    assertEquals(heartbeat, result);
    verify(controlBusService).getLastHeartbeat(nodeId);
  }

  @Test
  void testGetLastStatistics() {
    String nodeId = "node1";
    Message<?> stats = mock(Message.class);
    doReturn(stats).when(controlBusService).getLastStatistics(nodeId);

    Message<?> result = gateway.getLastStatistics(nodeId);

    assertEquals(stats, result);
    verify(controlBusService).getLastStatistics(nodeId);
  }

  @Test
  void testGetActiveNodes() {
    List<String> activeNodes = List.of("node1", "node2");
    when(controlBusService.getActiveNodes()).thenReturn(activeNodes);

    List<String> result = gateway.getActiveNodes();

    assertEquals(activeNodes, result);
    verify(controlBusService).getActiveNodes();
  }
}
