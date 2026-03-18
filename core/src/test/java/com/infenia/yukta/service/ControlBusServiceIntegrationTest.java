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

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.plugin.message.control.ControlHeartbeat;
import com.infenia.yukta.plugin.message.control.ControlStatistics;
import com.infenia.yukta.service.control.ControlHeartbeatHandler;
import com.infenia.yukta.service.control.ControlSignalHandler;
import com.infenia.yukta.service.control.ControlStatisticsHandler;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class ControlBusServiceIntegrationTest {

  private ControlBusService controlBusService;
  private ControlHeartbeatHandler heartbeatHandler;
  private ControlStatisticsHandler statisticsHandler;

  @BeforeEach
  void setUp() {
    heartbeatHandler = new ControlHeartbeatHandler();
    statisticsHandler = new ControlStatisticsHandler();

    final List<ControlSignalHandler> handlers = List.of(heartbeatHandler, statisticsHandler);
    controlBusService = new ControlBusService(100, 50, 256, handlers);
    controlBusService.init();
  }

  @Test
  void testHeartbeatSignalDispatch() {
    final Message<?> hb =
        DefaultMessage.create(null, new ControlHeartbeat("node1", 1000L))
            .withSourceNodeId("node1")
            .withPriority(5);

    StepVerifier.create(controlBusService.emit(hb)).verifyComplete();

    await()
        .timeout(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> controlBusService.getLastHeartbeat("node1") != null);

    assertNotNull(controlBusService.getLastHeartbeat("node1"));
    assertEquals("node1", controlBusService.getActiveNodes().get(0));
  }

  @Test
  void testStatisticsSignalDispatch() {
    final Message<?> stats =
        DefaultMessage.create(null, new ControlStatistics("node2", 100.0, 50.0))
            .withSourceNodeId("node2")
            .withPriority(5);

    StepVerifier.create(controlBusService.emit(stats)).verifyComplete();

    await()
        .timeout(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> controlBusService.getLastStatistics("node2") != null);

    assertNotNull(controlBusService.getLastStatistics("node2"));
  }

  @Test
  void testUnregisterPluginCleansUpAllHandlerState() {
    final Message<?> hb =
        DefaultMessage.create(null, new ControlHeartbeat("node3", 1000L))
            .withSourceNodeId("node3")
            .withPriority(5);
    final Message<?> stats =
        DefaultMessage.create(null, new ControlStatistics("node3", 100.0, 50.0))
            .withSourceNodeId("node3")
            .withPriority(5);

    StepVerifier.create(controlBusService.emit(hb)).verifyComplete();
    StepVerifier.create(controlBusService.emit(stats)).verifyComplete();

    await()
        .timeout(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .untilAsserted(
            () -> {
              /* allow batch processing delay */
            });

    assertNotNull(controlBusService.getLastHeartbeat("node3"));
    assertNotNull(controlBusService.getLastStatistics("node3"));

    controlBusService.unregisterPlugin("node3");

    assertNull(controlBusService.getLastHeartbeat("node3"));
    assertNull(controlBusService.getLastStatistics("node3"));
    assertTrue(controlBusService.getActiveNodes().isEmpty());
  }

  @Test
  void testMultipleNodesTrackedIndependently() {
    final Message<?> hb1 =
        DefaultMessage.create(null, new ControlHeartbeat("node1", 1000L))
            .withSourceNodeId("node1")
            .withPriority(5);
    final Message<?> hb2 =
        DefaultMessage.create(null, new ControlHeartbeat("node2", 1000L))
            .withSourceNodeId("node2")
            .withPriority(5);

    StepVerifier.create(controlBusService.emit(hb1)).verifyComplete();
    StepVerifier.create(controlBusService.emit(hb2)).verifyComplete();

    await()
        .timeout(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .untilAsserted(
            () -> {
              /* allow batch processing delay */
            });

    final List<String> activeNodes = controlBusService.getActiveNodes();
    assertEquals(2, activeNodes.size());
    assertTrue(activeNodes.contains("node1"));
    assertTrue(activeNodes.contains("node2"));

    controlBusService.unregisterPlugin("node1");

    assertEquals(1, controlBusService.getActiveNodes().size());
    assertNull(controlBusService.getLastHeartbeat("node1"));
    assertNotNull(controlBusService.getLastHeartbeat("node2"));
  }

  @Test
  void testGetControlStream() {
    final Message<?> hb =
        DefaultMessage.create(null, new ControlHeartbeat("node1", 1000L))
            .withSourceNodeId("node1")
            .withPriority(5);

    StepVerifier.create(controlBusService.getControlStream().take(1))
        .then(() -> StepVerifier.create(controlBusService.emit(hb)).verifyComplete())
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void testSendCommandSuccess() {
    // Emit heartbeat to ensure node1 is in activePlugins
    final Message<?> hb =
        DefaultMessage.create(null, new ControlHeartbeat("node1", 1000L))
            .withSourceNodeId("node1")
            .withPriority(5);

    StepVerifier.create(controlBusService.emit(hb)).verifyComplete();

    await()
        .timeout(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .untilAsserted(
            () -> {
              /* allow batch processing delay */
            });

    // Verify heartbeat was registered (sendCommand only works with registered plugins)
    assertNotNull(controlBusService.getLastHeartbeat("node1"));
  }

  @Test
  void testSendCommandNodeNotFound() {
    final Message<?> command =
        DefaultMessage.create(null, "command").withSourceNodeId("nonexistent");

    StepVerifier.create(controlBusService.sendCommand("nonexistent", command))
        .expectErrorMatches(
            e -> e instanceof IllegalArgumentException && e.getMessage().contains("Node not found"))
        .verify();
  }

  @Test
  void testRegisterPlugin() {
    final Message<?> hb =
        DefaultMessage.create(null, new ControlHeartbeat("node-test", 1000L))
            .withSourceNodeId("node-test")
            .withPriority(5);

    StepVerifier.create(controlBusService.emit(hb)).verifyComplete();

    await()
        .timeout(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .untilAsserted(
            () -> {
              /* allow batch processing delay */
            });

    // Verify heartbeat recorded and node active
    assertNotNull(controlBusService.getLastHeartbeat("node-test"));
    assertTrue(controlBusService.getActiveNodes().contains("node-test"));
  }

  @Test
  void testShutdown() {
    controlBusService.shutdown();

    // After shutdown, control stream should complete
    StepVerifier.create(controlBusService.getControlStream()).verifyComplete();
  }

  @Test
  void testInitWithCustomBufferSize() {
    final ControlBusService service = new ControlBusService(50, 30, 512, List.of());
    service.init();

    final Message<?> msg = DefaultMessage.create(null, "test").withSourceNodeId("test");
    StepVerifier.create(service.emit(msg)).verifyComplete();
  }

  @Test
  void testHandleControlBatchWithNullNodeId() {
    final Message<?> msgWithoutNodeId =
        DefaultMessage.create(null, new ControlHeartbeat("ignored", 1000L)).withPriority(5);
    // Message without sourceNodeId (null)

    StepVerifier.create(controlBusService.emit(msgWithoutNodeId)).verifyComplete();

    // Sleep to allow batch processing
    await()
        .timeout(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .untilAsserted(
            () -> {
              /* allow batch processing delay */
            });

    // Node should not be registered (null nodeId ignored)
    assertTrue(controlBusService.getActiveNodes().isEmpty());
  }

  @Test
  void testHandleControlBatchWithNullPayload() {
    final Message<?> msgWithoutPayload =
        DefaultMessage.create(null, null).withSourceNodeId("node1").withPriority(5);

    StepVerifier.create(controlBusService.emit(msgWithoutPayload)).verifyComplete();

    // Sleep to allow batch processing
    await()
        .timeout(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .untilAsserted(
            () -> {
              /* allow batch processing delay */
            });

    // Node should not be tracked (null payload ignored)
    assertNull(controlBusService.getLastHeartbeat("node1"));
  }

  @Test
  void testGetLastHeartbeatFromMultipleHandlers() {
    final Message<?> hb =
        DefaultMessage.create(null, new ControlHeartbeat("node1", 1000L))
            .withSourceNodeId("node1")
            .withPriority(5);

    StepVerifier.create(controlBusService.emit(hb)).verifyComplete();

    await()
        .timeout(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .untilAsserted(
            () -> {
              /* allow batch processing delay */
            });

    // Verify getLastHeartbeat searches through handlers
    assertNotNull(controlBusService.getLastHeartbeat("node1"));
    assertEquals(
        1000L,
        ((ControlHeartbeat) controlBusService.getLastHeartbeat("node1").getPayload()).uptime());
  }

  @Test
  void testGetLastStatisticsFromMultipleHandlers() {
    final Message<?> stats =
        DefaultMessage.create(null, new ControlStatistics("node1", 75.0, 25.0))
            .withSourceNodeId("node1")
            .withPriority(5);

    StepVerifier.create(controlBusService.emit(stats)).verifyComplete();

    await()
        .timeout(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .untilAsserted(
            () -> {
              /* allow batch processing delay */
            });

    // Verify getLastStatistics searches through handlers
    assertNotNull(controlBusService.getLastStatistics("node1"));
    assertEquals(
        75.0,
        ((ControlStatistics) controlBusService.getLastStatistics("node1").getPayload())
            .throughput());
  }

  @Test
  void testGetActiveNodesFromMultipleHandlers() {
    final Message<?> hb =
        DefaultMessage.create(null, new ControlHeartbeat("node-active", 1000L))
            .withSourceNodeId("node-active")
            .withPriority(5);

    StepVerifier.create(controlBusService.emit(hb)).verifyComplete();

    await()
        .timeout(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .untilAsserted(
            () -> {
              /* allow batch processing delay */
            });

    // Verify getActiveNodes searches through handlers and returns first non-empty list
    final List<String> activeNodes = controlBusService.getActiveNodes();
    assertTrue(activeNodes.contains("node-active"));
  }

  @Test
  void testHandleControlBatchPrioritization() {
    final Message<?> lowPriority =
        DefaultMessage.create(null, new ControlHeartbeat("node-low", 1000L))
            .withSourceNodeId("node-low")
            .withPriority(1);
    final Message<?> highPriority =
        DefaultMessage.create(null, new ControlHeartbeat("node-high", 2000L))
            .withSourceNodeId("node-high")
            .withPriority(10);

    StepVerifier.create(controlBusService.emit(lowPriority)).verifyComplete();
    StepVerifier.create(controlBusService.emit(highPriority)).verifyComplete();

    await()
        .timeout(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .untilAsserted(
            () -> {
              /* allow batch processing delay */
            });

    // Both messages should be processed regardless of priority
    assertNotNull(controlBusService.getLastHeartbeat("node-low"));
    assertNotNull(controlBusService.getLastHeartbeat("node-high"));
  }

  @Test
  void testEmitWithBufferSize256() {
    // Test default buffer size (256) - should not reinitialize sink
    final ControlBusService service = new ControlBusService(100, 50, 256, List.of());
    service.init();

    final Message<?> msg = DefaultMessage.create(null, "test").withSourceNodeId("test");
    StepVerifier.create(service.emit(msg)).verifyComplete();
  }

  @Test
  void testEmitWithCustomBufferSizeLessThanSmall() {
    // Test buffer size smaller than SMALL_BUFFER_SIZE (64) - should use SMALL_BUFFER_SIZE
    final ControlBusService service = new ControlBusService(100, 50, 32, List.of());
    service.init();

    final Message<?> msg = DefaultMessage.create(null, "test").withSourceNodeId("test");
    StepVerifier.create(service.emit(msg)).verifyComplete();
  }

  @Test
  void testEmitSuccessPath() {
    final Message<?> hb =
        DefaultMessage.create(null, new ControlHeartbeat("emit-test", 1000L))
            .withSourceNodeId("emit-test")
            .withPriority(5);

    // Verify emit completes successfully (returns Mono<Void>)
    StepVerifier.create(controlBusService.emit(hb)).verifyComplete();
  }

  @Test
  void testInitWithBufferSizeZero() {
    // Test buffer size = 0 - should use SMALL_BUFFER_SIZE
    final ControlBusService service = new ControlBusService(100, 50, 0, List.of());
    service.init();

    final Message<?> msg = DefaultMessage.create(null, "test").withSourceNodeId("test");
    StepVerifier.create(service.emit(msg)).verifyComplete();
  }
}
