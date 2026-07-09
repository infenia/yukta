// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.exception.FilterEvaluationException;
import com.infenia.yukta.plugin.exception.JoinTimeoutException;
import com.infenia.yukta.plugin.exception.NoMatchingBranchException;
import com.infenia.yukta.plugin.gateway.ResultCollector;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import com.infenia.yukta.plugin.type.TriggerPlugin;
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

    java.time.Instant now = java.time.Instant.now();
    Message<Integer> updated =
        rePayloaded
            .withTraceId("new-trace")
            .withTimestamp(now)
            .withExpiration(123456789L)
            .withFormatIndicator("json-v2");

    assertEquals("new-trace", updated.getTraceId());
    assertEquals(now.toEpochMilli(), updated.getTimestamp());
    assertEquals(123456789L, updated.getExpiration());
    assertEquals("json-v2", updated.getFormatIndicator());
  }

  @Test
  void testDefaultWorkflowPluginMethods() {
    Plugin plugin = new MockPlugin();

    // Lifecycle methods
    StepVerifier.create(plugin.initialize(Map.of())).verifyComplete();
    StepVerifier.create(plugin.prepare(Map.of())).verifyComplete();
    StepVerifier.create(plugin.validateConfig(Map.of())).verifyComplete();
    StepVerifier.create(plugin.shutdown(Map.of())).verifyComplete();

    // Metadata methods
    assertEquals("", plugin.getDescription());
    assertEquals("", plugin.getUsagePattern());
    assertEquals(30, plugin.getDefaultTimeout().toSeconds());
    assertFalse(plugin.getUiDesign().isPresent());

    // Port methods
    assertEquals(List.of("default"), plugin.getOutputPorts());
    assertEquals(List.of("default"), plugin.getOutputPorts(Map.of()));

    // Evaluation methods
    assertFalse(plugin.isBlocking());
    assertFalse(plugin.isComputationallyHeavy(Map.of()));
    assertFalse(plugin.suppressOptimizationHint(Map.of()));

    // Context & Signal methods
    WorkflowContext context = new WorkflowContext("node1", List.of(), List.of());
    StepVerifier.create(plugin.validateInContext(context, Map.of())).verifyComplete();

    Message<String> signal = DefaultMessage.create(UUID.randomUUID(), "signal");
    StepVerifier.create(plugin.onControlSignal(signal)).verifyComplete();
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

  private static class MockPlugin implements Plugin {
    @Override
    public String getType() {
      return "test";
    }

    @Override
    public PluginCategory getCategory() {
      return PluginCategory.TRIGGER;
    }
  }
}
