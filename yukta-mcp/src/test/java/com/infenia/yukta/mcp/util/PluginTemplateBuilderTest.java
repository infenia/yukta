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
package com.infenia.yukta.mcp.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PluginTemplateBuilderTest {

  @Test
  void testBuildTemplatesAll() {
    Map<String, String> templates = PluginTemplateBuilder.buildTemplates("all");
    assertEquals(3, templates.size());
    assertTrue(templates.containsKey("trigger"));
    assertTrue(templates.containsKey("processor"));
    assertTrue(templates.containsKey("terminal"));
  }

  @Test
  void testBuildTemplatesTrigger() {
    Map<String, String> templates = PluginTemplateBuilder.buildTemplates("trigger");
    assertEquals(1, templates.size());
    assertTrue(templates.containsKey("trigger"));
    assertTrue(templates.get("trigger").contains("TriggerPlugin"));
  }

  @Test
  void testBuildTemplatesProcessor() {
    Map<String, String> templates = PluginTemplateBuilder.buildTemplates("processor");
    assertEquals(1, templates.size());
    assertTrue(templates.containsKey("processor"));
    assertTrue(templates.get("processor").contains("ProcessorPlugin"));
  }

  @Test
  void testBuildTemplatesTerminal() {
    Map<String, String> templates = PluginTemplateBuilder.buildTemplates("terminal");
    assertEquals(1, templates.size());
    assertTrue(templates.containsKey("terminal"));
    assertTrue(templates.get("terminal").contains("TerminalPlugin"));
  }

  @Test
  void testBuildTemplatesNull() {
    Map<String, String> templates = PluginTemplateBuilder.buildTemplates(null);
    assertEquals(3, templates.size());
  }

  @Test
  void testBuildTemplatesBlank() {
    Map<String, String> templates = PluginTemplateBuilder.buildTemplates("   ");
    assertEquals(3, templates.size());
  }

  @Test
  void testBuildTemplatesCaseInsensitive() {
    Map<String, String> templates = PluginTemplateBuilder.buildTemplates("TRIGGER");
    assertEquals(1, templates.size());
    assertTrue(templates.containsKey("trigger"));
  }
}
