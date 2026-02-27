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
package com.infenia.yukta.plugin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.infenia.yukta.plugin.Message;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class ConsoleTerminalPluginTest {

  @Test
  void testConsume() {
    ConsoleTerminalPlugin plugin = new ConsoleTerminalPlugin();
    Message message = Message.create(UUID.randomUUID(), Map.of("key", "value"));

    StepVerifier.create(plugin.consume(Flux.just(message), Map.of())).verifyComplete();
  }

  @Test
  void testGetType() {
    assertEquals("console", new ConsoleTerminalPlugin().getType());
  }

  @Test
  void testMetadata() {
    ConsoleTerminalPlugin plugin = new ConsoleTerminalPlugin();
    assertEquals("Logs message payloads to the console/logger.", plugin.getDescription());
    assertEquals(
        "Consumes messages and prints their payload to the application logs. No configuration"
            + " required.",
        plugin.getUsagePattern());
    org.junit.jupiter.api.Assertions.assertTrue(plugin.getUiDesign().isPresent());
    assertEquals(120, plugin.getUiDesign().get().width());
    assertEquals(80, plugin.getUiDesign().get().height());
    org.junit.jupiter.api.Assertions.assertTrue(
        plugin.getUiDesign().get().html().contains("terminal"));
  }

  @Test
  void testLifecycle() {
    ConsoleTerminalPlugin plugin = new ConsoleTerminalPlugin();
    StepVerifier.create(plugin.validateConfig(Map.of())).verifyComplete();
    StepVerifier.create(plugin.initialize(Map.of())).verifyComplete();
  }
}
