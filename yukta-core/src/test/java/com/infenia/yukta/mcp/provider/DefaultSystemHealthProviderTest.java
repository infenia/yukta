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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.api.ControlBusStatus;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.core.WorkflowPlugin;
import com.infenia.yukta.service.WorkflowRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultSystemHealthProviderTest {

  private DefaultSystemHealthProvider provider;
  private WorkflowRegistry registry;

  @BeforeEach
  void setUp() {
    registry = mock(WorkflowRegistry.class);
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
    WorkflowPlugin plugin = mock(WorkflowPlugin.class);
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
