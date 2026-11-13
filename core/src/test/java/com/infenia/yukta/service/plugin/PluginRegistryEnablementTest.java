// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginTier;
import java.util.List;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class PluginRegistryEnablementTest {

  private final List<Plugin> all =
      List.of(
          plugin("BRANCH", PluginTier.CORE, false),
          plugin("DELAY_X", PluginTier.EXTENDED, false),
          plugin("PROCESS_EXECUTOR", PluginTier.EXTENDED, true),
          plugin("LLM_CALL", PluginTier.SPECIFIC, false));

  private static Plugin plugin(final String type, final PluginTier tier, final boolean restricted) {
    final Plugin p = mock(Plugin.class);
    when(p.getType()).thenReturn(type);
    when(p.getTier()).thenReturn(tier);
    when(p.isRestricted()).thenReturn(restricted);
    return p;
  }

  @Test
  void byDefaultOnlyGenericUnrestrictedPluginsAreRegistered() {
    final var registry = new PluginRegistry(all, List.of());

    assertThat(registry.listPlugins())
        .extracting(Plugin::getType)
        .containsExactly("BRANCH", "DELAY_X");
    assertThat(registry.listDisabled())
        .extracting(Plugin::getType)
        .containsExactly("LLM_CALL", "PROCESS_EXECUTOR");
    assertThat(registry.missingHint("LLM_CALL")).contains("yukta.plugins.enabled", "LLM_CALL");
    assertThat(registry.missingHint("NOPE")).isEmpty();
    assertThat(registry.getDisabled("LLM_CALL")).isSameAs(all.get(3));
    assertThat(registry.getDisabled("BRANCH")).isNull();
  }

  @Test
  void namedPluginsAreEnabled() {
    final var registry = new PluginRegistry(all, List.of("PROCESS_EXECUTOR"));

    assertThat(registry.contains("PROCESS_EXECUTOR")).isTrue();
    assertThat(registry.contains("LLM_CALL")).isFalse();
  }

  @Test
  void allEnablesEverything() {
    assertThat(new PluginRegistry(all, List.of("all")).listPlugins()).hasSize(4);
    assertThat(new PluginRegistry(all).listDisabled()).isEmpty();
  }
}
