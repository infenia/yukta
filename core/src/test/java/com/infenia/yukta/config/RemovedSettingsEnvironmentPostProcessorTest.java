// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.mock.env.MockEnvironment;

@NoArgsConstructor
class RemovedSettingsEnvironmentPostProcessorTest {

  private final RemovedSettingsEnvironmentPostProcessor processor =
      new RemovedSettingsEnvironmentPostProcessor();
  private final SpringApplication application = new SpringApplication();

  @Test
  void theOldResponderPrefixStopsStartUpNamingTheReplacement() {
    final MockEnvironment env =
        new MockEnvironment().withProperty("yukta.input.responders.ops.url", "https://x");

    assertThatThrownBy(() -> processor.postProcessEnvironment(env, application))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("yukta.input.responders.ops.url")
        .hasMessageContaining("yukta.notifications.channels.webhook.<name>");
  }

  @Test
  void theEnvironmentVariableFormIsCaughtToo() {
    final MockEnvironment env = new MockEnvironment();
    env.getPropertySources()
        .addFirst(new MapPropertySource("env", Map.of("YUKTA_INPUT_RESPONDERS_OPS_URL", "x")));

    assertThatThrownBy(() -> processor.postProcessEnvironment(env, application))
        .hasMessageContaining("YUKTA_INPUT_RESPONDERS_OPS_URL");
  }

  @Test
  void otherSettingsAndSourcesThatCannotBeListedAreFine() {
    final MockEnvironment env = new MockEnvironment().withProperty("yukta.input.default-ttl", "1h");
    env.getPropertySources()
        .addFirst(
            new PropertySource<>("opaque", new Object()) {
              @Override
              public Object getProperty(final String name) {
                return null;
              }
            });

    assertThatCode(() -> processor.postProcessEnvironment(env, application))
        .doesNotThrowAnyException();
  }
}
