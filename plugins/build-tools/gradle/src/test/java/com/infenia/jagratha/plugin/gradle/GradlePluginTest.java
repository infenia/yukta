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
package com.infenia.jagratha.plugin.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;

class GradlePluginTest {

  private GradlePlugin plugin;

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() {
    plugin = new GradlePlugin();
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

    StepVerifier.create(plugin.start(config, Map.of()))
        .assertNext(
            message -> {
              assertNotNull(message.id());
              assertNotNull(message.traceId());
              assertEquals("Task output for testTask", ((String) message.payload()).trim());
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

    StepVerifier.create(plugin.start(config, Map.of())).verifyError(RuntimeException.class);
  }
}
