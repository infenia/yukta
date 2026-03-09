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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.core.WorkflowPlugin;
import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.plugin.message.control.ControlHeartbeat;
import com.infenia.yukta.plugin.message.control.ControlStatistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ControlBusServiceTest {

  private ControlBusService service;

  @BeforeEach
  void setUp() {
    service = new ControlBusService();
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
    ControlHeartbeat hb = mock(ControlHeartbeat.class);
    Message<ControlHeartbeat> hbMsg = DefaultMessage.create(null, hb).withSourceNodeId("node1");

    ControlStatistics stats = mock(ControlStatistics.class);
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
}
