// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.dto.response.ControlBusStatus;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultSystemHealthProviderTest {

  private DefaultSystemHealthProvider provider;
  private PluginRegistry registry;

  @BeforeEach
  void setUp() {
    registry = mock(PluginRegistry.class);
    provider = new DefaultSystemHealthProvider(registry);
  }

  @Test
  void testGetControlBusStatusNull() {
    when(registry.listPlugins()).thenReturn(List.of());

    ControlBusStatus status = provider.getControlBusStatus(null);
    assertNotNull(status);
  }

  @Test
  void testGetControlBusStatusSessions() {
    when(registry.listPlugins()).thenReturn(List.of());

    ControlBusStatus status = provider.getControlBusStatus("sessions");
    assertNotNull(status);
  }

  @Test
  void testGetControlBusStatusPlugins() {
    Plugin plugin = mock(Plugin.class);
    when(plugin.getType()).thenReturn("test");
    when(plugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.listPlugins()).thenReturn(List.of(plugin));

    ControlBusStatus status = provider.getControlBusStatus("plugins");
    assertNotNull(status);
    assertTrue(status.pluginRegistry().size() > 0);
  }

  @Test
  void testGetControlBusStatusHealth() {
    when(registry.listPlugins()).thenReturn(List.of());

    ControlBusStatus status = provider.getControlBusStatus("health");
    assertNotNull(status);
    assertNotNull(status.systemHealth());
    assertNotNull(status.systemHealth().uptime());
  }

  @Test
  void testGetControlBusStatusExecutions() {
    when(registry.listPlugins()).thenReturn(List.of());

    ControlBusStatus status = provider.getControlBusStatus("executions");
    assertNotNull(status);
  }

  @Test
  void testGetControlBusStatusUnknownFilter() {
    when(registry.listPlugins()).thenReturn(List.of());

    ControlBusStatus status = provider.getControlBusStatus("unknown");
    assertNotNull(status);
  }
}
