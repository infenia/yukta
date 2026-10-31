// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RunModeTest {

  private static final Map<String, String> NO_ENV = Map.of();

  @Test
  void defaultsToConsoleOnTerminalElsePlain() {
    assertThat(RunMode.resolve(NO_ENV, true)).isEqualTo(RunMode.CONSOLE);
    assertThat(RunMode.resolve(NO_ENV, false)).isEqualTo(RunMode.PLAIN);
  }

  @Test
  void environmentOverridesTheDetection() {
    assertThat(RunMode.resolve(Map.of("YUKTA_MODE", "daemon"), true)).isEqualTo(RunMode.DAEMON);
  }

  @Test
  void flagOverridesTheEnvironment() {
    final var env = Map.of("YUKTA_MODE", "daemon");
    assertThat(RunMode.resolve(env, true, "--mode=console")).isEqualTo(RunMode.CONSOLE);
    assertThat(RunMode.resolve(env, true, "--mode", "Plain")).isEqualTo(RunMode.PLAIN);
  }

  @Test
  void plainFlagWinsOverEverything() {
    assertThat(RunMode.resolve(Map.of("YUKTA_MODE", "daemon"), true, "--mode=console", "--plain"))
        .isEqualTo(RunMode.PLAIN);
  }

  @Test
  void rejectsAnUnknownMode() {
    assertThatThrownBy(() -> RunMode.resolve(NO_ENV, true, "--mode=fancy"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fancy");
  }
}
