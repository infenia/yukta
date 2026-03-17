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
package com.infenia.yukta.model.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SessionCreationGuideTest {

  @Test
  void testSessionCreationGuideCreation() {
    List<PluginReference> plugins =
        List.of(new PluginReference("gradle", "build-tool", "Gradle plugin for quality checks"));
    List<ErrorExample> errors =
        List.of(
            new ErrorExample(
                "InvalidConfigException", "Missing workflow definition", "Check YAML syntax"));

    SessionCreationGuide guide =
        new SessionCreationGuide(
            "camelCase naming",
            "YAML with top-level nodes and edges",
            "session:\n  id: test-123",
            "nodes: [], edges: []",
            plugins,
            errors);

    assertEquals("camelCase naming", guide.namingConventions());
    assertEquals("YAML with top-level nodes and edges", guide.configurationStructure());
    assertEquals("session:\n  id: test-123", guide.exampleSessionConfig());
    assertEquals("nodes: [], edges: []", guide.workflowDefinitionFormat());
    assertEquals(1, guide.availablePlugins().size());
    assertEquals(1, guide.commonErrors().size());
  }

  @Test
  void testSessionCreationResponseCreation() {
    List<String> workflows = List.of("workflow-1", "workflow-2");
    List<String> warnings = List.of("warning-1");

    SessionCreationResponse response =
        new SessionCreationResponse("session-123", workflows, warnings, true);

    assertEquals("session-123", response.sessionId());
    assertEquals(2, response.createdWorkflows().size());
    assertEquals(1, response.warnings().size());
    assertTrue(response.success());
  }

  @Test
  void testPluginReferenceCreation() {
    PluginReference plugin =
        new PluginReference("gradle", "build-tool", "Gradle plugin for quality checks");

    assertEquals("gradle", plugin.type());
    assertEquals("build-tool", plugin.category());
    assertEquals("Gradle plugin for quality checks", plugin.description());
  }

  @Test
  void testErrorExampleCreation() {
    ErrorExample error =
        new ErrorExample(
            "InvalidConfigException", "Missing workflow definition", "Check YAML syntax");

    assertEquals("InvalidConfigException", error.error());
    assertEquals("Missing workflow definition", error.cause());
    assertEquals("Check YAML syntax", error.resolution());
  }

  @Test
  void testSessionCreationGuideWithEmptyCollections() {
    SessionCreationGuide guide =
        new SessionCreationGuide("naming", "config", "example", "format", List.of(), List.of());

    assertNotNull(guide.availablePlugins());
    assertNotNull(guide.commonErrors());
    assertTrue(guide.availablePlugins().isEmpty());
    assertTrue(guide.commonErrors().isEmpty());
  }

  @Test
  void testSessionCreationResponseWithEmptyCollections() {
    SessionCreationResponse response =
        new SessionCreationResponse("session-id", List.of(), List.of(), false);

    assertNotNull(response.createdWorkflows());
    assertNotNull(response.warnings());
    assertTrue(response.createdWorkflows().isEmpty());
    assertTrue(response.warnings().isEmpty());
  }
}
