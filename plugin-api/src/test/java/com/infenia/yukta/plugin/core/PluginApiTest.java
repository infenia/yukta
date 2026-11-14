// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PluginApiTest {

  @Test
  void versionIsMajorDotMinor() {
    assertThat(PluginApi.VERSION).isEqualTo(PluginApi.MAJOR + "." + PluginApi.MINOR);
  }

  @Test
  void pluginBuiltWithTheServer_isCompatible() {
    final Plugin plugin =
        new Plugin() {
          @Override
          public String getType() {
            return "T";
          }

          @Override
          public PluginCategory getCategory() {
            return PluginCategory.PROCESSOR;
          }
        };
    assertThat(PluginApi.refusal(plugin.apiVersion())).isNull();
  }

  @Test
  void olderMinor_isCompatible() {
    assertThat(PluginApi.refusal(PluginApi.MAJOR + ".0")).isNull();
  }

  @Test
  void notDeclared_isCompatible() {
    assertThat(PluginApi.refusal(null)).isNull();
  }

  @Test
  void newerMinor_isRefused() {
    assertThat(PluginApi.refusal(PluginApi.MAJOR + "." + (PluginApi.MINOR + 1)))
        .contains("newer than this server's SPI " + PluginApi.VERSION);
  }

  @Test
  void otherMajor_isRefused() {
    assertThat(PluginApi.refusal(PluginApi.MAJOR + 1 + ".0"))
        .contains("this server has SPI " + PluginApi.VERSION);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "1", "v1.0", "1.0.0", "1.x", "99999.0"})
  void malformedVersion_isRefused(final String version) {
    assertThat(PluginApi.refusal(version)).contains("is not MAJOR.MINOR");
  }
}
