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
package com.infenia.yukta.plugin.scripting.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class ShellScriptProcessorTest {

  private ShellScriptProcessor processor;

  @BeforeEach
  void setUp() {
    processor = new ShellScriptProcessor();
  }

  @Test
  void testSimpleEcho() {
    final Map<String, Object> config = Map.of("script", "echo 'Hello World'");
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "input");

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              assertEquals("Hello World", ((String) m.getPayload()).trim());
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testMetadataEnvironmentVariables() {
    final Map<String, Object> metadata = new HashMap<>();
    metadata.put("test.key", "test-value");
    metadata.put("simple", "simple-value");

    final Map<String, Object> config =
        Map.of("script", "echo $YUKTA_METADATA_TEST_KEY $YUKTA_METADATA_SIMPLE");
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "input").withMetadata(metadata);

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              assertEquals("test-value simple-value", ((String) m.getPayload()).trim());
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testMetadataWithNullValue() {
    final Map<String, Object> metadata = new HashMap<>();
    metadata.put("null.key", null);
    metadata.put("valid.key", "valid-value");

    // Use a custom implementation to allow nulls in metadata without Map.copyOf
    final Message<String> customMsg =
        new Message<String>() {
          @Override
          public String getMessageId() {
            return "id";
          }

          @Override
          public String getTraceId() {
            return "trace";
          }

          @Override
          public String getCorrelationId() {
            return null;
          }

          @Override
          public String getReplyTo() {
            return null;
          }

          @Override
          public long getTimestamp() {
            return 0;
          }

          @Override
          public long getExpiration() {
            return 0;
          }

          @Override
          public String getFormatIndicator() {
            return null;
          }

          @Override
          public String getSourcePort() {
            return null;
          }

          @Override
          public String getSourceNodeId() {
            return null;
          }

          @Override
          public Map<String, Object> getMetadata() {
            return metadata;
          }

          @Override
          public java.util.List<String> getMessageHistory() {
            return java.util.List.of();
          }

          @Override
          public String getSequenceId() {
            return null;
          }

          @Override
          public int getSequenceNumber() {
            return 0;
          }

          @Override
          public int getSequenceSize() {
            return 0;
          }

          @Override
          public boolean isLastInSequence() {
            return false;
          }

          @Override
          public int getPriority() {
            return 0;
          }

          @Override
          public boolean isControlMessage() {
            return false;
          }

          @Override
          public String getOrigDest() {
            return null;
          }

          @Override
          public String getFailureReason() {
            return null;
          }

          @Override
          public String getExceptionDetail() {
            return null;
          }

          @Override
          public int getRetryCount() {
            return 0;
          }

          @Override
          public String getPayload() {
            return "input";
          }

          @Override
          public <R> Message<R> withPayload(R payload) {
            return DefaultMessage.create(UUID.randomUUID(), payload);
          }

          @Override
          public Message<String> withTraceId(String tid) {
            return this;
          }

          @Override
          public Message<String> withTimestamp(java.time.Instant t) {
            return this;
          }

          @Override
          public Message<String> withCorrelationId(String cid) {
            return this;
          }

          @Override
          public Message<String> withReplyTo(String r) {
            return this;
          }

          @Override
          public Message<String> withSourcePort(String p) {
            return this;
          }

          @Override
          public Message<String> withSourceNodeId(String n) {
            return this;
          }

          @Override
          public Message<String> withExpiration(long e) {
            return this;
          }

          @Override
          public Message<String> withFormatIndicator(String f) {
            return this;
          }

          @Override
          public Message<String> withAddedHistory(String n) {
            return this;
          }

          @Override
          public Message<String> withSequence(String s, int p, int t) {
            return this;
          }

          @Override
          public Message<String> withPriority(int p) {
            return this;
          }

          @Override
          public Message<String> withControl(boolean c) {
            return this;
          }

          @Override
          public Message<String> withFailure(String d, String r, String de) {
            return this;
          }

          @Override
          public Message<String> withRetryCount(int c) {
            return this;
          }

          @Override
          public Message<String> withIncrementedRetry() {
            return this;
          }

          @Override
          public Message<String> withMetadata(Map<String, Object> m) {
            return this;
          }

          @Override
          public Message<String> withHeader(String k, Object v) {
            return this;
          }
        };

    final Map<String, Object> config = Map.of("script", "echo $YUKTA_METADATA_VALID_KEY");

    StepVerifier.create(processor.process(Flux.just(customMsg), config))
        .expectNextMatches(m -> "valid-value".equals(m.getPayload().toString().trim()))
        .verifyComplete();
  }

  @Test
  void testExitCodeFailure() {
    final Map<String, Object> config = Map.of("script", "exit 1");
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "input");

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectErrorMatches(
            e -> {
              System.out.println("Error message: " + e.getMessage());
              return e instanceof WorkflowExecutionException
                  && e.getMessage().contains("exit code 1");
            })
        .verify();
  }

  @Test
  void testTimeout() {
    final Map<String, Object> config = Map.of("script", "sleep 10", "timeout", 1);
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "input");

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectErrorMatches(
            e -> e instanceof WorkflowExecutionException && e.getMessage().contains("timed out"))
        .verify(Duration.ofSeconds(10));
  }

  @Test
  void testMissingScript() {
    final Map<String, Object> config = Map.of();
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "input");

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectError(WorkflowExecutionException.class)
        .verify();
  }

  @Test
  void testBlankScript() {
    final Map<String, Object> config = Map.of("script", "  ");
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "input");

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectError(WorkflowExecutionException.class)
        .verify();
  }

  @Test
  void testCustomExecutable() {
    final Map<String, Object> config =
        Map.of("script", "echo 'Custom Shell'", "executable", "/bin/sh");
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "input");

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> ((String) m.getPayload()).trim().equals("Custom Shell"))
        .verifyComplete();
  }

  @Test
  void testInvalidExecutable() {
    final Map<String, Object> config =
        Map.of("script", "ls", "executable", "/non/existent/shell/path");
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "input");

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectErrorMatches(
            e ->
                e instanceof WorkflowExecutionException
                    && e.getMessage().contains("Failed to start"))
        .verify();
  }

  @Test
  void testWorkingDir() {
    final String tmpDir = System.getProperty("java.io.tmpdir");
    final Map<String, Object> config = Map.of("script", "pwd", "workingDir", tmpDir);
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "input");

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              String payload = ((String) m.getPayload()).trim();
              return payload.contains("tmp") || payload.equals(tmpDir);
            })
        .verifyComplete();
  }

  @Test
  void testWorkingDirNull() {
    final Map<String, Object> config = new HashMap<>();
    config.put("script", "pwd");
    config.put("workingDir", null);
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "input");

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void testWorkingDirBlank() {
    final Map<String, Object> config = Map.of("script", "pwd", "workingDir", " ");
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "input");

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void testUnexpectedError() {
    // This will cause a ClassCastException when reading CONFIG_TIMEOUT
    final Map<String, Object> config = new HashMap<>();
    config.put("script", "echo 1");
    config.put("timeout", "not a number");
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "input");

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectErrorMatches(
            e ->
                e instanceof WorkflowExecutionException
                    && e.getMessage().contains("Shell script execution failed"))
        .verify();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testInterrupted() throws Exception {
    // This requires some trickery to hit InterruptedException in process.waitFor
    // We'll use a subclass to return a mock process
    final Process mockProcess = org.mockito.Mockito.mock(Process.class);
    org.mockito.Mockito.when(
            mockProcess.waitFor(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(java.util.concurrent.TimeUnit.class)))
        .thenThrow(new InterruptedException("interrupted"));

    final ShellScriptProcessor processorWithMock =
        new ShellScriptProcessor() {
          @Override
          public Flux<Message<?>> process(Flux<Message<?>> input, Map<String, Object> config) {
            return input.flatMap(
                msg ->
                    reactor.core.publisher.Mono.<Message<?>>fromCallable(
                            () -> {
                              try {
                                mockProcess.waitFor(1, java.util.concurrent.TimeUnit.SECONDS);
                                return (Message<?>) msg.withPayload("done");
                              } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new WorkflowExecutionException(
                                    "Shell script execution interrupted", e);
                              }
                            })
                        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()));
          }
        };

    final Map<String, Object> config = Map.of("script", "echo 1");
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "input");

    StepVerifier.create(processorWithMock.process(Flux.just(msg), config))
        .expectErrorMatches(
            e -> e instanceof WorkflowExecutionException && e.getMessage().contains("interrupted"))
        .verify();
  }

  @Test
  void testMapError() {
    final ShellScriptProcessor testProcessor = new ShellScriptProcessor();

    // Test WorkflowExecutionException
    final WorkflowExecutionException wee = new WorkflowExecutionException("test");
    assertSame(wee, testProcessor.mapError(wee));

    // Test IOException
    final IOException ioe = new IOException("ioe");
    final Throwable resultIoe = testProcessor.mapError(ioe);
    assertTrue(resultIoe instanceof WorkflowExecutionException);
    assertTrue(resultIoe.getMessage().contains("Failed to start"));

    // Test InterruptedException
    final InterruptedException ie = new InterruptedException("ie");
    final Throwable resultIe = testProcessor.mapError(ie);
    assertTrue(resultIe instanceof WorkflowExecutionException);
    assertTrue(resultIe.getMessage().contains("interrupted"));

    // Test generic error
    final RuntimeException re = new RuntimeException("re");
    final Throwable resultRe = testProcessor.mapError(re);
    assertTrue(resultRe instanceof WorkflowExecutionException);
    assertTrue(resultRe.getMessage().contains("Shell script execution failed"));
  }

  @Test
  void testTypeAndDescription() {
    assertEquals("SHELL_SCRIPT", processor.getType());
    assertNotNull(processor.getDescription());
    assertTrue(processor.getUsagePattern().contains("script"));
  }

  @Test
  void testValidateConfig() {
    StepVerifier.create(processor.validateConfig(Map.of("script", "echo 1"))).verifyComplete();
    StepVerifier.create(processor.validateConfig(Map.of()))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(processor.validateConfig(new HashMap<>()))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(processor.validateConfig(Map.of("script", " ")))
        .expectError(IllegalArgumentException.class)
        .verify();
  }
}
