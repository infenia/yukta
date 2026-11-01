// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.Plugin;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EchoOutputTest {

  private static Plugin plugin(final ConfigField... fields) {
    final Plugin plugin = mock(Plugin.class);
    when(plugin.getConfigSchema()).thenReturn(List.of(fields));
    return plugin;
  }

  private static Map<String, Object> engine(final Object echoOutput) {
    return Map.of("engine", Map.of("echoOutput", echoOutput));
  }

  @Test
  void nodeWithoutSecretsEchoesByDefault() {
    final Plugin plugin = plugin(ConfigField.optional("url", ConfigField.Type.STRING, null, "u"));
    assertThat(EchoOutput.enabled(plugin, Map.of("url", "http://x", "args", List.of("a"))))
        .isTrue();
  }

  @Test
  void declaredSecretFieldTurnsTheEchoOff() {
    final Plugin plugin =
        plugin(ConfigField.optional("pass", ConfigField.Type.SECRET, null, "secret"));
    assertThat(EchoOutput.enabled(plugin, Map.of("pass", "decrypted:x"))).isFalse();
  }

  @Test
  void secretNamedKeyAtAnyDepthTurnsTheEchoOff() {
    assertThat(EchoOutput.enabled(plugin(), Map.of("apiKey", "decrypted:x"))).isFalse();
    assertThat(
            EchoOutput.enabled(
                plugin(), Map.of("headers", List.of(Map.of("Authorization", "${env.A}")))))
        .isFalse();
  }

  @Test
  void explicitSettingWinsEitherWay() {
    assertThat(EchoOutput.enabled(plugin(), engine(false))).isFalse();
    final Map<String, Object> secret =
        Map.of("token", "decrypted:t", "engine", engine(true).get("engine"));
    assertThat(EchoOutput.enabled(plugin(), secret)).isTrue();
  }

  @Test
  void nonBooleanSettingIsRefused() {
    assertThatThrownBy(() -> EchoOutput.enabled(plugin(), engine("no")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void loggedLiveIsAlwaysTrueWhenTheEchoIsOff() {
    assertThat(EchoOutput.loggedLive(plugin(), engine(false), () -> false).getAsBoolean()).isTrue();
    assertThat(EchoOutput.loggedLive(plugin(), Map.of(), () -> false).getAsBoolean()).isFalse();
  }
}
