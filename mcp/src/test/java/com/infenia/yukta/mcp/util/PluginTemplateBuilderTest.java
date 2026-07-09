// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
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
