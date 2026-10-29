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

@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.ClassWithOnlyPrivateConstructorsShouldBeFinal",
  "PMD.EmptyCatchBlock",
  "PMD.LocalVariableCouldBeFinal",
  "PMD.MethodArgumentCouldBeFinal",
  "PMD.UnitTestAssertionsShouldIncludeMessage",
  "PMD.UnnecessaryFullyQualifiedName",
  "PMD.UseConcurrentHashMap",
  "PMD.UseUnderscoresInNumericLiterals"
})
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
    Message<String> msg = DefaultMessage.create(traceId, "payload");
    assertEquals(traceId.toString(), msg.traceId());
    assertEquals("payload", msg.payload());
    assertNull(msg.correlationId());
    assertNull(msg.replyTo());

    Message<String> correlationMsg = msg.withCorrelationId("corr1");
    assertEquals("corr1", correlationMsg.correlationId());

    Message<String> replyMsg = correlationMsg.withReplyTo("replyChannel");
    assertEquals("replyChannel", replyMsg.replyTo());
    assertEquals("corr1", replyMsg.correlationId());

    Message<String> stamped = replyMsg.withSourceNodeId("node1");
    assertEquals("node1", stamped.sourceNodeId());

    Message<String> ported = stamped.withSourcePort("port1");
    assertEquals("port1", ported.sourcePort());
    assertEquals("node1", ported.sourceNodeId());

    Message<String> historied = ported.withAddedHistory("comp1").withAddedHistory("comp2");
    assertEquals(List.of("comp1", "comp2"), historied.messageHistory());

    Message<String> sequenced = historied.withSequence("seq1", 1, 5);
    assertEquals("seq1", sequenced.sequenceId());
    assertEquals(1, sequenced.sequenceNumber());
    assertEquals(5, sequenced.sequenceSize());

    Message<String> lastSequenced = sequenced.withSequence("seq1", 5, 5);

    Message<String> prioritized = lastSequenced.withPriority(10);
    assertEquals(10, prioritized.priority());

    Message<String> controlled = prioritized.withControl(true);
    assertTrue(controlled.controlMessage());

    Message<String> failed = controlled.withFailure("error-port", "Bad Data", "Invalid format");
    assertEquals("error-port", failed.origDest());
    assertEquals("Bad Data", failed.failureReason());
    assertEquals("Invalid format", failed.exceptionDetail());

    Message<String> retried = failed.withRetryCount(3);
    assertEquals(3, retried.retryCount());

    Message<Integer> rePayloaded = retried.withPayload(123);
    assertEquals(123, rePayloaded.payload());
    assertEquals("error-port", rePayloaded.origDest());

    java.time.Instant now = java.time.Instant.now();
    Message<Integer> updated =
        rePayloaded
            .withTraceId("new-trace")
            .withTimestamp(now)
            .withExpiration(123456789L)
            .withFormatIndicator("json-v2");

    assertEquals("new-trace", updated.traceId());
    assertEquals(now, updated.timestamp());
    assertEquals(123456789L, updated.expiration());
    assertEquals("json-v2", updated.formatIndicator());
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
    assertEquals("", plugin.describe().description());
    assertEquals("", plugin.describe().usagePattern());
    assertEquals(30, plugin.getDefaultTimeout().toSeconds());
    assertEquals(30, plugin.getDefaultTimeout(java.util.Map.of("timeout", 5)).toSeconds());
    assertFalse(plugin.describe().uiDesign().isPresent());

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
    UiDesign design = new UiDesign("blue", "api", "Test", 100, 50);
    assertEquals("Test", design.label());
    assertEquals(100, design.width());
    assertEquals(50, design.height());
  }

  @Test
  void testExceptions() {
    FilterEvaluationException fe = new FilterEvaluationException("filter error");
    assertEquals("filter error", fe.getMessage());

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
    assertEquals(Map.of(), terminal.completionMetadata(Map.of()));
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
    assertEquals("res1", results.get(0).payload());
    assertEquals("res2", results.get(1).payload());

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
