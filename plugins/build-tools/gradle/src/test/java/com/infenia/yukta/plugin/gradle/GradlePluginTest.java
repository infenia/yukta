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
package com.infenia.yukta.plugin.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class GradlePluginTest {

  private GradlePlugin plugin;

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() {
    plugin = new GradlePlugin();
    plugin.setBuildGateway(new com.infenia.yukta.plugin.buildtool.DefaultBuildGateway());
  }

  @Test
  void testGetType() {
    assertEquals("gradle", plugin.getType());
  }

  @Test
  void testValidateConfigSuccess() {
    Map<String, Object> config =
        Map.of(
            "projectRoot", tempDir.toString(),
            "tasks", List.of("test"));
    StepVerifier.create(plugin.validateConfig(config)).verifyComplete();
  }

  @Test
  void testValidateConfigMissingProjectRoot() {
    Map<String, Object> config = Map.of("tasks", List.of("test"));
    StepVerifier.create(plugin.validateConfig(config)).verifyError(IllegalArgumentException.class);
  }

  @Test
  void testValidateConfigNull() {
    StepVerifier.create(plugin.validateConfig(null)).verifyError(IllegalArgumentException.class);
  }

  @Test
  void testValidateConfigInvalidTasks() {
    Map<String, Object> config = Map.of("projectRoot", tempDir.toString(), "tasks", "not-a-list");
    StepVerifier.create(plugin.validateConfig(config)).verifyError(IllegalArgumentException.class);
  }

  @Test
  void testInitialize() {
    StepVerifier.create(plugin.initialize(Map.of())).verifyComplete();
  }

  @Test
  void testStart() throws IOException {
    // Create a dummy gradlew script
    Path gradlew = tempDir.resolve("gradlew");
    String script = "#!/bin/sh\necho \"Task output for $1\"\n";
    Files.writeString(gradlew, script);
    gradlew.toFile().setExecutable(true);

    Map<String, Object> config =
        Map.of(
            "projectRoot", tempDir.toString(),
            "tasks", List.of("testTask"),
            "gradlePath", "./gradlew");

    StepVerifier.create(plugin.start(config))
        .assertNext(
            message -> {
              assertNotNull(message.getMessageId());
              assertNotNull(message.getTraceId());
              assertEquals("Task output for testTask", ((String) message.getPayload()).trim());
            })
        .verifyComplete();
  }

  @Test
  void testStartExecutionError() {
    Map<String, Object> config =
        Map.of(
            "projectRoot", tempDir.toString(),
            "tasks", List.of("testTask"),
            "gradlePath", "./non-existent-gradlew");

    StepVerifier.create(plugin.start(config)).verifyError(RuntimeException.class);
  }

  @Test
  void testProcess() throws IOException {
    // Create a dummy gradlew script
    Path gradlew = tempDir.resolve("gradlew");
    String script = "#!/bin/sh\necho \"Task output for $1\"\n";
    Files.writeString(gradlew, script);
    gradlew.toFile().setExecutable(true);

    Map<String, Object> config =
        Map.of(
            "projectRoot", tempDir.toString(),
            "tasks", List.of("processTask"),
            "gradlePath", "./gradlew");

    UUID traceId = UUID.randomUUID();
    Message inputMessage = DefaultMessage.create(traceId, Map.of("some", "payload"));
    StepVerifier.create(plugin.process(Flux.just(inputMessage), config))
        .assertNext(
            message -> {
              assertEquals(traceId.toString(), message.getTraceId());
              assertEquals("Task output for processTask", ((String) message.getPayload()).trim());
            })
        .verifyComplete();
  }

  @Test
  void testProcessExhaustMap() throws IOException {
    // Create a dummy gradlew script that sleeps
    Path gradlew = tempDir.resolve("gradlew");
    String script = "#!/bin/sh\nsleep 1\necho \"Finished $1\"\n";
    Files.writeString(gradlew, script);
    gradlew.toFile().setExecutable(true);

    Map<String, Object> config =
        Map.of(
            "projectRoot",
            tempDir.toString(),
            "tasks",
            List.of("task"),
            "gradlePath",
            "./gradlew",
            "timeout",
            5L);

    Message msg1 = DefaultMessage.create(UUID.randomUUID(), "payload1");
    Message msg2 = DefaultMessage.create(UUID.randomUUID(), "payload2");

    // Send two messages almost simultaneously.
    // The second one should be ignored because the first one is running (exhaustMap behavior).
    StepVerifier.create(plugin.process(Flux.just(msg1, msg2), config))
        .assertNext(m -> assertEquals("Finished task", ((String) m.getPayload()).trim()))
        .verifyComplete();
  }
}
