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
package com.infenia.yukta.mcp.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.dto.response.PluginCreationGuide;
import com.infenia.yukta.dto.response.PluginDetails;
import com.infenia.yukta.dto.response.PluginSummary;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultPluginInfoProviderTest {

  private DefaultPluginInfoProvider provider;
  private PluginRegistry registry;

  @BeforeEach
  void setUp() {
    registry = mock(PluginRegistry.class);
    provider = new DefaultPluginInfoProvider(registry);
  }

  @Test
  void testListPlugins() {
    Plugin plugin = mock(Plugin.class);
    when(plugin.getType()).thenReturn("test-plugin");
    when(plugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.listPlugins()).thenReturn(List.of(plugin));

    List<PluginSummary> plugins = provider.listPlugins();
    assertEquals(1, plugins.size());
    assertEquals("test-plugin", plugins.get(0).type());
  }

  @Test
  void testGetPluginDetails() {
    Plugin plugin = mock(Plugin.class);
    when(plugin.getType()).thenReturn("test-plugin");
    when(plugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(plugin.getDescription()).thenReturn("desc");
    when(plugin.getUsagePattern()).thenReturn("pattern");
    when(plugin.getUiDesign()).thenReturn(Optional.empty());
    when(plugin.getOutputPorts()).thenReturn(List.of());
    when(registry.get("test-plugin")).thenReturn(plugin);

    PluginDetails details = provider.getPluginDetails("test-plugin");
    assertEquals("test-plugin", details.type());
    assertEquals("desc", details.description());
  }

  @Test
  void testGetPluginDetailsNotFound() {
    when(registry.get("unknown")).thenReturn(null);

    assertThrows(IllegalArgumentException.class, () -> provider.getPluginDetails("unknown"));
  }

  @Test
  void testGetPluginCreationGuide() {
    PluginCreationGuide guide = provider.getPluginCreationGuide("all");

    assertNotNull(guide);
    assertNotNull(guide.architectureOverview());
    assertFalse(guide.architectureOverview().isEmpty());
    assertNotNull(guide.templateCode());
    assertFalse(guide.templateCode().isEmpty());
  }

  @Test
  void testGetPluginCreationGuideTrigger() {
    PluginCreationGuide guide = provider.getPluginCreationGuide("trigger");

    assertNotNull(guide);
    assertNotNull(guide.templateCode());
    assertFalse(guide.templateCode().isEmpty());
  }

  @Test
  void testGetPluginCreationGuideProcessor() {
    PluginCreationGuide guide = provider.getPluginCreationGuide("processor");

    assertNotNull(guide);
    assertNotNull(guide.templateCode());
    assertFalse(guide.templateCode().isEmpty());
  }

  @Test
  void testGetPluginCreationGuideTerminal() {
    PluginCreationGuide guide = provider.getPluginCreationGuide("terminal");

    assertNotNull(guide);
    assertNotNull(guide.templateCode());
    assertFalse(guide.templateCode().isEmpty());
  }
}
