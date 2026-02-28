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
package com.infenia.yukta.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    Message<String> msg =
        new DefaultMessage<>(
            UUID.randomUUID().toString(),
            traceId.toString(),
            null,
            null,
            0L,
            null,
            metadata,
            List.of(),
            null,
            0,
            0,
            0,
            false,
            null,
            null,
            null,
            0,
            "payload",
            java.time.Instant.now(),
            null,
            null);

    assertEquals("value", msg.getMetadata().get("key"));

    try {
      msg.getMetadata().put("new", "val");
    } catch (UnsupportedOperationException e) {
      // expected
    }
  }

  @Test
  void testMessageMethods() {
    UUID traceId = UUID.randomUUID();
    Message<String> msg = DefaultMessage.create(traceId, "payload");
    assertEquals(traceId.toString(), msg.getTraceId());
    assertEquals("payload", msg.getPayload());
    assertNull(msg.getCorrelationId());
    assertNull(msg.getReplyTo());

    Message<String> correlationMsg = msg.withCorrelationId("corr1");
    assertEquals("corr1", correlationMsg.getCorrelationId());

    Message<String> replyMsg = correlationMsg.withReplyTo("replyChannel");
    assertEquals("replyChannel", replyMsg.getReplyTo());
    assertEquals("corr1", replyMsg.getCorrelationId());

    Message<String> stamped = replyMsg.withSourceNodeId("node1");
    assertEquals("node1", stamped.getSourceNodeId());

    Message<String> ported = stamped.withSourcePort("port1");
    assertEquals("port1", ported.getSourcePort());
    assertEquals("node1", ported.getSourceNodeId());

    Message<String> historied = ported.withAddedHistory("comp1").withAddedHistory("comp2");
    assertEquals(List.of("comp1", "comp2"), historied.getMessageHistory());

    Message<String> sequenced = historied.withSequence("seq1", 1, 5);
    assertEquals("seq1", sequenced.getSequenceId());
    assertEquals(1, sequenced.getSequenceNumber());
    assertEquals(5, sequenced.getSequenceSize());
    assertFalse(sequenced.isLastInSequence());

    Message<String> lastSequenced = sequenced.withSequence("seq1", 5, 5);
    assertTrue(lastSequenced.isLastInSequence());

    Message<String> prioritized = lastSequenced.withPriority(10);
    assertEquals(10, prioritized.getPriority());

    Message<String> controlled = prioritized.withControl(true);
    assertTrue(controlled.isControlMessage());

    Message<String> failed = controlled.withFailure("error-port", "Bad Data", "Invalid format");
    assertEquals("error-port", failed.getOrigDest());
    assertEquals("Bad Data", failed.getFailureReason());
    assertEquals("Invalid format", failed.getExceptionDetail());

    Message<String> retried = failed.withRetryCount(3);
    assertEquals(3, retried.getRetryCount());

    Message<String> incremented = retried.withIncrementedRetry();
    assertEquals(4, incremented.getRetryCount());

    Message<Integer> rePayloaded = incremented.withPayload(123);
    assertEquals(123, rePayloaded.getPayload());
    assertEquals("error-port", rePayloaded.getOrigDest());
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
    org.junit.jupiter.api.Assertions.assertTrue(plugin.getOutputPorts().size() == 1);
    assertEquals("default", plugin.getOutputPorts().get(0));
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
          public Flux<Message<?>> start(Map<String, Object> config) {
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
          public Flux<Message<?>> process(Flux<Message<?>> input, Map<String, Object> config) {
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
          public Mono<Void> consume(Flux<Message<?>> input, Map<String, Object> config) {
            return input.then();
          }
        };
    assertEquals(PluginCategory.TERMINAL, terminal.getCategory());
  }

  @Test
  void testResultCollector() {
    ResultCollector collector = new ResultCollector();
    Message<String> msg1 = DefaultMessage.create(UUID.randomUUID(), "res1");
    Message<String> msg2 = DefaultMessage.create(UUID.randomUUID(), "res2");

    collector.add(msg1);
    collector.add(msg2);
    collector.add(null);

    List<Message<?>> results = collector.getResults();
    assertEquals(2, results.size());
    assertEquals("res1", results.get(0).getPayload());
    assertEquals("res2", results.get(1).getPayload());

    collector.clear();
    assertEquals(0, collector.getResults().size());
  }
}
