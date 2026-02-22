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
package com.infenia.jagratha.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class PluginInterfaceTest {

  @Test
  void testMessageImmutability() {
    UUID traceId = UUID.randomUUID();
    Map<String, Object> metadata = new java.util.HashMap<>();
    metadata.put("key", "value");
    Message msg =
        new Message(UUID.randomUUID(), traceId, metadata, "payload", java.time.Instant.now());

    assertEquals("value", msg.metadata().get("key"));

    try {
      msg.metadata().put("new", "val");
    } catch (UnsupportedOperationException e) {
      // expected
    }
  }

  @Test
  void testDefaultWorkflowPluginMethods() {
    WorkflowPlugin plugin =
        new WorkflowPlugin() {
          @Override
          public String getType() {
            return "test";
          }

          @Override
          public PluginCategory getCategory() {
            return PluginCategory.TRIGGER;
          }
        };

    StepVerifier.create(plugin.validateConfig(Map.of())).verifyComplete();
    StepVerifier.create(plugin.initialize(Map.of())).verifyComplete();
  }

  @Test
  void testTriggerPlugin() {
    TriggerPlugin trigger =
        new TriggerPlugin() {
          @Override
          public String getType() {
            return "test";
          }

          @Override
          public Flux<Message> start(Map<String, Object> config, Map<String, Object> payload) {
            return Flux.empty();
          }
        };
    assertEquals(PluginCategory.TRIGGER, trigger.getCategory());
  }

  @Test
  void testProcessorPlugin() {
    ProcessorPlugin processor =
        new ProcessorPlugin() {
          @Override
          public String getType() {
            return "test";
          }

          @Override
          public Flux<Message> process(Flux<Message> input, Map<String, Object> config) {
            return input;
          }
        };
    assertEquals(PluginCategory.PROCESSOR, processor.getCategory());
  }

  @Test
  void testTerminalPlugin() {
    TerminalPlugin terminal =
        new TerminalPlugin() {
          @Override
          public String getType() {
            return "test";
          }

          @Override
          public Mono<Void> consume(Flux<Message> input, Map<String, Object> config) {
            return input.then();
          }
        };
    assertEquals(PluginCategory.TERMINAL, terminal.getCategory());
  }
}
