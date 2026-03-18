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
package com.infenia.yukta.plugin.process;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import reactor.test.StepVerifier;

class ProcessExecutorGatewayTest {

  private ProcessExecutorGateway gateway;

  @BeforeEach
  void setUp() {
    gateway = new ProcessExecutorGateway();
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void testExecuteStreamUnix() {
    StepVerifier.create(
            gateway.executeStream(List.of("echo", "hello\nworld"), null, 10L, Map.of(), false))
        .expectNext("hello")
        .expectNext("world")
        .verifyComplete();
  }

  @Test
  void testExecuteStreamWithShell() {
    StepVerifier.create(gateway.executeStream(List.of("echo hello"), null, 10L, Map.of(), true))
        .assertNext(line -> assertTrue(line.contains("hello")))
        .verifyComplete();
  }

  @Test
  void testExecuteStreamWithEnv() {
    StepVerifier.create(
            gateway.executeStream(
                List.of("sh", "-c", "echo $TEST_VAR"), null, 10L, Map.of("TEST_VAR", "foo"), false))
        .expectNext("foo")
        .verifyComplete();
  }

  @Test
  void testExecuteStreamTimeout() {
    StepVerifier.create(gateway.executeStream(List.of("sleep", "10"), null, 1L, Map.of(), false))
        .verifyError(WorkflowExecutionException.class);
  }

  @Test
  void testExecuteStreamNonZeroExit() {
    StepVerifier.create(gateway.executeStream(List.of("false"), null, 10L, Map.of(), false))
        .verifyError(WorkflowExecutionException.class);
  }

  @Test
  void testExecuteWithMetadata() {
    StepVerifier.create(
            gateway.executeWithMetadata(
                List.of("sh", "-c", "echo $YUKTA_METADATA_FOO"), null, 10L, Map.of("foo", "bar")))
        .assertNext(output -> assertTrue(output.contains("bar")))
        .verifyComplete();
  }

  @Test
  void testExecuteStreamWithInvalidTimeout() {
    StepVerifier.create(gateway.executeStream(List.of("echo", "hello"), null, 0L, Map.of(), false))
        .verifyError(IllegalArgumentException.class);
  }

  @Test
  void testExecuteStreamWithNegativeTimeout() {
    StepVerifier.create(gateway.executeStream(List.of("echo", "hello"), null, -5L, Map.of(), false))
        .verifyError(IllegalArgumentException.class);
  }

  @Test
  void testExecuteNonStreaming() {
    StepVerifier.create(gateway.execute(List.of("echo", "hello"), null, 10L))
        .expectNext("hello")
        .verifyComplete();
  }

  @Test
  void testExecuteMetadataExport() {
    StepVerifier.create(
            gateway.executeWithMetadata(
                List.of("sh", "-c", "echo $YUKTA_METADATA_KEY"), null, 10L, Map.of("key", "value")))
        .assertNext(output -> assertTrue(output.contains("value")))
        .verifyComplete();
  }
}
