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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PluginInterfaceTest {

  @Test
  void testProcessorInputCompactConstructor() {
    OutputProcessorPlugin.ProcessorInput input =
        new OutputProcessorPlugin.ProcessorInput("sess", "root", "mod", "task", "out", "res", null);

    assertNotNull(input.config());
    assertEquals(0, input.config().size());
  }

  @Test
  void testProcessorInputConfigImmutability() {
    Map<String, Object> config = new java.util.HashMap<>();
    config.put("key", "value");
    OutputProcessorPlugin.ProcessorInput input =
        new OutputProcessorPlugin.ProcessorInput(
            "sess", "root", "mod", "task", "out", "res", config);

    assertEquals("value", input.config().get("key"));

    // Attempting to modify the returned map should throw UnsupportedOperationException
    try {
      input.config().put("new", "val");
    } catch (UnsupportedOperationException e) {
      // expected
    }
  }

  @Test
  void testProcessorResult() {
    OutputProcessorPlugin.ProcessorResult result =
        new OutputProcessorPlugin.ProcessorResult("SUCCESS", "out", "art");

    assertEquals("SUCCESS", result.status());
    assertEquals("out", result.output());
    assertEquals("art", result.artifactPath());
  }

  @Test
  void testValidationResultSuccess() {
    ValidationResult result = ValidationResult.success();
    assertTrue(result.valid());
    assertEquals("Validation successful", result.message());
    assertTrue(result.errors().isEmpty());
  }

  @Test
  void testValidationResultError() {
    ValidationResult result = ValidationResult.error("Failed");
    assertFalse(result.valid());
    assertEquals("Failed", result.message());
    assertTrue(result.errors().isEmpty());
  }

  @Test
  void testValidationResultFieldErrors() {
    List<ValidationResult.FieldError> errors =
        List.of(new ValidationResult.FieldError("field", "error"));
    ValidationResult result = ValidationResult.error("Invalid", errors);
    assertFalse(result.valid());
    assertEquals("Invalid", result.message());
    assertEquals(1, result.errors().size());
    assertEquals("field", result.errors().get(0).field());
    assertEquals("error", result.errors().get(0).message());
  }

  @Test
  void testDefaultValidateConfig() {
    JagrathaPlugin plugin =
        new JagrathaPlugin() {
          @Override
          public String getName() {
            return "test";
          }

          @Override
          public String identifyModule(String projectRoot, String relativePath) {
            return "";
          }

          @Override
          public List<String> buildTaskCommand(
              String module, String task, Map<String, Object> pluginConfig) {
            return List.of();
          }
        };

    assertTrue(plugin.validateConfig(Map.of()).valid());

    AiPlugin aiPlugin =
        new AiPlugin() {
          @Override
          public String getName() {
            return "test";
          }

          @Override
          public String execute(String prompt, Map<String, Object> config) {
            return "";
          }
        };
    assertTrue(aiPlugin.validateConfig(Map.of()).valid());

    OutputProcessorPlugin procPlugin =
        new OutputProcessorPlugin() {
          @Override
          public String getName() {
            return "test";
          }

          @Override
          public ProcessorResult process(ProcessorInput input) {
            return null;
          }
        };
    assertTrue(procPlugin.validateConfig(Map.of()).valid());
  }
}
