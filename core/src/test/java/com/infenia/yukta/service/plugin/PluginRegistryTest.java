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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.core.Plugin;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** Unit tests for {@link PluginRegistry}. */
@SpringJUnitConfig(LocalValidatorFactoryBean.class)
@NoArgsConstructor
@SuppressWarnings("PMD.TooManyMethods")
class PluginRegistryTest {

  /** Test plugin type identifier. */
  private static final String TEST_TYPE = "test-type";

  @BeforeEach
  void setUp() {
    try (LocalValidatorFactoryBean validatorFactoryBean = new LocalValidatorFactoryBean()) {
      validatorFactoryBean.afterPropertiesSet();
    }
  }

  @Test
  void testConstructorWithSinglePlugin() {
    final Plugin mockPlugin = mock(Plugin.class);
    when(mockPlugin.getType()).thenReturn("test-plugin");

    final PluginRegistry registry = new PluginRegistry(List.of(mockPlugin));

    assertThat(registry.contains("test-plugin")).isTrue();
    assertThat(registry.get("test-plugin")).isNotNull();
  }

  @Test
  void testConstructorWithMultiplePlugins() {
    final Plugin firstPlugin = mock(Plugin.class);
    when(firstPlugin.getType()).thenReturn("type-1");
    final Plugin secondPlugin = mock(Plugin.class);
    when(secondPlugin.getType()).thenReturn("type-2");
    final Plugin thirdPlugin = mock(Plugin.class);
    when(thirdPlugin.getType()).thenReturn("type-3");

    final PluginRegistry registry =
        new PluginRegistry(List.of(firstPlugin, secondPlugin, thirdPlugin));

    assertThat(registry.listPlugins().size()).isEqualTo(3);
    assertThat(registry.contains("type-1")).isTrue();
    assertThat(registry.contains("type-2")).isTrue();
    assertThat(registry.contains("type-3")).isTrue();
  }

  @Test
  void testConstructorWithEmptyPluginList() {
    final PluginRegistry registry = new PluginRegistry(List.of());

    assertThat(registry.listPlugins().isEmpty()).isTrue();
    assertThat(registry.contains("any-type")).isFalse();
    assertThat(registry.get("any-type")).isNull();
  }

  @Test
  void testGetWithRegisteredType() {
    final Plugin mockPlugin = mock(Plugin.class);
    when(mockPlugin.getType()).thenReturn("my-plugin");

    final PluginRegistry registry = new PluginRegistry(List.of(mockPlugin));
    final Plugin retrieved = registry.get("my-plugin");

    assertThat(retrieved).isNotNull();
    assertThat(retrieved).isEqualTo(mockPlugin);
  }

  @Test
  void testGetWithUnregisteredType() {
    final Plugin mockPlugin = mock(Plugin.class);
    when(mockPlugin.getType()).thenReturn("registered");

    final PluginRegistry registry = new PluginRegistry(List.of(mockPlugin));
    final Plugin retrieved = registry.get("non-existent");

    assertThat(retrieved).isNull();
  }

  @Test
  void testContainsWithRegisteredType() {
    final Plugin mockPlugin = mock(Plugin.class);
    when(mockPlugin.getType()).thenReturn("registered-type");

    final PluginRegistry registry = new PluginRegistry(List.of(mockPlugin));

    assertThat(registry.contains("registered-type")).isTrue();
  }

  @Test
  void testContainsWithUnregisteredType() {
    final Plugin mockPlugin = mock(Plugin.class);
    when(mockPlugin.getType()).thenReturn("registered");

    final PluginRegistry registry = new PluginRegistry(List.of(mockPlugin));

    assertThat(registry.contains("non-existent")).isFalse();
  }

  @Test
  void testListPluginsReturnsAllPlugins() {
    final Plugin firstPlugin = mock(Plugin.class);
    when(firstPlugin.getType()).thenReturn("type1");
    final Plugin secondPlugin = mock(Plugin.class);
    when(secondPlugin.getType()).thenReturn("type2");
    final Plugin thirdPlugin = mock(Plugin.class);
    when(thirdPlugin.getType()).thenReturn("type3");

    final PluginRegistry registry =
        new PluginRegistry(List.of(firstPlugin, secondPlugin, thirdPlugin));
    final List<Plugin> plugins = registry.listPlugins();

    assertThat(plugins).hasSize(3);
    assertThat(plugins.contains(firstPlugin)).isTrue();
    assertThat(plugins.contains(secondPlugin)).isTrue();
    assertThat(plugins.contains(thirdPlugin)).isTrue();
  }

  @Test
  void testListPluginsReturnsImmutableCopy() {
    final Plugin mockPlugin = mock(Plugin.class);
    when(mockPlugin.getType()).thenReturn("plugin");

    final PluginRegistry registry = new PluginRegistry(List.of(mockPlugin));
    final List<Plugin> plugins = registry.listPlugins();

    assertThatThrownBy(() -> plugins.add(mock(Plugin.class)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void testListPluginsEmptyRegistry() {
    final PluginRegistry registry = new PluginRegistry(List.of());
    final List<Plugin> plugins = registry.listPlugins();

    assertThat(plugins.isEmpty()).isTrue();
    assertThatThrownBy(() -> plugins.add(mock(Plugin.class)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void testPluginTypeIsUsedAsKey() {
    final Plugin firstPlugin = mock(Plugin.class);
    when(firstPlugin.getType()).thenReturn("same-type");
    final Plugin secondPlugin = mock(Plugin.class);
    when(secondPlugin.getType()).thenReturn("same-type");

    final PluginRegistry registry = new PluginRegistry(List.of(firstPlugin, secondPlugin));

    assertThat(registry.listPlugins().size()).isEqualTo(1);
    assertThat(registry.get("same-type")).isEqualTo(secondPlugin);
  }

  @Test
  void testRegistry() {
    final Plugin mockPlugin = mock(Plugin.class);
    when(mockPlugin.getType()).thenReturn(TEST_TYPE);

    final PluginRegistry registry = new PluginRegistry(List.of(mockPlugin));

    assertThat(registry.contains(TEST_TYPE)).isTrue();
    assertThat(registry.contains("unknown")).isFalse();
    assertThat(registry.get(TEST_TYPE)).isNotNull();
    assertThat(registry.get("unknown")).isNull();

    final List<Plugin> plugins = registry.listPlugins();
    assertThat(plugins).hasSize(1);
    assertThat(plugins.get(0).getType()).isEqualTo(TEST_TYPE);
  }

  @Test
  void testMultiplePlugins() {
    final Plugin firstPlugin = mock(Plugin.class);
    when(firstPlugin.getType()).thenReturn("type1");
    final Plugin secondPlugin = mock(Plugin.class);
    when(secondPlugin.getType()).thenReturn("type2");

    final PluginRegistry registry = new PluginRegistry(List.of(firstPlugin, secondPlugin));

    assertThat(registry.listPlugins().size()).isEqualTo(2);
    assertThat(registry.contains("type1")).isTrue();
    assertThat(registry.contains("type2")).isTrue();
  }
}
