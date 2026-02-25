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

import java.util.List;
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
        new Message(
            UUID.randomUUID(), traceId, metadata, "payload", java.time.Instant.now(), null, null);

    assertEquals("value", msg.metadata().get("key"));

    try {
      msg.metadata().put("new", "val");
    } catch (UnsupportedOperationException e) {
      // expected
    }
  }

  @Test
  void testMessageMethods() {
    UUID traceId = UUID.randomUUID();
    Message msg = Message.create(traceId, "payload");
    assertEquals(traceId, msg.traceId());
    assertEquals("payload", msg.payload());

    Message stamped = msg.withSourceNodeId("node1");
    assertEquals("node1", stamped.sourceNodeId());

    Message ported = stamped.withSourcePort("port1");
    assertEquals("port1", ported.sourcePort());
    assertEquals("node1", ported.sourceNodeId());
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
    assertEquals("", plugin.getDescription());
    assertEquals("", plugin.getUsagePattern());
    assertEquals(30, plugin.getDefaultTimeout().getSeconds());
    org.junit.jupiter.api.Assertions.assertFalse(plugin.getUiDesign().isPresent());
    org.junit.jupiter.api.Assertions.assertTrue(plugin.getOutputPorts().isEmpty());
  }

  @Test
  void testUiDesign() {
    UiDesign design = new UiDesign("<div>test</div>", 100, 50);
    assertEquals("<div>test</div>", design.html());
    assertEquals(100, design.width());
    assertEquals(50, design.height());
  }

  @Test
  void testExceptions() {
    FilterEvaluationException fe = new FilterEvaluationException("filter error");
    assertEquals("filter error", fe.getMessage());

    JoinTimeoutException jte = new JoinTimeoutException("join timeout");
    assertEquals("join timeout", jte.getMessage());

    NoMatchingBranchException nme = new NoMatchingBranchException("no branch");
    assertEquals("no branch", nme.getMessage());
  }

  @Test
  void testDefaultWorkflowPluginExtraMethods() {
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

    StepVerifier.create(plugin.shutdown(Map.of())).verifyComplete();
    StepVerifier.create(plugin.initialize(Map.of())).verifyComplete();
    StepVerifier.create(plugin.validateConfig(Map.of())).verifyComplete();

    java.time.Duration timeout = plugin.getDefaultTimeout();
    assertEquals(30, timeout.getSeconds());
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
          public Flux<Message> start(Map<String, Object> config) {
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

  @Test
  void testResultCollector() {
    ResultCollector collector = new ResultCollector();
    Message msg1 = Message.create(UUID.randomUUID(), "res1");
    Message msg2 = Message.create(UUID.randomUUID(), "res2");

    collector.add(msg1);
    collector.add(msg2);
    collector.add(null);

    List<Message> results = collector.getResults();
    assertEquals(2, results.size());
    assertEquals("res1", results.get(0).payload());
    assertEquals("res2", results.get(1).payload());

    collector.clear();
    assertEquals(0, collector.getResults().size());
  }
}
