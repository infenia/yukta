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
package com.infenia.yukta.service.control.directive;

import static org.assertj.core.api.Assertions.*;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.control.ControlHeartbeat;
import com.infenia.yukta.model.control.ControlStatistics;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.CommentRequired",
  "PMD.ShortVariable",
  "PMD.UncommentedEmptyMethodBody"
})
@NoArgsConstructor
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

    assertThat(heartbeatHandler.canHandle(hb.getPayload())).isTrue();
    assertThat(statisticsHandler.canHandle(hb.getPayload())).isFalse();

    assertThat(statisticsHandler.canHandle(stats.getPayload())).isTrue();
    assertThat(heartbeatHandler.canHandle(stats.getPayload())).isFalse();
  }

  @Test
  void testHeartbeatHandlerStoresAndRetrievesMessages() {
    final Message<?> msg =
        DefaultMessage.create(null, new ControlHeartbeat("node1", 1000L)).withSourceNodeId("node1");

    heartbeatHandler.handle("node1", msg, msg.getPayload());

    assertThat(heartbeatHandler.getLastHeartbeat("node1")).isEqualTo(msg);
    assertThat(heartbeatHandler.getActiveNodes()).isEqualTo(List.of("node1"));
  }

  @Test
  void testHeartbeatHandlerRemovesNode() {
    final Message<?> msg =
        DefaultMessage.create(null, new ControlHeartbeat("node1", 1000L)).withSourceNodeId("node1");

    heartbeatHandler.handle("node1", msg, msg.getPayload());
    assertThat(heartbeatHandler.getLastHeartbeat("node1")).isEqualTo(msg);

    heartbeatHandler.removeNode("node1");

    assertThat(heartbeatHandler.getLastHeartbeat("node1")).isNull();
    assertThat(heartbeatHandler.getActiveNodes()).isEqualTo(List.of());
  }

  @Test
  void testStatisticsHandlerStoresAndRetrievesMessages() {
    final Message<?> msg =
        DefaultMessage.create(null, new ControlStatistics("node1", 100.0, 50.0))
            .withSourceNodeId("node1");

    statisticsHandler.handle("node1", msg, msg.getPayload());

    assertThat(statisticsHandler.getLastStatistics("node1")).isEqualTo(msg);
  }

  @Test
  void testStatisticsHandlerRemovesNode() {
    final Message<?> msg =
        DefaultMessage.create(null, new ControlStatistics("node1", 100.0, 50.0))
            .withSourceNodeId("node1");

    statisticsHandler.handle("node1", msg, msg.getPayload());
    assertThat(statisticsHandler.getLastStatistics("node1")).isEqualTo(msg);

    statisticsHandler.removeNode("node1");

    assertThat(statisticsHandler.getLastStatistics("node1")).isNull();
  }

  @Test
  void testDefaultMethods() {
    final ControlSignalHandler dummy =
        new ControlSignalHandler() {
          @Override
          public boolean canHandle(final Object payload) {
            return false;
          }

          @Override
          public void handle(final String nodeId, final Message<?> message, final Object payload) {}
        };

    assertThat(dummy.getLastHeartbeat("node1")).isNull();
    assertThat(dummy.getLastStatistics("node1")).isNull();
    assertThat(dummy.getActiveNodes()).isEqualTo(List.of());
    dummy.removeNode("node1"); // Cover default no-op
  }
}
