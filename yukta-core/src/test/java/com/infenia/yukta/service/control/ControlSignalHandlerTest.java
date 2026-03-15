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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.plugin.message.control.ControlHeartbeat;
import com.infenia.yukta.plugin.message.control.ControlStatistics;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ControlSignalHandlerTest {

  private ControlHeartbeatHandler heartbeatHandler;
  private ControlStatisticsHandler statisticsHandler;

  @BeforeEach
  void setUp() {
    heartbeatHandler = new ControlHeartbeatHandler();
    statisticsHandler = new ControlStatisticsHandler();
  }

  @Test
  void testHeartbeatHandlerCanHandle() {
    final Message<?> hb =
        DefaultMessage.create(null, new ControlHeartbeat("node1", 1000L)).withSourceNodeId("node1");
    final Message<?> stats =
        DefaultMessage.create(null, new ControlStatistics("node1", 100.0, 50.0))
            .withSourceNodeId("node1");

    assertTrue(heartbeatHandler.canHandle(hb.getPayload()));
    assertTrue(!statisticsHandler.canHandle(hb.getPayload()));

    assertTrue(statisticsHandler.canHandle(stats.getPayload()));
    assertTrue(!heartbeatHandler.canHandle(stats.getPayload()));
  }

  @Test
  void testHeartbeatHandlerStoresAndRetrievesMessages() {
    final Message<?> msg =
        DefaultMessage.create(null, new ControlHeartbeat("node1", 1000L)).withSourceNodeId("node1");

    heartbeatHandler.handle("node1", msg, msg.getPayload());

    assertEquals(msg, heartbeatHandler.getLastHeartbeat("node1"));
    assertEquals(List.of("node1"), heartbeatHandler.getActiveNodes());
  }

  @Test
  void testHeartbeatHandlerRemovesNode() {
    final Message<?> msg =
        DefaultMessage.create(null, new ControlHeartbeat("node1", 1000L)).withSourceNodeId("node1");

    heartbeatHandler.handle("node1", msg, msg.getPayload());
    assertEquals(msg, heartbeatHandler.getLastHeartbeat("node1"));

    heartbeatHandler.removeNode("node1");

    assertNull(heartbeatHandler.getLastHeartbeat("node1"));
    assertEquals(List.of(), heartbeatHandler.getActiveNodes());
  }

  @Test
  void testStatisticsHandlerStoresAndRetrievesMessages() {
    final Message<?> msg =
        DefaultMessage.create(null, new ControlStatistics("node1", 100.0, 50.0))
            .withSourceNodeId("node1");

    statisticsHandler.handle("node1", msg, msg.getPayload());

    assertEquals(msg, statisticsHandler.getLastStatistics("node1"));
  }

  @Test
  void testStatisticsHandlerRemovesNode() {
    final Message<?> msg =
        DefaultMessage.create(null, new ControlStatistics("node1", 100.0, 50.0))
            .withSourceNodeId("node1");

    statisticsHandler.handle("node1", msg, msg.getPayload());
    assertEquals(msg, statisticsHandler.getLastStatistics("node1"));

    statisticsHandler.removeNode("node1");

    assertNull(statisticsHandler.getLastStatistics("node1"));
  }

  @Test
  void testDefaultMethods() {
    ControlSignalHandler dummy =
        new ControlSignalHandler() {
          @Override
          public boolean canHandle(Object payload) {
            return false;
          }

          @Override
          public void handle(String nodeId, Message<?> message, Object payload) {}
        };

    assertNull(dummy.getLastHeartbeat("node1"));
    assertNull(dummy.getLastStatistics("node1"));
    assertEquals(List.of(), dummy.getActiveNodes());
    dummy.removeNode("node1"); // Cover default no-op
  }
}
