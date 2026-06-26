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
package com.infenia.yukta.service.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.core.Plugin;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@SpringJUnitConfig(LocalValidatorFactoryBean.class)
class PluginRegistryTest {

  @BeforeEach
  void setUp() {
    try (LocalValidatorFactoryBean validatorFactoryBean = new LocalValidatorFactoryBean()) {
      validatorFactoryBean.afterPropertiesSet();
    }
  }

  @Test
  void testConstructorWithSinglePlugin() {
    Plugin mockPlugin = mock(Plugin.class);
    when(mockPlugin.getType()).thenReturn("test-plugin");

    PluginRegistry registry = new PluginRegistry(List.of(mockPlugin));

    assertTrue(registry.contains("test-plugin"));
    assertNotNull(registry.get("test-plugin"));
  }

  @Test
  void testConstructorWithMultiplePlugins() {
    Plugin p1 = mock(Plugin.class);
    when(p1.getType()).thenReturn("type-1");
    Plugin p2 = mock(Plugin.class);
    when(p2.getType()).thenReturn("type-2");
    Plugin p3 = mock(Plugin.class);
    when(p3.getType()).thenReturn("type-3");

    PluginRegistry registry = new PluginRegistry(List.of(p1, p2, p3));

    assertEquals(3, registry.listPlugins().size());
    assertTrue(registry.contains("type-1"));
    assertTrue(registry.contains("type-2"));
    assertTrue(registry.contains("type-3"));
  }

  @Test
  void testConstructorWithEmptyPluginList() {
    PluginRegistry registry = new PluginRegistry(List.of());

    assertTrue(registry.listPlugins().isEmpty());
    assertFalse(registry.contains("any-type"));
    assertNull(registry.get("any-type"));
  }

  @Test
  void testGetWithRegisteredType() {
    Plugin mockPlugin = mock(Plugin.class);
    when(mockPlugin.getType()).thenReturn("my-plugin");

    PluginRegistry registry = new PluginRegistry(List.of(mockPlugin));
    Plugin retrieved = registry.get("my-plugin");

    assertNotNull(retrieved);
    assertEquals(mockPlugin, retrieved);
  }

  @Test
  void testGetWithUnregisteredType() {
    Plugin mockPlugin = mock(Plugin.class);
    when(mockPlugin.getType()).thenReturn("registered");

    PluginRegistry registry = new PluginRegistry(List.of(mockPlugin));
    Plugin retrieved = registry.get("non-existent");

    assertNull(retrieved);
  }

  @Test
  void testContainsWithRegisteredType() {
    Plugin mockPlugin = mock(Plugin.class);
    when(mockPlugin.getType()).thenReturn("registered-type");

    PluginRegistry registry = new PluginRegistry(List.of(mockPlugin));

    assertTrue(registry.contains("registered-type"));
  }

  @Test
  void testContainsWithUnregisteredType() {
    Plugin mockPlugin = mock(Plugin.class);
    when(mockPlugin.getType()).thenReturn("registered");

    PluginRegistry registry = new PluginRegistry(List.of(mockPlugin));

    assertFalse(registry.contains("non-existent"));
  }

  @Test
  void testListPluginsReturnsAllPlugins() {
    Plugin p1 = mock(Plugin.class);
    when(p1.getType()).thenReturn("type1");
    Plugin p2 = mock(Plugin.class);
    when(p2.getType()).thenReturn("type2");
    Plugin p3 = mock(Plugin.class);
    when(p3.getType()).thenReturn("type3");

    PluginRegistry registry = new PluginRegistry(List.of(p1, p2, p3));
    List<Plugin> plugins = registry.listPlugins();

    assertEquals(3, plugins.size());
    assertTrue(plugins.contains(p1));
    assertTrue(plugins.contains(p2));
    assertTrue(plugins.contains(p3));
  }

  @Test
  void testListPluginsReturnsImmutableCopy() {
    Plugin mockPlugin = mock(Plugin.class);
    when(mockPlugin.getType()).thenReturn("plugin");

    PluginRegistry registry = new PluginRegistry(List.of(mockPlugin));
    List<Plugin> plugins = registry.listPlugins();

    assertThrows(UnsupportedOperationException.class, () -> plugins.add(mock(Plugin.class)));
  }

  @Test
  void testListPluginsEmptyRegistry() {
    PluginRegistry registry = new PluginRegistry(List.of());
    List<Plugin> plugins = registry.listPlugins();

    assertTrue(plugins.isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> plugins.add(mock(Plugin.class)));
  }

  @Test
  void testPluginTypeIsUsedAsKey() {
    Plugin p1 = mock(Plugin.class);
    when(p1.getType()).thenReturn("same-type");
    Plugin p2 = mock(Plugin.class);
    when(p2.getType()).thenReturn("same-type");

    PluginRegistry registry = new PluginRegistry(List.of(p1, p2));

    assertEquals(1, registry.listPlugins().size());
    assertEquals(p2, registry.get("same-type"));
  }

  @Test
  void testRegistry() {
    Plugin mockPlugin = mock(Plugin.class);
    when(mockPlugin.getType()).thenReturn("test-type");

    PluginRegistry registry = new PluginRegistry(List.of(mockPlugin));

    assertTrue(registry.contains("test-type"));
    assertFalse(registry.contains("unknown"));
    assertNotNull(registry.get("test-type"));
    assertNull(registry.get("unknown"));

    List<Plugin> plugins = registry.listPlugins();
    assertEquals(1, plugins.size());
    assertEquals("test-type", plugins.get(0).getType());
  }

  @Test
  void testMultiplePlugins() {
    Plugin p1 = mock(Plugin.class);
    when(p1.getType()).thenReturn("type1");
    Plugin p2 = mock(Plugin.class);
    when(p2.getType()).thenReturn("type2");

    PluginRegistry registry = new PluginRegistry(List.of(p1, p2));

    assertEquals(2, registry.listPlugins().size());
    assertTrue(registry.contains("type1"));
    assertTrue(registry.contains("type2"));
  }
}
